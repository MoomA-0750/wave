package xyz.mooma0750.wave.handoff.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private var now = 0L
    private val idA = WaveCrypto.generateEphemeralKeyPair()
    private val idB = WaveCrypto.generateEphemeralKeyPair()

    private fun responder(account: String = "account-1") = Pairing.Responder(idA.private, idA.public, "Pixel 3", account, nowMs = { now })

    private fun initiator(
        offer: Pairing.Offer,
        account: String = "account-1",
        identity: java.security.KeyPair = idB,
    ) = Pairing.Initiator(offer, identity.private, identity.public, "Fold 7", account)

    private fun reason(result: Result<*>) = (result.exceptionOrNull() as Pairing.PairingFailure).reason

    @Test
    fun honestPairingAgreesOnTheCodeAndDirectionalKeys() {
        val a = responder()
        val offer = assertNotNull(Pairing.decodeOffer(Pairing.encodeOffer(a.offer)))
        val b = initiator(offer)
        val confirm = a.onHello(b.hello()).getOrThrow()
        val (code, pairOnB) = b.onConfirm(confirm).getOrThrow()
        assertTrue(Regex("^[0-9]{6}$").matches(code))
        val pairOnA = a.onCodeEntered(code).getOrThrow()
        assertContentEquals(pairOnA.sendKey, pairOnB.receiveKey)
        assertContentEquals(pairOnA.receiveKey, pairOnB.sendKey)
        assertContentEquals(pairOnA.accountKey, pairOnB.accountKey)
        assertContentEquals(idB.public.encoded, pairOnA.peerPublicKey)
        assertContentEquals(idA.public.encoded, pairOnB.peerPublicKey)
        assertEquals("Fold 7", pairOnA.peerDeviceName)
        assertEquals("Pixel 3", pairOnB.peerDeviceName)
    }

    @Test
    fun aJoinerWithoutLoginIsAcceptedOnlyWhenTheResponderAllowsIt() {
        // wave-api（ADR-0015）はログインを持たずに加わる。A は印で分かり、加えた後にログインを渡す
        val a = responder()
        val api = Pairing.Initiator(a.offer, idB.private, idB.public, "wave API", accountId = null)
        val (code, _) = api.onConfirm(a.onHello(api.hello()).getOrThrow()).getOrThrow()
        assertTrue(a.onCodeEntered(code).getOrThrow().peerAccountless)
        val strict = Pairing.Responder(idA.private, idA.public, "Pixel 3", "account-1", nowMs = { now }, acceptAccountless = false)
        assertEquals(Pairing.Failure.AccountMismatch, reason(strict.onHello(Pairing.Initiator(strict.offer, idB.private, idB.public, "wave API", accountId = null).hello())))
        // ふつうの端末は印にならない
        val other = responder()
        val normal = initiator(other.offer)
        val (normalCode, _) = normal.onConfirm(other.onHello(normal.hello()).getOrThrow()).getOrThrow()
        kotlin.test.assertFalse(other.onCodeEntered(normalCode).getOrThrow().peerAccountless)
    }

    @Test
    fun thirdPartyWhoPhotographedTheQrIsRejectedByTheCodeB() {
        val a = responder()
        val attacker = WaveCrypto.generateEphemeralKeyPair()
        // 第三者が QR を撮り、B より先に自分の鍵で Hello を送る（アカウントの HMAC は、識別子を知っていれば作れると仮定する）
        val evil = initiator(a.offer, identity = attacker)
        a.onHello(evil.hello()).getOrThrow()
        // 正規の B は token 消費済みで進めない
        assertEquals(Pairing.Failure.AlreadyUsed, reason(a.onHello(initiator(a.offer).hello())))
        // 本人が B に出ない数字を入れる（B の画面には数字が出ていない）。3回で打ち切る
        repeat(2) { assertEquals(Pairing.Failure.WrongCode, reason(a.onCodeEntered("000000"))) }
        assertEquals(Pairing.Failure.TooManyAttempts, reason(a.onCodeEntered("000000")))
    }

    @Test
    fun relayCannotSwapTheInitiatorsEphemeralKey() {
        val a = responder()
        val b = initiator(a.offer)
        val hello = b.hello()
        val swapped = hello.copy(eB = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(WaveCrypto.generateEphemeralKeyPair().public.encoded))
        assertEquals(Pairing.Failure.Malformed, reason(a.onHello(swapped)), "差し替えた鍵で封が開いた")
    }

    @Test
    fun confirmFromAnotherResponderFailsSignatureOrDecryption() {
        val a = responder()
        val b = initiator(a.offer)
        a.onHello(b.hello()).getOrThrow()
        val other = responder()
        val confirmFromOther = other.onHello(initiator(other.offer).hello()).getOrThrow()
        assertIs<Pairing.PairingFailure>(b.onConfirm(confirmFromOther).exceptionOrNull())
    }

    @Test
    fun differentAccountsAreRejected() {
        val a = responder(account = "account-1")
        val b = initiator(a.offer, account = "account-2")
        assertEquals(Pairing.Failure.AccountMismatch, reason(a.onHello(b.hello())))
    }

    @Test
    fun offersExpireAfterTwoMinutes() {
        val a = responder()
        val b = initiator(a.offer)
        now += Pairing.LIFETIME_MS + 1
        assertEquals(Pairing.Failure.Expired, reason(a.onHello(b.hello())))
    }

    @Test
    fun malformedOffersAreRejected() {
        assertNull(Pairing.decodeOffer("https://example.com"))
        assertNull(Pairing.decodeOffer("wave-pair:!!!"))
        val a = responder()
        val tampered = a.offer.copy(token = "AAAA")
        assertNull(Pairing.decodeOffer(Pairing.encodeOffer(tampered)))
        assertNull(Pairing.decodeOffer(Pairing.encodeOffer(a.offer.copy(v = 2))))
        assertNotEquals(a.offer.token, responder().offer.token)
    }

    @Test
    fun helloSignedWithAnotherKeyIsRejected() {
        val a = responder()
        // 封は正しい（使い捨ての鍵で導いた鍵）が、pkB に対応しない鍵で署名した Hello
        val forged = Pairing.Initiator(a.offer, WaveCrypto.generateEphemeralKeyPair().private, idB.public, "Fold 7", "account-1")
        assertEquals(Pairing.Failure.BadSignature, reason(a.onHello(forged.hello())))
    }

    @Test
    fun confirmSignedWithAnotherKeyIsRejected() {
        val a = Pairing.Responder(WaveCrypto.generateEphemeralKeyPair().private, idA.public, "Pixel 3", "account-1", nowMs = { now })
        val b = initiator(a.offer)
        assertEquals(Pairing.Failure.BadSignature, reason(b.onConfirm(a.onHello(b.hello()).getOrThrow())))
    }

    @Test
    fun initiatorChecksTheResponderAccountToo() {
        val a = Pairing.Responder(idA.private, idA.public, "Pixel 3", "account-1", nowMs = { now }, confirmAccountId = "account-2")
        val b = initiator(a.offer)
        assertEquals(Pairing.Failure.AccountMismatch, reason(b.onConfirm(a.onHello(b.hello()).getOrThrow())))
    }

    @Test
    fun codeEntryExpiresAndSelfPairingIsRejected() {
        val a = responder()
        val b = initiator(a.offer)
        a.onHello(b.hello()).getOrThrow()
        now += Pairing.LIFETIME_MS + 1
        assertEquals(Pairing.Failure.Expired, reason(a.onCodeEntered("000000")))
        val self = responder()
        assertEquals(Pairing.Failure.Malformed, reason(self.onHello(initiator(self.offer, identity = idA).hello())))
    }

    @Test
    fun connectV2ExtensionsTravelInsideTheSignedHandshake() {
        val a = Pairing.Responder(idA.private, idA.public, "Pixel 3", "account-1", nowMs = { now }, confirmExtension = """{"householdId":"h","headSeq":1}""")
        val b = Pairing.Initiator(a.offer, idB.private, idB.public, "Fold 7", "account-1", helloExtension = """{"deviceId":"d","role":"player"}""")
        val (code, pairOnB) = b.onConfirm(a.onHello(b.hello()).getOrThrow()).getOrThrow()
        val pairOnA = a.onCodeEntered(code).getOrThrow()
        assertEquals("""{"deviceId":"d","role":"player"}""", pairOnA.peerExtension)
        assertEquals("""{"householdId":"h","headSeq":1}""", pairOnB.peerExtension)
        // v1（拡張なし）はそのまま
        val v1 = responder()
        val v1b = initiator(v1.offer)
        val (v1code, v1OnB) = v1b.onConfirm(v1.onHello(v1b.hello()).getOrThrow()).getOrThrow()
        assertNull(v1OnB.peerExtension)
        assertNull(v1.onCodeEntered(v1code).getOrThrow().peerExtension)
        // 長すぎる拡張は受けない
        val big = Pairing.Initiator(a.offer, idB.private, idB.public, "Fold 7", "account-1", helloExtension = "x".repeat(4096))
        val fresh = Pairing.Responder(idA.private, idA.public, "Pixel 3", "account-1", nowMs = { now })
        val bigOnFresh = Pairing.Initiator(fresh.offer, idB.private, idB.public, "Fold 7", "account-1", helloExtension = "x".repeat(4096))
        assertEquals(Pairing.Failure.Malformed, reason(fresh.onHello(bigOnFresh.hello())))
        assertNotNull(big)
    }

    @Test
    fun deviceNamesAreSanitized() {
        assertEquals("abc", Pairing.sanitizeName("a\u202Eb\u0000c"))
        assertEquals("端末", Pairing.sanitizeName("\u0007"))
    }
}
