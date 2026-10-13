package xyz.mooma0750.wave.relay.v2

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.StateKind
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class RelayV2Config(
    /** 署名の対象に入れるホスト名（tailscale serve は TLS を終端するので、設定から取る）。 */
    val host: String,
    val store: HouseholdStore,
    val nowMs: () -> Long = System::currentTimeMillis,
    /** transfer の期限を見る間隔（テストでは短くする）。 */
    val tickMs: Long = 1_000L,
)

/**
 * 中継 v2（Phase 6b）。`/v2/connect` は家の端末（家が無いときは起動時の設定の鍵の端末）だけ、`/v2/pair` は pairing_open された token の未加入の端末だけ。
 * ログは件数・種類だけ（端末 ID・本文は出さない）。
 */
fun Application.relayV2Module(config: RelayV2Config) {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 90.seconds
        maxFrameSize = RelayV2.MAX_FRAME_CHARS.toLong() + 1024
    }
    val hub = HubV2(config)
    routing {
        get("/health") { call.respondText("ok") }
        webSocket("/v2/connect") { hub.connect(this) }
        webSocket("/v2/pair") { hub.pair(this) }
    }
    launch {
        while (isActive) {
            delay(config.tickMs)
            hub.tick()
        }
    }
}

private class HubV2(
    private val config: RelayV2Config,
) {
    private val store = config.store
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val unauthenticated = AtomicInteger(0)
    private val pairWaiting = AtomicInteger(0)

    /**
     * 家の状態の変更と、その結果の配信を1本に並べる（active_changed・yield・state の順番を、全端末で同じにする。レビュー p6b12 高-1）。
     * 配信は trySend なので、ここでは待たない
     */
    private val order = Any()

    private class PairingSlot(
        val ownerId: String,
        val owner: DefaultWebSocketServerSession,
        val expiresAtMs: Long,
        val confirm: CompletableDeferred<JsonElement> = CompletableDeferred(),
        val helloDelivered: AtomicBoolean = AtomicBoolean(false),
    )

    private val pairings = ConcurrentHashMap<String, PairingSlot>()

    private fun log(message: String) = println("relay-v2 $message")

    private fun send(
        target: DefaultWebSocketServerSession,
        message: RelayV2,
    ): Boolean = runCatching { target.outgoing.trySend(Frame.Text(RelayV2.encode(message))).isSuccess }.getOrDefault(false)

    private fun broadcast(
        message: RelayV2,
        except: String? = null,
    ) {
        sessions.forEach { (id, session) -> if (id != except) send(session, message) }
    }

    /** library_get を送った接続（ライブラリはこの接続にだけ配る、ADR-0013）。order の中で触る。 */
    private val librarySubscribers = HashSet<DefaultWebSocketServerSession>()

    private fun presence() = broadcast(RelayV2.Presence(sessions.keys.toList()))

    private suspend fun <T> gate(
        session: DefaultWebSocketServerSession,
        block: suspend () -> T,
    ): T? {
        if (unauthenticated.incrementAndGet() > MAX_UNAUTHENTICATED) {
            unauthenticated.decrementAndGet()
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return null
        }
        return try {
            block()
        } finally {
            unauthenticated.decrementAndGet()
        }
    }

    suspend fun connect(session: DefaultWebSocketServerSession) {
        val nonce = WaveCrypto.randomBytes(32)
        session.send(Frame.Text(RelayV2.encode(RelayV2.Challenge(config.host, b64.encodeToString(nonce)))))
        val auth =
            gate(session) {
                withTimeoutOrNull(30.seconds) { runCatching { (session.incoming.receive() as? Frame.Text)?.readText() }.getOrNull() }
                    ?.takeIf { it.length <= 4096 }
                    ?.let(RelayV2::decode) as? RelayV2.Auth
            }
        val publicKey = auth?.let { runCatching { unb64.decode(it.publicKey) }.getOrNull() }
        val deviceId = publicKey?.let(EnvelopeSealer::deviceId)
        val key = deviceId?.let(store::authKey)
        val signature = auth?.let { runCatching { unb64.decode(it.signature) }.getOrNull() }
        if (key == null || signature == null || !WaveCrypto.encodePublicKey(key).contentEquals(publicKey) ||
            !WaveCrypto.verify(key, RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(config.host.encodeToByteArray(), nonce), signature)
        ) {
            log("auth rejected")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))
            return
        }
        val previous =
            synchronized(order) {
                val replaced = sessions.put(deviceId, session)
                send(session, store.welcome(deviceId, sessions.keys.toList()))
                presence()
                replaced
            }
        previous?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
        log("connected devices=${sessions.size}")
        try {
            for (frame in session.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                if (text.length > RelayV2.MAX_FRAME_CHARS) {
                    send(session, RelayV2.Error("too_large"))
                    continue
                }
                // 外された端末は、接続が閉じきる前でも受けない
                if (store.authKey(deviceId) == null) {
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "removed"))
                    break
                }
                synchronized(order) { handle(session, deviceId, RelayV2.decode(text)) }
            }
        } finally {
            synchronized(order) {
                librarySubscribers.remove(session)
                if (sessions.remove(deviceId, session)) {
                    pairings.entries.removeIf { it.value.owner === session }
                    router.broadcastExpired(store.expirePending(disconnected = deviceId), outlet(null))
                    presence()
                }
            }
            log("disconnected devices=${sessions.size}")
        }
    }

    fun tick() {
        synchronized(order) {
            router.broadcastExpired(store.expirePending(), outlet(null))
        }
    }

    private val router = HouseholdRouter({ store }, ::log)

    /** 返事は session へ（無ければ返さない）、配信は接続中の全端末へ、外された端末は閉じる。 */
    private fun outlet(session: DefaultWebSocketServerSession?) =
        object : HouseholdRouter.Outlet {
            override fun reply(message: RelayV2) {
                session?.let { send(it, message) }
            }

            override fun broadcast(
                message: RelayV2,
                except: String?,
            ) = this@HubV2.broadcast(message, except)

            override fun removed(deviceId: String) {
                sessions.remove(deviceId)?.let { removedSession ->
                    librarySubscribers.remove(removedSession)
                    closeLater(removedSession)
                }
            }

            override fun subscribeLibrary() {
                session?.let { librarySubscribers += it }
            }

            override fun broadcastLibrary(
                message: RelayV2,
                except: String?,
            ) {
                sessions.forEach { (id, target) -> if (id != except && target in librarySubscribers) send(target, message) }
            }
        }

    private fun handle(
        session: DefaultWebSocketServerSession,
        deviceId: String,
        message: RelayV2?,
    ) {
        fun reply(message: RelayV2) = send(session, message)
        when (message) {
            is RelayV2.PairingOpen -> {
                if (message.tokenHash.length != 43 || !store.isMember(deviceId)) return run { reply(RelayV2.Error("bad_token")) }
                pairings.entries.removeIf { it.value.ownerId == deviceId || it.value.expiresAtMs < config.nowMs() }
                if (pairings.putIfAbsent(message.tokenHash, PairingSlot(deviceId, session, config.nowMs() + PAIRING_LIFETIME_MS)) != null) {
                    return run { reply(RelayV2.Error("bad_token")) }
                }
                log("pairing opened")
            }
            is RelayV2.PairConfirm -> {
                val slot = pairings[message.tokenHash]
                if (slot == null || slot.ownerId != deviceId || !slot.helloDelivered.get()) return run { reply(RelayV2.Error("no_pairing")) }
                slot.confirm.complete(message.confirm)
                pairings.remove(message.tokenHash)
            }
            // 家の状態に当てるものは、テストの中継と同じ振り分け（HouseholdRouter）を通る
            else -> if (!router.route(deviceId, message, outlet(session))) reply(RelayV2.Error("unsupported"))
        }
    }

    /** 未加入の端末の入口。token が pairing_open されていれば、枠を開けた端末にだけ1回転送し、Confirm を1回だけ返す。 */
    suspend fun pair(session: DefaultWebSocketServerSession) {
        // 加入の待ち（最大2分）は、接続の認証の枠とは別に数える（待ちで認証の枠を埋めさせない）
        if (pairWaiting.incrementAndGet() > MAX_PAIR_WAITING) {
            pairWaiting.decrementAndGet()
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }
        try {
            pairGated(session)
        } finally {
            pairWaiting.decrementAndGet()
        }
    }

    /** 閉じるのは suspend なので、順番の外で行う。 */
    private fun closeLater(target: DefaultWebSocketServerSession) {
        target.launch { target.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "removed")) }
    }

    private suspend fun pairGated(session: DefaultWebSocketServerSession) {
        val text = withTimeoutOrNull(30.seconds) { runCatching { (session.incoming.receive() as? Frame.Text)?.readText() }.getOrNull() }
        val hello = text?.takeIf { it.length <= RelayV2.MAX_PAIR_FRAME_CHARS }?.let(RelayV2::decode) as? RelayV2.PairHelloIn
        val token = hello?.let { runCatching { unb64.decode(it.token) }.getOrNull() }
        val tokenHash = token?.let { b64.encodeToString(WaveCrypto.sha256(it)) }
        val slot = tokenHash?.let { pairings[it] }?.takeIf { it.expiresAtMs >= config.nowMs() }
        val owner = slot?.owner?.takeIf { sessions[slot.ownerId] === it }
        if (hello == null || slot == null || owner == null || !slot.helloDelivered.compareAndSet(false, true)) {
            log("pair rejected")
            session.send(Frame.Text(RelayV2.encode(RelayV2.Error("no_pairing"))))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pair"))
            return
        }
        if (!send(owner, RelayV2.PairHello(tokenHash, hello.hello))) {
            session.send(Frame.Text(RelayV2.encode(RelayV2.Error("no_pairing"))))
            session.close(CloseReason(CloseReason.Codes.NORMAL, "offline"))
            return
        }
        log("pair hello forwarded")
        val confirm = withTimeoutOrNull(PAIRING_LIFETIME_MS) { slot.confirm.await() }
        session.send(Frame.Text(RelayV2.encode(if (confirm == null) RelayV2.Error("timeout") else RelayV2.PairConfirmed(confirm))))
        session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
    }

    private companion object {
        const val PAIRING_LIFETIME_MS = 2 * 60 * 1000L
        const val MAX_UNAUTHENTICATED = 16
        const val MAX_PAIR_WAITING = 4
    }
}
