package xyz.mooma0750.wave.relay

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import xyz.mooma0750.wave.handoff.Envelope
import xyz.mooma0750.wave.handoff.EnvelopeType
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.io.File
import java.security.KeyPair
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayServerTest {
    private val host = "relay.test:10443"
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val a = WaveCrypto.generateEphemeralKeyPair()
    private val b = WaveCrypto.generateEphemeralKeyPair()
    private val c = WaveCrypto.generateEphemeralKeyPair()

    private fun pk(key: KeyPair) = b64.encodeToString(key.public.encoded)

    private fun id(key: KeyPair) = EnvelopeSealer.deviceId(key.public.encoded)

    private fun ApplicationTestBuilder.setUp(file: File = File.createTempFile("registry", ".json").apply { delete() }) {
        application { relayModule(RelayConfig(host, Registry(file, listOf(pk(a))))) }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): RelayMessage = withTimeout(5_000) { RelayMessage.decode((incoming.receive() as Frame.Text).readText())!! }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: RelayMessage) = send(Frame.Text(RelayMessage.encode(message)))

    /** 認証して、接続の nonce を返す。 */
    private suspend fun DefaultClientWebSocketSession.authenticate(key: KeyPair): ByteArray {
        val challenge = assertIs<RelayMessage.Challenge>(receiveMessage())
        assertEquals(host, challenge.host)
        val nonce = Base64.getUrlDecoder().decode(challenge.nonce)
        sendMessage(RelayMessage.Auth(pk(key), b64.encodeToString(WaveCrypto.sign(key.private, RelayMessage.AUTH_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce)))))
        return nonce
    }

    private fun envelope(
        from: String,
        to: String,
    ) = Envelope(Envelope.VERSION, Envelope.SUITE_P256_AESGCM, from, to, EnvelopeType.Request, 1, null, "bm9uY2U", "Y2lwaGVy")

    @Test
    fun unregisteredOrBadlySignedDevicesAreRejected() =
        testApplication {
            setUp()
            val client = createClient { install(WebSockets) }
            client.webSocket("/connect") {
                authenticate(b) // 登録されていない
                assertTrue(runCatching { receiveMessage() }.isFailure, "登録されていない端末を受け入れた")
            }
            client.webSocket("/connect") {
                receiveMessage()
                // 別の nonce に対する署名
                sendMessage(RelayMessage.Auth(pk(a), b64.encodeToString(WaveCrypto.sign(a.private, RelayMessage.AUTH_LABEL, byteArrayOf(1)))))
                assertTrue(runCatching { receiveMessage() }.isFailure)
            }
        }

    @Test
    fun registeredDevicesExchangeEnvelopesButCannotSpoofTheSender() =
        testApplication {
            setUp()
            val client = createClient { install(WebSockets) }
            client.webSocket("/connect") {
                val nonce = authenticate(a)
                assertIs<RelayMessage.Welcome>(receiveMessage())
                // A が B を登録する
                val signature = WaveCrypto.sign(a.private, RelayMessage.REGISTER_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce, pk(b).encodeToByteArray()))
                sendMessage(RelayMessage.Register(pk(b), b64.encodeToString(signature)))
                assertIs<RelayMessage.OnlineResult>(receiveMessage())
                // B はまだオフライン
                sendMessage(RelayMessage.Send(envelope(id(a), id(b))))
                assertEquals(RelayMessage.Error("offline"), receiveMessage())
                val sessionA = this
                client.webSocket("/connect") {
                    authenticate(b)
                    assertIs<RelayMessage.Welcome>(receiveMessage())
                    sessionA.sendMessage(RelayMessage.Send(envelope(id(a), id(b))))
                    assertEquals(id(a), assertIs<RelayMessage.Delivered>(receiveMessage()).envelope.from)
                    // B が A になりすました封筒は捨てる
                    sendMessage(RelayMessage.Send(envelope(id(a), id(a))))
                    assertEquals(RelayMessage.Error("from_mismatch"), receiveMessage())
                    sendMessage(RelayMessage.Online(listOf(id(a), id(c))))
                    assertEquals(RelayMessage.OnlineResult(listOf(id(a))), receiveMessage())
                }
            }
        }

    @Test
    fun unregisteredDeviceCanOnlyReachTheOwnerOfAnOpenPairingOnce() =
        testApplication {
            setUp()
            val client = createClient { install(WebSockets) }
            val token = WaveCrypto.randomBytes(16)
            client.webSocket("/connect") {
                authenticate(a)
                receiveMessage()
                // pairing_open 前の /pair は拒否
                client.webSocket("/pair") {
                    sendMessage(RelayMessage.PairHelloIn(b64.encodeToString(token), JsonPrimitive("hello")))
                    assertEquals(RelayMessage.Error("no_pairing"), receiveMessage())
                }
                val tokenHash = b64.encodeToString(WaveCrypto.sha256(token))
                sendMessage(RelayMessage.PairingOpen(tokenHash))
                val ownerSession = this
                client.webSocket("/pair") {
                    sendMessage(RelayMessage.PairHelloIn(b64.encodeToString(token), JsonPrimitive("hello")))
                    val forwarded = assertIs<RelayMessage.PairHello>(ownerSession.receiveMessage())
                    assertEquals(JsonPrimitive("hello"), forwarded.hello)
                    ownerSession.sendMessage(RelayMessage.PairConfirm(tokenHash, JsonPrimitive("confirm")))
                    assertEquals(RelayMessage.PairConfirmed(JsonPrimitive("confirm")), receiveMessage())
                }
                // 同じ token の2通目は届けない
                client.webSocket("/pair") {
                    sendMessage(RelayMessage.PairHelloIn(b64.encodeToString(token), JsonPrimitive("again")))
                    assertEquals(RelayMessage.Error("no_pairing"), receiveMessage())
                }
            }
        }

    @Test
    fun bootstrapKeysCannotBeRevokedButOthersCanAndRegistrationPersists() =
        testApplication {
            val file = File.createTempFile("registry", ".json").apply { delete() }
            setUp(file)
            val client = createClient { install(WebSockets) }
            client.webSocket("/connect") {
                val nonce = authenticate(a)
                receiveMessage()
                sendMessage(RelayMessage.Register(pk(b), b64.encodeToString(WaveCrypto.sign(a.private, RelayMessage.REGISTER_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce, pk(b).encodeToByteArray())))))
                receiveMessage()
                assertTrue(Registry(file, emptyList()).contains(id(b)), "登録を保存していない")
                sendMessage(RelayMessage.Revoke(id(a), b64.encodeToString(WaveCrypto.sign(a.private, RelayMessage.REVOKE_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce, id(a).encodeToByteArray())))))
                assertEquals(RelayMessage.Error("revoke_failed"), receiveMessage())
                // 別の接続の nonce に対する署名は使えない
                sendMessage(RelayMessage.Revoke(id(b), b64.encodeToString(WaveCrypto.sign(a.private, RelayMessage.REVOKE_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), ByteArray(32), id(b).encodeToByteArray())))))
                assertEquals(RelayMessage.Error("revoke_failed"), receiveMessage())
                sendMessage(RelayMessage.Revoke(id(b), b64.encodeToString(WaveCrypto.sign(a.private, RelayMessage.REVOKE_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce, id(b).encodeToByteArray())))))
                assertIs<RelayMessage.OnlineResult>(receiveMessage())
                assertTrue(!Registry(file, emptyList()).contains(id(b)))
            }
        }
}
