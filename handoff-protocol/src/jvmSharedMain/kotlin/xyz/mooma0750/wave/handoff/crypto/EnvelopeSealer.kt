package xyz.mooma0750.wave.handoff.crypto

import xyz.mooma0750.wave.handoff.Envelope
import xyz.mooma0750.wave.handoff.EnvelopeType
import java.util.Base64

/** 封筒を封じる・開く。seq の永続化は呼び出し側（送る前に永続化、受けた最後の値をペアの鍵と同じレコードに）。 */
object EnvelopeSealer {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    fun deviceId(encodedPublicKey: ByteArray): String = b64.encodeToString(WaveCrypto.fingerprint(encodedPublicKey))

    fun seal(
        sendKey: ByteArray,
        from: String,
        to: String,
        type: EnvelopeType,
        seq: Long,
        handoffId: String?,
        plaintext: ByteArray,
    ): Envelope {
        val aad = aad(Envelope.VERSION, Envelope.SUITE_P256_AESGCM, from, to, type, seq, handoffId)
        val (nonce, ciphertext) = WaveCrypto.seal(sendKey, aad, plaintext)
        return Envelope(Envelope.VERSION, Envelope.SUITE_P256_AESGCM, from, to, type, seq, handoffId, b64.encodeToString(nonce), b64.encodeToString(ciphertext))
    }

    /**
     * 開く。宛先が自分でない・版や暗号の組が知らない・seq が最後に受けた値以下・認証に失敗、のどれでも null。
     * @param lastReceivedSeq この相手から最後に受けた seq（無ければ -1）
     */
    fun open(
        envelope: Envelope,
        receiveKey: ByteArray,
        self: String,
        expectedFrom: String,
        lastReceivedSeq: Long,
    ): ByteArray? {
        if (envelope.v != Envelope.VERSION || envelope.suite != Envelope.SUITE_P256_AESGCM) return null
        if (envelope.to != self || envelope.from != expectedFrom) return null
        if (envelope.seq <= lastReceivedSeq) return null
        if (envelope.ciphertext.length > Envelope.MAX_CIPHERTEXT_CHARS) return null
        val nonce = runCatching { unb64.decode(envelope.nonce) }.getOrNull() ?: return null
        val ciphertext = runCatching { unb64.decode(envelope.ciphertext) }.getOrNull() ?: return null
        return WaveCrypto.open(receiveKey, nonce, aad(envelope.v, envelope.suite, envelope.from, envelope.to, envelope.type, envelope.seq, envelope.handoffId), ciphertext)
    }

    private fun aad(
        v: Int,
        suite: Int,
        from: String,
        to: String,
        type: EnvelopeType,
        seq: Long,
        handoffId: String?,
    ): ByteArray =
        WaveCrypto.lengthPrefixed(
            "wave-envelope-v1".encodeToByteArray(),
            v.toString().encodeToByteArray(),
            suite.toString().encodeToByteArray(),
            from.encodeToByteArray(),
            to.encodeToByteArray(),
            type.name.encodeToByteArray(),
            seq.toString().encodeToByteArray(),
            // null と "" を区別する（有無の1バイトを前に付ける）
            if (handoffId == null) byteArrayOf(0) else byteArrayOf(1) + handoffId.encodeToByteArray(),
        )
}
