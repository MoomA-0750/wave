package xyz.mooma0750.wave.relay

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
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class RelayConfig(
    /** 署名の対象に入れるホスト名。tailscale serve は TLS を終端するので、Host ヘッダではなく設定から取る。 */
    val host: String,
    val registry: Registry,
    val nowMs: () -> Long = System::currentTimeMillis,
)

/**
 * 中継（ADR-0008）。/connect は登録済みの端末だけ、/pair は pairing_open された token の B だけ（1通の Hello と1通の Confirm）。
 * ログは件数・種類・大きさだけ（端末 ID・本文は出さない）。
 */
fun Application.relayModule(config: RelayConfig) {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 90.seconds
        // 1フレームの上限（ASCII の JSON なので文字数とバイト数はほぼ同じ）。認証前・/pair の入口は同時に開ける数を別に絞る
        maxFrameSize = RelayMessage.MAX_FRAME_CHARS.toLong() + 1024
    }
    val hub = Hub(config)
    routing {
        get("/health") { call.respondText("ok") }
        webSocket("/connect") { hub.connect(this) }
        webSocket("/pair") { hub.pair(this) }
    }
}

private class Hub(
    private val config: RelayConfig,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    private class PairingSlot(
        val ownerId: String,
        /** 枠を開けた接続そのもの（置き換えられた古い接続の後始末で、新しい接続の枠を消さない）。 */
        val owner: DefaultWebSocketServerSession,
        val expiresAtMs: Long,
        val confirm: CompletableDeferred<kotlinx.serialization.json.JsonElement> = CompletableDeferred(),
        val helloDelivered: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    )

    /** 認証前の /connect と /pair の同時の数（tailnet 内からの大きなフレームの抱え込みを限る）。 */
    private val unauthenticated = java.util.concurrent.atomic.AtomicInteger(0)

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

    /** 宛先へ送る。宛先が閉じている・詰まっていても、送り手の接続を落とさない。 */
    private fun deliver(
        target: DefaultWebSocketServerSession,
        message: RelayMessage,
    ): Boolean = runCatching { target.outgoing.trySend(Frame.Text(RelayMessage.encode(message))).isSuccess }.getOrDefault(false)

    /** tokenHash → A。端末ごとに1件。メモリだけ。 */
    private val pairings = ConcurrentHashMap<String, PairingSlot>()

    private fun log(message: String) = println("relay $message")

    suspend fun connect(session: DefaultWebSocketServerSession) {
        val nonce = WaveCrypto.randomBytes(32)
        session.send(Frame.Text(RelayMessage.encode(RelayMessage.Challenge(config.host, b64.encodeToString(nonce)))))
        val auth =
            gate(session) {
                withTimeoutOrNull(30.seconds) { runCatching { (session.incoming.receive() as? Frame.Text)?.readText() }.getOrNull() }
                    ?.takeIf { it.length <= 4096 }
                    ?.let(RelayMessage::decode) as? RelayMessage.Auth
            }
        val deviceId = auth?.let { Registry.idOf(it.publicKey) }
        val key = deviceId?.let(config.registry::publicKey)
        val signature = auth?.let { runCatching { unb64.decode(it.signature) }.getOrNull() }
        if (key == null || signature == null ||
            !WaveCrypto.verify(key, RelayMessage.AUTH_LABEL, WaveCrypto.lengthPrefixed(config.host.encodeToByteArray(), nonce), signature)
        ) {
            log("auth rejected")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))
            return
        }
        // 同じ端末の新しい接続が勝つ
        sessions.put(deviceId, session)?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
        log("connected devices=${sessions.size}")
        session.send(Frame.Text(RelayMessage.encode(RelayMessage.Welcome(deviceId))))
        try {
            for (frame in session.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                if (text.length > RelayMessage.MAX_FRAME_CHARS) {
                    session.send(Frame.Text(RelayMessage.encode(RelayMessage.Error("too_large"))))
                    continue
                }
                handle(session, deviceId, nonce, RelayMessage.decode(text))
            }
        } finally {
            sessions.remove(deviceId, session)
            pairings.entries.removeIf { it.value.owner === session }
            log("disconnected devices=${sessions.size}")
        }
    }

    private suspend fun handle(
        session: DefaultWebSocketServerSession,
        deviceId: String,
        sessionNonce: ByteArray,
        message: RelayMessage?,
    ) {
        suspend fun reply(message: RelayMessage) = session.send(Frame.Text(RelayMessage.encode(message)))
        when (message) {
            is RelayMessage.Send -> {
                val envelope = message.envelope
                // 締め出された端末は、接続が閉じきる前でも送らせない
                if (!config.registry.contains(deviceId)) return session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                // 送り手は接続の鍵と一致しなければならない（なりすましの封筒を捨てる）
                if (envelope.from != deviceId) return reply(RelayMessage.Error("from_mismatch"))
                val target = sessions[envelope.to]
                if (target == null || !deliver(target, RelayMessage.Delivered(envelope))) return reply(RelayMessage.Error("offline"))
                log("delivered type=${envelope.type} chars=${envelope.ciphertext.length}")
            }
            is RelayMessage.Online -> reply(RelayMessage.OnlineResult(message.ids.take(32).filter { sessions.containsKey(it) }))
            is RelayMessage.Register -> {
                val signature = runCatching { unb64.decode(message.signature) }.getOrNull()
                val registrant = config.registry.publicKey(deviceId)
                val ok =
                    registrant != null && signature != null &&
                        WaveCrypto.verify(
                            registrant,
                            RelayMessage.REGISTER_LABEL,
                            WaveCrypto.lengthPrefixed(config.host.encodeToByteArray(), sessionNonce, message.publicKey.encodeToByteArray()),
                            signature,
                        ) && config.registry.register(message.publicKey)
                log("register ok=$ok devices=${config.registry.size()}")
                reply(if (ok) RelayMessage.OnlineResult(emptyList()) else RelayMessage.Error("register_failed"))
            }
            is RelayMessage.Revoke -> {
                val signature = runCatching { unb64.decode(message.signature) }.getOrNull()
                val registrant = config.registry.publicKey(deviceId)
                val ok =
                    registrant != null && signature != null &&
                        WaveCrypto.verify(
                            registrant,
                            RelayMessage.REVOKE_LABEL,
                            WaveCrypto.lengthPrefixed(config.host.encodeToByteArray(), sessionNonce, message.deviceId.encodeToByteArray()),
                            signature,
                        ) && config.registry.revoke(message.deviceId)
                if (ok) sessions.remove(message.deviceId)?.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                log("revoke ok=$ok devices=${config.registry.size()}")
                reply(if (ok) RelayMessage.OnlineResult(emptyList()) else RelayMessage.Error("revoke_failed"))
            }
            is RelayMessage.PairingOpen -> {
                if (message.tokenHash.length != 43) return reply(RelayMessage.Error("bad_token"))
                pairings.entries.removeIf { it.value.ownerId == deviceId || it.value.expiresAtMs < config.nowMs() }
                // 他の端末の枠を上書きさせない
                if (pairings.putIfAbsent(message.tokenHash, PairingSlot(deviceId, session, config.nowMs() + PAIRING_LIFETIME_MS)) != null) {
                    return reply(RelayMessage.Error("bad_token"))
                }
                log("pairing opened")
            }
            is RelayMessage.PairConfirm -> {
                val slot = pairings[message.tokenHash]
                if (slot == null || slot.ownerId != deviceId || !slot.helloDelivered.get()) return reply(RelayMessage.Error("no_pairing"))
                slot.confirm.complete(message.confirm)
                pairings.remove(message.tokenHash)
            }
            else -> reply(RelayMessage.Error("unsupported"))
        }
    }

    /** 未登録の B の入口。token が pairing_open されていれば A にだけ1回転送し、A の Confirm を1回だけ返す。 */
    suspend fun pair(session: DefaultWebSocketServerSession) {
        gate(session) { pairGated(session) }
    }

    private suspend fun pairGated(session: DefaultWebSocketServerSession) {
        val text = withTimeoutOrNull(30.seconds) { runCatching { (session.incoming.receive() as? Frame.Text)?.readText() }.getOrNull() }
        val hello = text?.takeIf { it.length <= RelayMessage.MAX_PAIR_FRAME_CHARS }?.let(RelayMessage::decode) as? RelayMessage.PairHelloIn
        val token = hello?.let { runCatching { unb64.decode(it.token) }.getOrNull() }
        val tokenHash = token?.let { b64.encodeToString(WaveCrypto.sha256(it)) }
        val slot = tokenHash?.let { pairings[it] }?.takeIf { it.expiresAtMs >= config.nowMs() }
        // 枠の持ち主がいまも同じ接続でオンラインのときだけ。Hello は1回だけ（同時の2本でも1本だけ通す）
        val owner = slot?.owner?.takeIf { sessions[slot.ownerId] === it }
        if (hello == null || slot == null || owner == null || !slot.helloDelivered.compareAndSet(false, true)) {
            log("pair rejected")
            session.send(Frame.Text(RelayMessage.encode(RelayMessage.Error("no_pairing"))))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pair"))
            return
        }
        if (!deliver(owner, RelayMessage.PairHello(tokenHash, hello.hello))) {
            session.send(Frame.Text(RelayMessage.encode(RelayMessage.Error("no_pairing"))))
            session.close(CloseReason(CloseReason.Codes.NORMAL, "offline"))
            return
        }
        log("pair hello forwarded")
        val confirm = withTimeoutOrNull(PAIRING_LIFETIME_MS) { slot.confirm.await() }
        if (confirm == null) {
            session.send(Frame.Text(RelayMessage.encode(RelayMessage.Error("timeout"))))
        } else {
            session.send(Frame.Text(RelayMessage.encode(RelayMessage.PairConfirmed(confirm))))
        }
        session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
    }

    private companion object {
        const val PAIRING_LIFETIME_MS = 2 * 60 * 1000L
        const val MAX_UNAUTHENTICATED = 16
    }
}
