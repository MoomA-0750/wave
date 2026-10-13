package xyz.mooma0750.wave.connect

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey

/**
 * この端末の身元（Phase 6、ADR-0008）。Android Keystore の P-256 署名鍵で、秘密鍵は取り出せない。
 * ユーザー認証・端末のアンロックは要求しない（画面ロック中の再生で中継に認証するため、ADR-0002 の cookie の鍵と同じ）。
 * 署名だけに使い、鍵交換には使わない（決定 K-9）。Connect v2（ADR-0010）の身元もこの鍵（別名は v1 の頃のまま）。
 * 消すと家の端末としての身元が変わるので、v1 の削除（2026-10-14）でも残している。
 */
class DeviceIdentity(
    private val alias: String = ALIAS,
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @get:Synchronized
    val publicKey: PublicKey
        get() = entry().certificate.publicKey

    val encodedPublicKey: ByteArray
        get() = WaveCrypto.encodePublicKey(publicKey)

    /** 端末 ID = 署名公開鍵の SHA-256（base64url）。 */
    val deviceId: String
        get() = EnvelopeSealer.deviceId(encodedPublicKey)

    /** 指紋の表示（先頭 16 バイトを 4 文字ずつ）。 */
    val fingerprintForDisplay: String
        get() = WaveCrypto.fingerprint(encodedPublicKey).take(16).joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")

    fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = WaveCrypto.sign(privateKey(), label, message)

    fun privateKey(): PrivateKey = entry().privateKey

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry {
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply {
                initialize(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKeyPair()
        return keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    private companion object {
        const val ALIAS = "wave-device-identity-v1"
    }
}
