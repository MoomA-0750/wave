package xyz.mooma0750.wave.handoff.crypto

import xyz.mooma0750.wave.handoff.EnvelopeType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class EnvelopeSealerTest {
    private val idA = WaveCrypto.generateEphemeralKeyPair()
    private val idB = WaveCrypto.generateEphemeralKeyPair()

    private fun pair(): Pair<Pairing.Established, Pairing.Established> {
        val a = Pairing.Responder(idA.private, idA.public, "A", "acct", nowMs = { 0 })
        val b = Pairing.Initiator(a.offer, idB.private, idB.public, "B", "acct")
        val (code, onB) = b.onConfirm(a.onHello(b.hello()).getOrThrow()).getOrThrow()
        return a.onCodeEntered(code).getOrThrow() to onB
    }

    private val a = EnvelopeSealer.deviceId(idA.public.encoded)
    private val b = EnvelopeSealer.deviceId(idB.public.encoded)

    @Test
    fun opensOnlyForTheIntendedDirectionAndOrder() {
        val (onA, onB) = pair()
        val envelope = EnvelopeSealer.seal(onB.sendKey, b, a, EnvelopeType.Request, seq = 5, handoffId = "h1", plaintext = "hi".encodeToByteArray())
        assertContentEquals("hi".encodeToByteArray(), EnvelopeSealer.open(envelope, onA.receiveKey, self = a, expectedFrom = b, lastReceivedSeq = 4))
        // 再送（seq が最後の値以下）
        assertNull(EnvelopeSealer.open(envelope, onA.receiveKey, self = a, expectedFrom = b, lastReceivedSeq = 5))
        // 反射: B→A の封筒を B に送り返しても、B の受ける鍵では開かない
        assertNull(EnvelopeSealer.open(envelope.copy(from = a, to = b), onB.receiveKey, self = b, expectedFrom = a, lastReceivedSeq = -1))
        // 平文の部分（type・seq・handoffId）の書き換え
        assertNull(EnvelopeSealer.open(envelope.copy(type = EnvelopeType.Cancel), onA.receiveKey, a, b, 4))
        assertNull(EnvelopeSealer.open(envelope.copy(seq = 6), onA.receiveKey, a, b, 4))
        assertNull(EnvelopeSealer.open(envelope.copy(handoffId = "h2"), onA.receiveKey, a, b, 4))
        // 宛先・送り手が違う
        assertNull(EnvelopeSealer.open(envelope, onA.receiveKey, self = "someone", expectedFrom = b, lastReceivedSeq = 4))
        assertNull(EnvelopeSealer.open(envelope, onA.receiveKey, self = a, expectedFrom = "someone", lastReceivedSeq = 4))
    }
}
