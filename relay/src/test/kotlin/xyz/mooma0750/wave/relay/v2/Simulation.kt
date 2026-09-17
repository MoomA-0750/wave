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
 * 端末なしの多端末テストの中継（Phase 6b、Opus 中-8）。本物の HouseholdStore を使い、振り分けは HubV2 と同じ。
 * 届ける前に hook で落とす・遅らせることができる（敵対的なサーバー）。届けるのは run() のときだけ（決まった順）。
 */
class FakeRelay(
    bootstrapKeys: List<String>,
    val file: File = File(Files.createTempDirectory("fake-relay").toFile(), "household.json"),
    private val nowMs: () -> Long,
) {
    var store = HouseholdStore(file, bootstrapKeys, nowMs)
    private val connected = LinkedHashMap<String, (RelayV2) -> Unit>()
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
    }

    fun disconnect(deviceId: String) {
        connected.remove(deviceId)
        store.expirePending(disconnected = deviceId)?.let { (claimId, active) ->
            broadcast(RelayV2.PendingExpired(claimId))
            broadcast(active)
        }
    }

    fun isConnected(deviceId: String) = deviceId in connected

    fun tick() {
        store.expirePending()?.let { (claimId, active) ->
            broadcast(RelayV2.PendingExpired(claimId))
            broadcast(active)
        }
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

    private fun handle(
        deviceId: String,
        message: RelayV2,
    ) {
        when (message) {
            is RelayV2.ChainGet -> post(deviceId, RelayV2.Chain(store.chainFrom(message.fromSeq)))
            is RelayV2.ChainAppend ->
                when (val result = store.append(deviceId, message.record)) {
                    is HouseholdStore.ChainResult.Ok -> {
                        post(deviceId, RelayV2.ChainOk(result.headSeq, result.headHash))
                        broadcast(RelayV2.ChainChanged(result.headSeq, result.headHash), except = deviceId)
                        result.activeChanged?.let { broadcast(it) }
                        result.removed?.let { connected.remove(it) }
                    }
                    is HouseholdStore.ChainResult.Conflict -> post(deviceId, RelayV2.ChainConflict(result.headSeq, result.headHash))
                    is HouseholdStore.ChainResult.Rejected -> post(deviceId, RelayV2.Error(result.code))
                }
            RelayV2.StateGet -> store.latest().forEach { post(deviceId, RelayV2.State(it)) }
            is RelayV2.StatePut ->
                when (val result = store.put(deviceId, message)) {
                    is HouseholdStore.PutResult.Ok -> {
                        post(deviceId, RelayV2.StateOk(message.requestId, message.kind, result.state.version))
                        broadcast(RelayV2.State(result.state), except = deviceId)
                        result.activeChanged?.let { broadcast(it) }
                    }
                    is HouseholdStore.PutResult.Conflict -> post(deviceId, RelayV2.StateConflict(message.requestId, message.kind, result.latest))
                    is HouseholdStore.PutResult.Rejected -> post(deviceId, RelayV2.Error(result.code, message.requestId))
                }
            is RelayV2.Claim ->
                when (val result = store.claim(deviceId, message.claimId, message.yieldDoc)) {
                    is HouseholdStore.ClaimResult.Ok -> {
                        store.takeDroppedPending()?.let { broadcast(RelayV2.PendingExpired(it)) }
                        broadcast(result.activeChanged)
                        broadcast(RelayV2.YieldDelivery(message.claimId, message.yieldDoc), except = deviceId)
                    }
                    is HouseholdStore.ClaimResult.Conflict -> post(deviceId, RelayV2.StateConflict(message.claimId, StateKind.Cursor, result.latest))
                    is HouseholdStore.ClaimResult.Rejected -> post(deviceId, RelayV2.Error(result.code, message.claimId))
                }
            is RelayV2.Transfer ->
                when (val result = store.transfer(deviceId, message.claimId, message.basedOnVersion, message.yieldDoc)) {
                    is HouseholdStore.ClaimResult.Ok -> {
                        store.takeDroppedPending()?.let { broadcast(RelayV2.PendingExpired(it)) }
                        broadcast(result.activeChanged)
                        broadcast(RelayV2.YieldDelivery(message.claimId, message.yieldDoc), except = deviceId)
                    }
                    is HouseholdStore.ClaimResult.Conflict -> post(deviceId, RelayV2.StateConflict(message.claimId, StateKind.Cursor, result.latest))
                    is HouseholdStore.ClaimResult.Rejected -> post(deviceId, RelayV2.Error(result.code, message.claimId))
                }
            is RelayV2.Yielded ->
                when (val result = store.yielded(deviceId, message)) {
                    is HouseholdStore.PutResult.Ok -> {
                        post(deviceId, RelayV2.StateOk(message.claimId, StateKind.Cursor, result.state.version))
                        broadcast(RelayV2.State(result.state), except = deviceId)
                    }
                    is HouseholdStore.PutResult.Conflict -> post(deviceId, RelayV2.StateConflict(message.claimId, StateKind.Cursor, result.latest))
                    is HouseholdStore.PutResult.Rejected -> post(deviceId, RelayV2.Error(result.code, message.claimId))
                }
            is RelayV2.Release -> store.release(deviceId, message.claimId)?.let { broadcast(it) }
            else -> post(deviceId, RelayV2.Error("unsupported"))
        }
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
    var queue: String? = null
    var positionMs = 0L
    var currentItemId = "item-0"
    val events = ArrayList<ClientEvent>()
    var persisted = ClientPersisted()
    var foreground = true

    val client: ConnectClient =
        ConnectClient(
            signer = this,
            opener = this,
            role = role,
            send = { message -> relay().fromClient(deviceId, message) },
            effects =
                object : ConnectClient.Effects {
                    override fun pause() {
                        playing = false
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
                    }

                    override fun accountTag(): String? = accountTag
                },
            nowMs = clock,
            epochMs = { clock() + epochSkewMs },
            newId = { UUID.randomUUID().toString() },
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
        client.setLocalCursor(ConnectClient.LocalCursor(currentItemId, positionMs, playing, HandoffRepeat.Off, 0, false))
    }

    fun stoppedBy(other: SimDevice) = events.any { it is ClientEvent.Stopped && it.by == other.deviceId }
}
