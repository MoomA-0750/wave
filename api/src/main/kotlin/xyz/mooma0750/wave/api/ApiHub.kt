package xyz.mooma0750.wave.api

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Pairing
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.ClientPersisted
import xyz.mooma0750.wave.handoff.v2.CommandBody
import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.ConnectJson
import xyz.mooma0750.wave.handoff.v2.ControlAction
import xyz.mooma0750.wave.handoff.v2.CursorBody
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.MemberKeys
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.TimeSource

/**
 * wave-api の中身（ADR-0015）。家の端末の1つ（player）として中継につながり、家の再生を読み、遠隔操作を送る。音は出さず、再生を引き取らない。
 * ConnectClient は1本のスレッド（engine）だけで触る。ログに認証情報・曲の中身・端末 ID を出さない。
 */
class ApiHub(
    private val data: DataDir,
    private val keys: ApiKeys,
    relayUrl: String,
    relayHost: String,
    private val scope: CoroutineScope,
) {
    private val engine = Executors.newSingleThreadExecutor { Thread(it, "wave-api-engine").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val clock = TimeSource.Monotonic.markNow()
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private val loginStore = LoginStore(data)
    private val auth = AuthManager(loginStore)
    private val http = createWaveHttpClient(AuthGuardEngine(OkHttp.create()))
    private val session = WaveSession(http, auth, scope, rethrowViolations = false, contentLanguage = "ja", contentRegion = "JP")
    val music = MusicRepository(session)

    val relay: RelayLink = RelayLink(keys, relayUrl, relayHost, scope, deliver = { message -> post { onRelayMessage(message) } }, onStatus = { status -> post { onRelayStatus(status) } })

    private lateinit var client: ConnectClient

    /** 自分で計算したアカウントタグ（家の ID とログインに結び付ける）。無ければ鳴っている端末のカーソルのものを借りる。 */
    private var ownTag: Pair<String, String>? = null

    @Serializable
    data class Label(
        val name: String,
        val kind: String? = null,
    )

    private val labels = HashMap<String, Label>()
    private var profileSentTo: Set<String> = emptySet()
    private var wasMember = false

    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 家の再生の表示が変わったかもしれない（SSE が playback を流し直す）。 */
    val changes: SharedFlow<Unit> = mutableChanges

    sealed interface PairState {
        data object Idle : PairState

        data class Working(
            val step: String,
        ) : PairState

        /** 家の端末でこの6桁を入れてもらう。入れたら家に加えてもらえるので、こちらは加入を待つ。 */
        data class ShowCode(
            val code: String,
            val peerName: String,
        ) : PairState

        data class Joined(
            val peerName: String?,
        ) : PairState

        data class Failed(
            val reason: String,
        ) : PairState
    }

    @Volatile
    var pairState: PairState = PairState.Idle
        private set

    fun start() {
        scope.launch {
            auth.restore()
            refreshTag()
        }
        post {
            data.read(DataDir.LABELS)?.let { text -> runCatching { json.decodeFromString(MapSerializer(String.serializer(), Label.serializer()), text) }.getOrNull()?.let(labels::putAll) }
            val initial = data.read(DataDir.CLIENT)?.let { runCatching { ConnectJson.json.decodeFromString(ClientPersisted.serializer(), it) }.getOrNull() } ?: ClientPersisted()
            client = newClient(initial)
            wasMember = initial.chain.isNotEmpty()
            relay.onLost = { post { client.onDisconnected() } }
            if (wasMember || initial.expectedJoin != null) relay.start()
            log("started member=$wasMember")
        }
        // 時間の経過（transfer の待ち・状態の外挿）
        scope.launch {
            while (true) {
                delay(1_000)
                post {
                    client.tick()
                    retryHandOff()
                }
            }
        }
    }

    /** 「鳴らす」で引き取りを頼んだ端末と、頼んだ時刻・回数（引き取られなければ頼み直す）。engine の上で触る。 */
    private class HandOffWait(
        val target: String,
        var sentAtMs: Long,
        var attempts: Int,
    )

    private var handOffWait: HandOffWait? = null

    /** wave-api 以外で最後に家の再生を書いた端末（wave-api が書き手のままになったときの行き先）。engine の上で触る。 */
    private var lastOtherWriter: String? = null

    /**
     * 引き取りが失敗した（行き先の端末が鳴っている最中に、こちらが再生を引き受けた直後など）ままだと、家の再生が wave-api に残って
     * どの端末でも鳴らない。数秒たっても書き手が自分なら、同じ端末に引き取りを頼み直す。
     */
    private fun retryHandOff() {
        val wait = handOffWait ?: return
        val writer = client.latestCursorBody()?.first
        if (writer != null && writer != keys.deviceId) {
            handOffWait = null
            return
        }
        if (nowMs() - wait.sentAtMs < HANDOFF_RETRY_MS) return
        if (wait.attempts >= HANDOFF_ATTEMPTS || writer != keys.deviceId) {
            log("handoff gave up attempts=${wait.attempts}")
            handOffWait = null
            return
        }
        wait.attempts++
        wait.sentAtMs = nowMs()
        log("handoff retry attempt=${wait.attempts} sent=${client.requestRemoteTransfer(wait.target)}")
    }

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun post(block: () -> Unit) {
        scope.launch(engine) {
            runCatching(block).onFailure { log("engine error type=${it::class.simpleName}") }
        }
    }

    private suspend fun <T> onEngine(block: () -> T): T = withContext(engine) { block() }

    private fun newClient(initial: ClientPersisted) =
        ConnectClient(
            signer = keys,
            opener = keys,
            role = Role.Player,
            send = { relay.send(it) },
            effects = effects,
            nowMs = ::nowMs,
            epochMs = System::currentTimeMillis,
            newId = { UUID.randomUUID().toString() },
            initial = initial,
        )

    private val effects =
        object : ConnectClient.Effects {
            // 音を出さないので、止めるものも取り込むものも無い（家の再生を引き取らない）
            override fun pause(): ConnectClient.LocalCursor? = null

            override fun adopt(
                snapshot: String,
                cursor: CursorBody,
                positionMs: Long,
            ): Boolean = false

            override fun event(event: ClientEvent) {
                when (event) {
                    is ClientEvent.Profile -> {
                        val label = Label(Pairing.sanitizeName(event.name), event.kind?.take(16))
                        if (labels[event.deviceId] != label) {
                            labels[event.deviceId] = label
                            saveLabels()
                        }
                    }
                    ClientEvent.Joined -> {
                        log("joined")
                        wasMember = true
                        pairState = PairState.Joined((pairState as? PairState.ShowCode)?.peerName)
                        sendProfile(force = true)
                        scope.launch { refreshTag() }
                    }
                    is ClientEvent.RemoteChanged -> if (event.writer != keys.deviceId) lastOtherWriter = event.writer
                    else -> log("event=${event::class.simpleName}")
                }
                mutableChanges.tryEmit(Unit)
            }

            override fun persist(state: ClientPersisted) {
                runCatching { data.write(DataDir.CLIENT, ConnectJson.json.encodeToString(ClientPersisted.serializer(), state)) }
                    .onFailure { log("persist failed type=${it::class.simpleName}") }
            }

            override fun accountTag(): String? {
                val household = client.householdId ?: return null
                ownTag?.takeIf { it.first == household }?.let { return it.second }
                return client.latestCursorBody()?.second?.accountTag
            }

            override fun loginGranted(grant: CommandBody.LoginGrant) {
                log("login granted")
                scope.launch {
                    val credentials = Credentials.fromLogin(grant.cookie, grant.visitorData, grant.dataSyncId, grant.obtainedAtEpochMs)
                    if (credentials == null) {
                        log("login grant unreadable")
                        return@launch
                    }
                    log("login saved result=${auth.completeLogin(credentials)::class.simpleName}")
                    refreshTag()
                    mutableChanges.tryEmit(Unit)
                }
            }
        }

    /** ログインの確かめたアカウントから、家ごとのアカウントタグを作る（Android の AccountTag と同じ）。 */
    private suspend fun refreshTag() {
        val household = onEngine { if (::client.isInitialized) client.householdId else null } ?: return
        val account = session.verifiedAccount() ?: return
        val tag = AccountTag.of(household, account.id.reveal())
        onEngine { ownTag = household to tag }
    }

    private fun saveLabels() {
        runCatching { data.write(DataDir.LABELS, json.encodeToString(MapSerializer(String.serializer(), Label.serializer()), labels)) }
    }

    private fun onRelayMessage(message: RelayV2) {
        client.onMessage(message)
        if (message is RelayV2.Welcome || message is RelayV2.Presence) sendProfile(force = message is RelayV2.Welcome)
        checkRemoved()
        mutableChanges.tryEmit(Unit)
    }

    private fun onRelayStatus(status: RelayLink.Status) {
        if (status == RelayLink.Status.Removed) wipe("removed")
        mutableChanges.tryEmit(Unit)
    }

    /**
     * 家のほかの端末に名前と種類（api）を知らせる。新しくつながった端末がいれば送り直す。
     * 切れた端末は送った先から外す（アプリを入れ直してつなぎ直した端末は、前の版が知らなかった種類を捨てていることがある）。
     */
    private fun sendProfile(force: Boolean) {
        val online = client.onlineDevices()
        profileSentTo = profileSentTo intersect online
        if (!force && (online - profileSentTo - keys.deviceId).isEmpty()) return
        if (client.sendProfile(NAME, KIND)) profileSentTo = online
    }

    /** 家の一覧から自分が外された: 家の記録とログインを消す（身元の鍵とトークンは残す。加え直せる）。 */
    private fun checkRemoved() {
        val chain = client.verifiedChain() ?: return
        if (keys.deviceId !in chain.members && wasMember) wipe("not_in_chain")
    }

    private fun wipe(reason: String) {
        log("removed from household reason=$reason")
        wasMember = false
        relay.stop()
        data.delete(DataDir.CLIENT)
        data.delete(DataDir.LABELS)
        labels.clear()
        ownTag = null
        client = newClient(ClientPersisted())
        scope.launch { auth.logout() }
    }

    // --- 加入（ADR-0015。B の側。Android の PairingV2Controller.runAccept と同じ手順） ---

    /** 家の端末が出した QR の文字で加わる。6桁を返す（家の端末に入れてもらう）。その後は加入を待つ。 */
    suspend fun pair(offerText: String): PairState {
        if (onEngine { client.householdId != null }) return PairState.Failed("already_joined").also { pairState = it }
        val offer = Pairing.decodeOffer(offerText.trim()) ?: return PairState.Failed("not_wave_pair").also { pairState = it }
        pairState = PairState.Working("sending")
        val memberKeys = ConnectJson.json.encodeToString(MemberKeys.serializer(), MemberChain.memberKeys(keys, keys.kxPub, Role.Player))
        val initiator = Pairing.Initiator(offer, keys.identityPrivate, keys.identityPublic, NAME, accountId = null, helloExtension = memberKeys)
        val reply = relay.pair(offer.token, json.encodeToJsonElement(Pairing.Hello.serializer(), initiator.hello()))
        val confirmed = reply as? RelayV2.PairConfirmed ?: return PairState.Failed(if (reply == null) "relay_unreachable" else "peer_not_waiting").also { pairState = it }
        val confirm = runCatching { json.decodeFromJsonElement(Pairing.Confirm.serializer(), confirmed.confirm) }.getOrNull() ?: return PairState.Failed("bad_reply").also { pairState = it }
        val (code, established) = initiator.onConfirm(confirm).getOrElse { return PairState.Failed("peer_unverified").also { pairState = it } }
        val info = established.peerExtension?.let { runCatching { ConnectJson.json.decodeFromString(ConnectClient.JoinInfo.serializer(), it) }.getOrNull() } ?: return PairState.Failed("peer_unsupported").also { pairState = it }
        val inviter = EnvelopeSealer.deviceId(established.peerPublicKey)
        onEngine {
            client.expectJoin(info, inviter)
            labels[inviter] = Label(established.peerDeviceName, labels[inviter]?.kind)
            saveLabels()
        }
        val showing = PairState.ShowCode(code, established.peerDeviceName)
        pairState = showing
        scope.launch { awaitJoin() }
        return showing
    }

    /** 加えてもらうまで、間を空けてつなぎ直す（加えてもらう前は中継が認証で断る）。 */
    private suspend fun awaitJoin() {
        relay.start()
        val joined =
            withTimeoutOrNull(JOIN_WAIT_MS) {
                while (pairState !is PairState.Joined) {
                    delay(JOIN_RETRY_MS)
                    if (pairState is PairState.Joined) break
                    if (relay.status != RelayLink.Status.Connected) relay.restart()
                }
                true
            }
        if (joined == null) {
            pairState = PairState.Failed("join_timed_out")
            onEngine {
                client.cancelJoin()
                if (client.householdId == null) relay.stop()
            }
        }
    }

    // --- 読む ---

    suspend fun playback(): PlaybackView = onEngine { buildPlayback() }

    private fun buildPlayback(): PlaybackView {
        val observedAt = Instant.now().toString()
        val loggedIn = loginStore.exists()
        val joined = ::client.isInitialized && client.verifiedChain()?.members?.containsKey(keys.deviceId) == true
        val latest = if (joined) client.latestCursorBody() else null
        if (latest == null) return PlaybackView(joined, loggedIn, false, "off", null, null, observedAt, null, null)
        val (writer, cursor) = latest
        val snapshot = client.latestQueueBody()?.second?.snapshot?.let(Views::snapshot)
        val item = Views.currentItem(snapshot, cursor)
        val online = writer in client.onlineDevices()
        val position = client.remoteNow()?.positionMs ?: cursor.positionMs
        return PlaybackView(
            joined = true,
            loggedIn = loggedIn,
            isPlaying = cursor.playing && online,
            repeat = Views.repeat(cursor.repeat),
            track = item?.let(Views::nowPlaying),
            progressMs = if (item?.isLive == true) null else position,
            observedAt = observedAt,
            device = device(writer, active = true),
            volumePercent = cursor.volumePercent,
        )
    }

    suspend fun queue(): List<TrackView> =
        onEngine {
            val cursor = client.latestCursorBody()?.second ?: return@onEngine emptyList()
            Views.upcoming(client.latestQueueBody()?.second?.snapshot?.let(Views::snapshot), cursor).map(Views::track)
        }

    suspend fun devices(): List<DeviceView> =
        onEngine {
            val chain = client.verifiedChain() ?: return@onEngine emptyList()
            val writer = client.latestCursorBody()?.first
            chain.members.keys.sorted().map { device(it, active = it == writer) }
        }

    private fun device(
        id: String,
        active: Boolean,
    ): DeviceView {
        val label = labels[id]
        val self = id == keys.deviceId
        return DeviceView(
            id = id,
            name = if (self) NAME else label?.name ?: "端末 ${id.take(6)}",
            kind = if (self) KIND else label?.kind,
            online = self || id in client.onlineDevices(),
            active = active,
            self = self,
        )
    }

    val loggedIn: Boolean get() = loginStore.exists()

    // --- 操作 ---

    /** 鳴らしている端末へ遠隔操作を送る。@return 送ったか */
    suspend fun control(
        action: ControlAction,
        positionMs: Long?,
        volumePercent: Int?,
    ): Boolean =
        onEngine {
            when (action) {
                ControlAction.Volume -> volumePercent != null && client.sendVolume(volumePercent)
                ControlAction.Transfer -> false
                else -> client.sendControl(action, positionMs)
            }
        }

    suspend fun transfer(deviceId: String): Boolean = onEngine { deviceId != keys.deviceId && client.requestRemoteTransfer(deviceId) }

    suspend fun queueAdd(
        items: List<HandoffItem>,
        next: Boolean,
    ): Boolean = onEngine { client.sendQueueAdd(items, next) }

    sealed interface PlayResult {
        data class Sent(
            val deviceId: String,
        ) : PlayResult

        data object NoDevice : PlayResult

        data object NotSent : PlayResult
    }

    /**
     * キューを家に置き、行き先の端末に引き取ってもらう（ほかの端末で鳴っている間に曲を選んだときと同じ、ConnectClient.playOn）。
     * 行き先は requested、無ければ家の最後の再生を書いた端末（どちらも中継につながっているとき）。
     */
    suspend fun play(
        snapshot: HandoffSnapshot,
        requested: String?,
    ): PlayResult =
        onEngine {
            val chain = client.verifiedChain() ?: return@onEngine PlayResult.NoDevice
            val players = chain.members.filterValues { it.keys.role == Role.Player }.keys
            val writer = client.latestCursorBody()?.first
            if (writer != null && writer != keys.deviceId) lastOtherWriter = writer
            val target = Views.target(requested, keys.deviceId, players, client.onlineDevices(), writer.takeUnless { it == keys.deviceId } ?: lastOtherWriter) ?: return@onEngine PlayResult.NoDevice
            val encoded = HandoffCodec.encode(snapshot)
            // 作ったキューが受け手の検査を通るか確かめる（通らないキューを置くと、どの端末も引き取れず家の再生が wave-api に残る）
            (HandoffCodec.decode(encoded, snapshot.handoffId) as? xyz.mooma0750.wave.handoff.HandoffDecodeResult.Rejected)?.let {
                log("queue rejected reason=${it.reason}")
                return@onEngine PlayResult.NotSent
            }
            client.setLocalQueue(encoded)
            client.setLocalCursor(ConnectClient.LocalCursor(snapshot.currentItemId, 0, false, snapshot.repeat, 0, false, nowMs(), null))
            if (client.playOn(target)) {
                handOffWait = HandOffWait(target, nowMs(), 0)
                PlayResult.Sent(target)
            } else {
                PlayResult.NotSent
            }
        }

    sealed interface JumpResult {
        data class Played(
            val result: PlayResult,
        ) : JumpResult

        data object NotFound : JumpResult
    }

    /** キューの曲へ飛ぶ（Views.jump）。今鳴らしている端末に、選んだ曲からのキューを引き取り直してもらう。 */
    suspend fun jump(
        index: Int?,
        videoId: String?,
    ): JumpResult {
        val (writer, snapshot) =
            onEngine {
                val (writer, cursor) = client.latestCursorBody() ?: return@onEngine null
                val queue = client.latestQueueBody()?.second?.snapshot?.let(Views::snapshot)
                Views.jump(queue, cursor, index, videoId, java.util.UUID.randomUUID().toString(), System.currentTimeMillis())?.let { writer to it }
            } ?: return JumpResult.NotFound
        return JumpResult.Played(play(snapshot, writer.takeUnless { it == keys.deviceId }))
    }

    companion object {
        const val NAME = "wave API"
        const val KIND = "api"
        const val JOIN_WAIT_MS = 5 * 60 * 1000L
        const val JOIN_RETRY_MS = 4_000L

        /** 引き取りを頼み直すまでの間と回数（中継の引き取りの期限は10秒）。 */
        const val HANDOFF_RETRY_MS = 4_000L
        const val HANDOFF_ATTEMPTS = 2
    }
}

/** 家ごとのアカウントタグ（D-3、Android の AccountTag と同じ）: `SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)` を base64url（詰め物なし）で。値はログに出さない。 */
internal object AccountTag {
    const val LABEL = "wave-v2-account-tag"

    fun of(
        householdId: String,
        accountId: String,
    ): String {
        val digest = WaveCrypto.sha256(WaveCrypto.lengthPrefixed(LABEL.encodeToByteArray(), householdId.encodeToByteArray(), accountId.encodeToByteArray()))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
