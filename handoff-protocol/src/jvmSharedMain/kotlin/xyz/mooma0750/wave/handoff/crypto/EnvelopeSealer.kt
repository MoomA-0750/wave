package xyz.mooma0750.wave.handoff.crypto

import java.util.Base64

/**
 * 端末 ID の決まり（ADR-0008）。端末 ID = 署名公開鍵（SPKI）の SHA-256 の base64url（パディングなし）。
 * v1 の封筒（ペアごとの鍵で封じる直接の受け渡し、ADR-0009）を消したあとも、v2 の家の一覧・中継・Windows・Mac と同じ値を使うので残している。
 */
object EnvelopeSealer {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun deviceId(encodedPublicKey: ByteArray): String = b64.encodeToString(WaveCrypto.fingerprint(encodedPublicKey))
}
