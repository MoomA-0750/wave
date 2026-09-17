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
        maxFrameSize = RelayMessage.MAX_FRAME_CHARS.toLong() * 4
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
        val expiresAtMs: Long,
        val confirm: CompletableDeferred<kotlinx.serialization.json.JsonElement> = CompletableDeferred(),
        @Volatile var helloDelivered: Boolean = false,
    )

    /** tokenHash → A。端末ごとに1件。メモリだけ。 */
    private val pairings = ConcurrentHashMap<String, PairingSlot>()

    private fun log(message: String) = println("relay $message")

    suspend fun connect(session: DefaultWebSocketServerSession) {
        val nonce = WaveCrypto.randomBytes(32)
        session.send(Frame.Text(RelayMessage.encode(RelayMessage.Challenge(config.host, b64.encodeToString(nonce)))))
        val auth =
            withTimeoutOrNull(30.seconds) { (session.incoming.receive() as? Frame.Text)?.readText() }
                ?.takeIf { it.length <= 4096 }
                ?.let(RelayMessage::decode) as? RelayMessage.Auth
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
            pairings.entries.removeIf { it.value.ownerId == deviceId }
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
                // 送り手は接続の鍵と一致しなければならない（なりすましの封筒を捨てる）
                if (envelope.from != deviceId) return reply(RelayMessage.Error("from_mismatch"))
                val target = sessions[envelope.to] ?: return reply(RelayMessage.Error("offline"))
                target.send(Frame.Text(RelayMessage.encode(RelayMessage.Delivered(envelope))))
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
                pairings[message.tokenHash] = PairingSlot(deviceId, config.nowMs() + PAIRING_LIFETIME_MS)
                log("pairing opened")
            }
            is RelayMessage.PairConfirm -> {
                val slot = pairings[message.tokenHash]
                if (slot == null || slot.ownerId != deviceId || !slot.helloDelivered) return reply(RelayMessage.Error("no_pairing"))
                slot.confirm.complete(message.confirm)
                pairings.remove(message.tokenHash)
            }
            else -> reply(RelayMessage.Error("unsupported"))
        }
    }

    /** 未登録の B の入口。token が pairing_open されていれば A にだけ1回転送し、A の Confirm を1回だけ返す。 */
    suspend fun pair(session: DefaultWebSocketServerSession) {
        val text = withTimeoutOrNull(30.seconds) { (session.incoming.receive() as? Frame.Text)?.readText() }
        val hello = text?.takeIf { it.length <= RelayMessage.MAX_PAIR_FRAME_CHARS }?.let(RelayMessage::decode) as? RelayMessage.PairHelloIn
        val token = hello?.let { runCatching { unb64.decode(it.token) }.getOrNull() }
        val tokenHash = token?.let { b64.encodeToString(WaveCrypto.sha256(it)) }
        val slot = tokenHash?.let { pairings[it] }?.takeIf { it.expiresAtMs >= config.nowMs() && !it.helloDelivered }
        val owner = slot?.let { sessions[it.ownerId] }
        if (hello == null || slot == null || owner == null) {
            log("pair rejected")
            session.send(Frame.Text(RelayMessage.encode(RelayMessage.Error("no_pairing"))))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pair"))
            return
        }
        slot.helloDelivered = true
        owner.send(Frame.Text(RelayMessage.encode(RelayMessage.PairHello(tokenHash, hello.hello))))
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
    }
}
