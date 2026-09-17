package xyz.mooma0750.wave.connect2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.DeviceSigner
import xyz.mooma0750.wave.handoff.v2.KxOpener
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Connect v2 の端末の鍵（Phase 6b、D-1）。身元は v1 と同じ Keystore の署名鍵、受け取りの鍵（kx）は Keystore の P-256 の鍵交換の鍵。
 * どちらも取り出せず、ユーザー認証は要求しない（画面ロック中の再生で状態を開くため）。
 * Pixel 3（Android 12）では kx 鍵は Keystore のソフトウェア実装に置かれる（P6b-S の計測、securityLevel=0）。
 */
class DeviceKeysV2(
    private val identity: DeviceIdentity,
    private val kxAlias: String = KX_ALIAS,
) : DeviceSigner, KxOpener {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override val deviceId: String
        get() = identity.deviceId

    override val identityPub: ByteArray
        get() = identity.encodedPublicKey

    override fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = identity.sign(label, message)

    override val kxPub: ECPublicKey
        get() = kxEntry().certificate.publicKey as ECPublicKey

    /** 相手の点は呼ぶ側（Hpke.deserializePublicKey）で曲線上の点を確かめてある。 */
    override fun dh(peer: ECPublicKey): ByteArray {
        val validated = requireNotNull(WaveCrypto.decodePublicKey(peer.encoded)) { "invalid peer key" }
        return KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
            init(kxEntry().privateKey)
            doPhase(validated, true)
            generateSecret()
        }
    }

    @Synchronized
    private fun kxEntry(): KeyStore.PrivateKeyEntry {
        (keyStore.getEntry(kxAlias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply {
                initialize(
                    KeyGenParameterSpec
                        .Builder(kxAlias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKeyPair()
        return keyStore.getEntry(kxAlias, null) as KeyStore.PrivateKeyEntry
    }

    private companion object {
        const val KX_ALIAS = "wave-device-kx-v1"
    }
}
