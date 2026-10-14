package xyz.mooma0750.wave.relay.v2

import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.ClientPersisted
import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.CursorBody
import xyz.mooma0750.wave.handoff.v2.DeviceSigner
import xyz.mooma0750.wave.handoff.v2.KxOpener
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.MemberKeys
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import xyz.mooma0750.wave.handoff.v2.StateKind
import java.io.File
import java.nio.file.Files
import java.security.interfaces.ECPublicKey
import java.util.UUID

/**
 * 端末なしの多端末テストの中継（Phase 6b、Opus 中-8）。本物の HouseholdStore と、中継と同じ振り分け（HouseholdRouter）を使う。
 * 届ける前に hook で落とす・遅らせることができる（敵対的なサーバー）。届けるのは run() のときだけ（決まった順）。
 */
class FakeRelay(
    bootstrapKeys: List<String>,
    val file: File = File(Files.createTempDirectory("fake-relay").toFile(), "household.json"),
    private val nowMs: () -> Long,
) {
    var store = HouseholdStore(file, bootstrapKeys, nowMs)
    private val connected = LinkedHashMap<String, (RelayV2) -> Unit>()
    private val librarySubscribers = HashSet<String>()
    private val outbox = ArrayDeque<Pair<String, RelayV2>>()
    private val held = ArrayList<Pair<String, RelayV2>>()
    val log = ArrayList<String>()

    /** true を返すと、その端末へのそのメッセージを落とす。 */
    var drop: (to: String, message: RelayV2) -> Boolean = { _, _ -> false }

    /** true を返すと、release() まで届けない。 */
    var hold: (to: String, message: RelayV2) -> Boolean = { _, _ -> false }

    /** true を返すと、端末からのそのメッセージを受けない（届かなかった）。 */
    var dropIncoming: (from: String, message: RelayV2) -> Boolean = { _, _ -> false }

    fun connect(
        deviceId: String,
        deliver: (RelayV2) -> Unit,
    ) {
        connected[deviceId] = deliver
        post(deviceId, store.welcome(deviceId, connected.keys.toList()))
        broadcast(RelayV2.Presence(connected.keys.toList()))
    }

    fun disconnect(deviceId: String) {
        connected.remove(deviceId)
        librarySubscribers.remove(deviceId)
        router.broadcastExpired(store.expirePending(disconnected = deviceId), outlet(null))
        broadcast(RelayV2.Presence(connected.keys.toList()))
    }

    fun isConnected(deviceId: String) = deviceId in connected

    fun tick() {
        router.broadcastExpired(store.expirePending(), outlet(null))
    }

    fun releaseHeld() {
        held.forEach { outbox.addLast(it) }
        held.clear()
    }

    /** 届くものが無くなるまで届ける。 */
    fun run() {
        var guard = 0
        while (outbox.isNotEmpty()) {
            check(guard++ < 10_000) { "メッセージが止まらない" }
            val (to, message) = outbox.removeFirst()
            val deliver = connected[to] ?: continue
            if (drop(to, message)) continue
            if (hold(to, message)) {
                held += to to message
                continue
            }
            log += "${to.take(4)}<-${message::class.simpleName}${(message as? RelayV2.ActiveChanged)?.let { "(" + it.activeDeviceId?.take(4) + "," + it.claimOrder + ")" } ?: ""}${(message as? RelayV2.Error)?.code?.let { "($it)" } ?: ""}"
            deliver(message)
        }
    }

    private fun post(
        to: String,
        message: RelayV2,
    ) = outbox.addLast(to to message)

    private fun broadcast(
        message: RelayV2,
        except: String? = null,
    ) = connected.keys.filter { it != except }.forEach { post(it, message) }

    /** @return 中継に届いたか（つながっていなければ false。端末の send の戻り値） */
    fun fromClient(
        deviceId: String,
        message: RelayV2,
    ): Boolean {
        if (deviceId !in connected) return false
        if (dropIncoming(deviceId, message)) return true
        handle(deviceId, message)
        return true
    }

    /** 振り分けは中継（HubV2）と同じもの。 */
    private val router = HouseholdRouter({ store })

    private fun outlet(deviceId: String?) =
        object : HouseholdRouter.Outlet {
            override fun reply(message: RelayV2) {
                deviceId?.let { post(it, message) }
            }

            override fun broadcast(
                message: RelayV2,
                except: String?,
            ) = this@FakeRelay.broadcast(message, except)

            override fun removed(deviceId: String) {
                connected.remove(deviceId)
            }

            override fun subscribeLibrary() {
                deviceId?.let { librarySubscribers += it }
            }

            override fun broadcastLibrary(
                message: RelayV2,
                except: String?,
            ) = connected.keys.filter { it != except && it in librarySubscribers }.forEach { post(it, message) }
        }

    private fun handle(
        deviceId: String,
        message: RelayV2,
    ) {
        if (!router.route(deviceId, message, outlet(deviceId))) post(deviceId, RelayV2.Error("unsupported"))
    }

    /** 敵対的なサーバー: 端末へ任意のメッセージを差し込む。 */
    fun inject(
        to: String,
        message: RelayV2,
    ) = post(to, message)
}

/** シミュレーションの端末: 鍵・ConnectClient・偽の再生。 */
class SimDevice(
    val name: String,
    private val relay: () -> FakeRelay,
    private val clock: () -> Long,
    role: Role = Role.Player,
    var accountTag: String? = "tag-account-1",
    /** 端末の時計のずれ（順序の判断に使われないことを確かめる）。 */
    private val epochSkewMs: Long = 0,
) : DeviceSigner, KxOpener {
    private val identity = WaveCrypto.generateEphemeralKeyPair()
    private val kx = WaveCrypto.generateEphemeralKeyPair()
    override val identityPub: ByteArray = WaveCrypto.encodePublicKey(identity.public)
    override val deviceId: String = EnvelopeSealer.deviceId(identityPub)
    override val kxPub: ECPublicKey = kx.public as ECPublicKey
    val publicKeyB64: String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(identityPub)
    val keys: MemberKeys = MemberChain.memberKeys(this, kxPub, role)

    override fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = WaveCrypto.sign(identity.private, label, message)

    override fun dh(peer: ECPublicKey): ByteArray = WaveCrypto.ecdh(kx.private, peer)

    // 偽の再生
    var playing = false
        set(value) {
            // 再生・停止の切り替えで、そこまでの位置を確定させる
            val current = positionNow()
            field = value
            positionAnchor = current
            anchorAt = clock()
        }
    var queue: String? = null

    /** 偽のアプリの中の音量（0〜100）。 */
    var volumePercent = 70
    private var positionAnchor = 0L
    private var anchorAt = 0L

    /** 偽の再生の位置（再生中は時間とともに進む）。代入するとそこから数え直す。 */
    var positionMs: Long
        get() = positionNow()
        set(value) {
            positionAnchor = value
            anchorAt = clock()
        }

    private fun positionNow(): Long = if (playing) positionAnchor + (clock() - anchorAt) else positionAnchor
    var currentItemId = "item-0"
    val events = ArrayList<ClientEvent>()

    /** 中継から届いたライブラリ（合わせた後のもの）。 */
    var library: xyz.mooma0750.wave.handoff.v2.LibraryBody? = null

    /** 従った遠隔操作。 */
    val controls = ArrayList<Pair<xyz.mooma0750.wave.handoff.v2.ControlAction, Long?>>()
    /** 従ったキューへの追加（項目の ID・次に再生か・どの曲の後ろか）。 */
    val queueAdds = ArrayList<Triple<List<String>, Boolean, String?>>()
    var persisted = ClientPersisted()
    var persistCount = 0
    var foreground = true

    /** false なら、止めた時点の位置を返せない（再生サービスがいない）。 */
    var pauseCapturesPosition = true
    private val role = role

    var client: ConnectClient = newClient(ClientPersisted())
        private set

    /** プロセスが落ちて起き直した（保存から読み直す）。 */
    fun restart(initial: ClientPersisted = persisted) {
        client = newClient(initial)
    }

    private fun newClient(initial: ClientPersisted): ConnectClient =
        ConnectClient(
            signer = this,
            opener = this,
            role = role,
            send = { message -> relay().fromClient(deviceId, message) },
            effects =
                object : ConnectClient.Effects {
                    override fun pause(): ConnectClient.LocalCursor? {
                        playing = false
                        return if (pauseCapturesPosition) localCursor() else null
                    }

                    override fun adopt(
                        snapshot: String,
                        cursor: CursorBody,
                        positionMs: Long,
                    ): Boolean {
                        if (!foreground) return false
                        queue = snapshot
                        currentItemId = cursor.currentItemId
                        this@SimDevice.positionMs = positionMs
                        playing = true
                        return true
                    }

                    override fun event(event: ClientEvent) {
                        events += event
                    }

                    override fun persist(state: ClientPersisted) {
                        persisted = state
                        persistCount++
                    }

                    override fun accountTag(): String? = accountTag

                    override fun libraryChanged(body: xyz.mooma0750.wave.handoff.v2.LibraryBody) {
                        library = body
                    }

                    override fun volume(volumePercent: Int): Boolean {
                        this@SimDevice.volumePercent = volumePercent
                        return true
                    }

                    override fun queueAdd(
                        items: List<xyz.mooma0750.wave.handoff.HandoffItem>,
                        next: Boolean,
                        afterItemId: String?,
                    ): Boolean {
                        queueAdds += Triple(items.map { it.itemId }, next, afterItemId)
                        return true
                    }

                    override fun control(
                        action: xyz.mooma0750.wave.handoff.v2.ControlAction,
                        positionMs: Long?,
                    ): Boolean {
                        controls += action to positionMs
                        when (action) {
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Play -> playing = true
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Pause -> playing = false
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Seek -> this@SimDevice.positionMs = positionMs ?: 0
                            else -> Unit
                        }
                        return true
                    }
                },
            nowMs = clock,
            epochMs = { clock() + epochSkewMs },
            newId = { UUID.randomUUID().toString() },
            initial = initial,
        )

    fun connect() = relay().connect(deviceId) { client.onMessage(it) }

    fun disconnect() {
        relay().disconnect(deviceId)
        client.onDisconnected()
    }

    /** 本人が手元で曲を選んで再生を始めた。 */
    fun startPlaying(
        snapshot: String,
        item: String = "item-0",
        position: Long = 0,
    ) {
        queue = snapshot
        currentItemId = item
        positionMs = position
        playing = true
        client.setLocalQueue(snapshot)
        pushCursor()
        client.userStartedPlayback()
    }

    fun pushCursor() {
        client.setLocalCursor(localCursor())
    }

    fun localCursor() = ConnectClient.LocalCursor(currentItemId, positionMs, playing, HandoffRepeat.Off, 0, false, clock(), volumePercent)

    fun stoppedBy(other: SimDevice) = events.any { it is ClientEvent.Stopped && it.by == other.deviceId }
}
