package xyz.mooma0750.wave.relay.v2

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ChainRecord
import xyz.mooma0750.wave.handoff.v2.ChainVerification
import xyz.mooma0750.wave.handoff.v2.DocKind
import xyz.mooma0750.wave.handoff.v2.DocSealer
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.StateKind
import xyz.mooma0750.wave.handoff.v2.VerifiedChain
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayV2ServerTest {
    private val host = "relay.test:10443"
    private val a = RelayTestDevice()
    private val b = RelayTestDevice()

    private fun store() = HouseholdStore(File(Files.createTempDirectory("relay-v2").toFile(), "household.json"), listOf(a.publicKeyB64))

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): RelayV2 = withTimeout(5_000) { RelayV2.decode((incoming.receive() as Frame.Text).readText())!! }

    /** 目的の種類が来るまで読み飛ばす（presence・state など、順序が決まらないもの）。 */
    private suspend inline fun <reified T : RelayV2> DefaultClientWebSocketSession.expect(): T {
        while (true) {
            val message = receiveMessage()
            if (message is T) return message
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: RelayV2) = send(Frame.Text(RelayV2.encode(message)))

    private suspend fun DefaultClientWebSocketSession.authenticate(device: RelayTestDevice): RelayV2.Welcome {
        val challenge = assertIs<RelayV2.Challenge>(receiveMessage())
        val nonce = Base64.getUrlDecoder().decode(challenge.nonce)
        val signature = device.sign(RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce))
        sendMessage(RelayV2.Auth(device.publicKeyB64, Base64.getUrlEncoder().withoutPadding().encodeToString(signature)))
        return expect()
    }

    private fun verified(records: List<ChainRecord>): VerifiedChain = (MemberChain.verify(records) as ChainVerification.Ok).chain

    private fun doc(
        device: RelayTestDevice,
        chain: VerifiedChain,
        kind: DocKind,
        version: Long,
    ) = DocSealer.seal(device, chain, kind, version, null, 0, "body".encodeToByteArray())

    @Test
    fun householdClaimWriteAndTransferOverWebSockets() =
        testApplication {
            application { relayV2Module(RelayV2Config(host, store(), tickMs = 50)) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/v2/connect") {
                val welcome = authenticate(a)
                assertNull(welcome.householdId)
                val genesis = MemberChain.genesis(a, a.keys)
                sendMessage(RelayV2.HouseholdCreate(genesis))
                expect<RelayV2.ChainOk>()
                val addB = MemberChain.add(a, verified(listOf(genesis)), b.keys)
                sendMessage(RelayV2.ChainAppend(addB))
                assertEquals(1L, expect<RelayV2.ChainOk>().headSeq)
                val chain = verified(listOf(genesis, addB))
                val sessionA = this
                client.webSocket("/v2/connect") {
                    val welcomeB = authenticate(b)
                    assertEquals(chain.householdId, welcomeB.householdId)
                    // A が再生を始める → B に yield と再生中の端末の変更が届く
                    sessionA.sendMessage(RelayV2.Claim("0f8fad5b-d9cb-469f-a165-70867728950e", doc(a, chain, DocKind.Command, 1)))
                    assertEquals(a.deviceId, expect<RelayV2.ActiveChanged>().activeDeviceId)
                    assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", expect<RelayV2.YieldDelivery>().claimId)
                    // A がカーソルを書く → B に配られる
                    sessionA.sendMessage(RelayV2.StatePut("r1", StateKind.Cursor, 0, doc(a, chain, DocKind.Cursor, 1)))
                    assertEquals(1L, sessionA.expect<RelayV2.StateOk>().version)
                    assertEquals(1L, expect<RelayV2.State>().state.version)
                    // B が引き取る → A に yield、A の最後のカーソル、B の最初のカーソルで確定
                    sendMessage(RelayV2.Transfer("1f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(b, chain, DocKind.Command, 1)))
                    assertEquals(b.deviceId, expect<RelayV2.ActiveChanged>().pendingOwner)
                    assertEquals("1f8fad5b-d9cb-469f-a165-70867728950e", sessionA.expect<RelayV2.YieldDelivery>().claimId)
                    sessionA.sendMessage(RelayV2.Yielded("1f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(a, chain, DocKind.Cursor, 2)))
                    assertEquals(2L, expect<RelayV2.State>().state.version)
                    sendMessage(RelayV2.StatePut("r2", StateKind.Cursor, 2, doc(b, chain, DocKind.Cursor, 3)))
                    assertEquals(3L, expect<RelayV2.StateOk>().version)
                    assertEquals(b.deviceId, sessionA.expect<RelayV2.ActiveChanged>().activeDeviceId)
                    // A は再生中でなくなったので書けない
                    sessionA.sendMessage(RelayV2.StatePut("r3", StateKind.Cursor, 3, doc(a, chain, DocKind.Cursor, 4)))
                    assertEquals("not_active", sessionA.expect<RelayV2.Error>().code)
                    // A が B を外す → B は切られ、次に来ても認証できない
                    sessionA.sendMessage(RelayV2.ChainAppend(MemberChain.remove(a, chain, b.deviceId)))
                    assertEquals(2L, sessionA.expect<RelayV2.ChainOk>().headSeq)
                    assertTrue(runCatching { while (true) receiveMessage() }.isFailure)
                }
                client.webSocket("/v2/connect") {
                    val challenge = assertIs<RelayV2.Challenge>(receiveMessage())
                    val nonce = Base64.getUrlDecoder().decode(challenge.nonce)
                    sendMessage(RelayV2.Auth(b.publicKeyB64, Base64.getUrlEncoder().withoutPadding().encodeToString(b.sign(RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce)))))
                    assertTrue(runCatching { receiveMessage() }.isFailure, "外した端末が認証できた")
                }
            }
        }

    @Test
    fun transferOwnerDisconnectingExpiresThePendingTransfer() =
        testApplication {
            application { relayV2Module(RelayV2Config(host, store(), tickMs = 50)) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/v2/connect") {
                authenticate(a)
                val genesis = MemberChain.genesis(a, a.keys)
                sendMessage(RelayV2.HouseholdCreate(genesis))
                expect<RelayV2.ChainOk>()
                val addB = MemberChain.add(a, verified(listOf(genesis)), b.keys)
                sendMessage(RelayV2.ChainAppend(addB))
                expect<RelayV2.ChainOk>()
                val chain = verified(listOf(genesis, addB))
                sendMessage(RelayV2.Claim("0f8fad5b-d9cb-469f-a165-70867728950e", doc(a, chain, DocKind.Command, 1)))
                expect<RelayV2.ActiveChanged>()
                sendMessage(RelayV2.StatePut("r1", StateKind.Cursor, 0, doc(a, chain, DocKind.Cursor, 1)))
                expect<RelayV2.StateOk>()
                client.webSocket("/v2/connect") {
                    authenticate(b)
                    sendMessage(RelayV2.Transfer("1f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(b, chain, DocKind.Command, 1)))
                    expect<RelayV2.ActiveChanged>()
                }
                // B が引き取る前に切れた → A に知らせ、再生中は A のまま
                assertEquals("1f8fad5b-d9cb-469f-a165-70867728950e", expect<RelayV2.PendingExpired>().claimId)
                assertEquals(a.deviceId, expect<RelayV2.ActiveChanged>().activeDeviceId)
            }
        }

    @Test
    fun unknownDevicesCannotConnectBeforeOrAfterTheHouseholdExists() =
        testApplication {
            application { relayV2Module(RelayV2Config(host, store(), tickMs = 50)) }
            val client = createClient { install(WebSockets) }
            client.webSocket("/v2/connect") {
                val challenge = assertIs<RelayV2.Challenge>(receiveMessage())
                val nonce = Base64.getUrlDecoder().decode(challenge.nonce)
                sendMessage(RelayV2.Auth(b.publicKeyB64, Base64.getUrlEncoder().withoutPadding().encodeToString(b.sign(RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(host.encodeToByteArray(), nonce)))))
                assertTrue(runCatching { receiveMessage() }.isFailure, "家が無いのに起動時の設定の鍵でない端末を受けた")
            }
            client.webSocket("/v2/connect") {
                receiveMessage()
                // 別の端末の公開鍵で、自分の鍵の署名（なりすまし）
                sendMessage(RelayV2.Auth(a.publicKeyB64, Base64.getUrlEncoder().withoutPadding().encodeToString(b.sign(RelayV2.AUTH_LABEL, "x".encodeToByteArray()))))
                assertTrue(runCatching { receiveMessage() }.isFailure)
            }
        }
}
