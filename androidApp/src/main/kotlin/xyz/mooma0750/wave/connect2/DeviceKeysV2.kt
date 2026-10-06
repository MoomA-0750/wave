package xyz.mooma0750.wave.connect2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.DeviceSigner
import xyz.mooma0750.wave.handoff.v2.KxOpener
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Connect v2 の端末の鍵（Phase 6b、D-1）。身元は v1 と同じ Keystore の署名鍵。どちらもユーザー認証は要求しない（画面ロック中の再生で状態を開くため）。
 *
 * 受け取りの鍵（kx）は、Keystore が TEE などで守れる端末では Keystore の P-256 の鍵交換の鍵（取り出せない）。
 * Keystore がソフトウェアでしか守れない端末（Pixel 3、Android 12）では、Keystore の鍵での鍵交換が1回ごとに操作の枠を漏らし、
 * 15回ほどで wave の Keystore の操作がすべて止まる（再起動まで戻らない。2026-10-06 に keystore_probe で確かめた）。
 * その端末では、アプリの中で kx を持ち、Keystore の AES 鍵で包んでファイルに置く（解くのはプロセスごとに1回。守りの強さは
 * Keystore のソフトウェア実装とほぼ同じ）。
 */
class DeviceKeysV2(
    private val identity: DeviceIdentity,
    /** ソフトウェアの kx を包んで置くファイル。 */
    private val softwareKxFile: File,
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
        get() = kx().public

    /** 相手の点は呼ぶ側（Hpke.deserializePublicKey）で曲線上の点を確かめてある。 */
    override fun dh(peer: ECPublicKey): ByteArray {
        val validated = requireNotNull(WaveCrypto.decodePublicKey(peer.encoded)) { "invalid peer key" }
        return when (val key = kx()) {
            is Kx.Software -> WaveCrypto.ecdh(key.pair.private, validated)
            is Kx.Keystore ->
                KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
                    init(key.entry.privateKey)
                    doPhase(validated, true)
                    generateSecret()
                }
        }
    }

    private sealed interface Kx {
        val public: ECPublicKey

        class Keystore(
            val entry: KeyStore.PrivateKeyEntry,
        ) : Kx {
            override val public: ECPublicKey get() = entry.certificate.publicKey as ECPublicKey
        }

        class Software(
            val pair: KeyPair,
        ) : Kx {
            override val public: ECPublicKey get() = pair.public as ECPublicKey
        }
    }

    private var cached: Kx? = null

    @Synchronized
    private fun kx(): Kx {
        cached?.let { return it }
        val resolved = loadSoftware() ?: keystoreOrSoftware()
        cached = resolved
        return resolved
    }

    /** Keystore の kx を使う。無ければ作る。ソフトウェアでしか守れないなら、消してアプリの中の kx に替える。 */
    private fun keystoreOrSoftware(): Kx {
        val entry = (keyStore.getEntry(kxAlias, null) as? KeyStore.PrivateKeyEntry) ?: generateKeystore()
        if (!isSoftwareOnly(entry.privateKey)) return Kx.Keystore(entry)
        Log.i(WaveApplication.LOG_TAG, "connect-v2 kx keystore is software-only, using app kx")
        val software = createSoftware()
        keyStore.deleteEntry(kxAlias)
        return software
    }

    private fun generateKeystore(): KeyStore.PrivateKeyEntry {
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

    private fun isSoftwareOnly(key: PrivateKey): Boolean =
        runCatching {
            val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            info.securityLevel == KeyProperties.SECURITY_LEVEL_SOFTWARE
        }.getOrDefault(false)

    // --- アプリの中の kx（Keystore の AES 鍵で包んでファイルに置く） ---

    private val file = AtomicFile(softwareKxFile)

    private fun loadSoftware(): Kx.Software? {
        val bytes = runCatching { file.readFully() }.getOrNull() ?: return null
        // 包みを開けない（Keystore の鍵が消えた・壊れた）なら、勝手に作り直さない（kx が変わると家の文書が開けなくなる）
        val plain =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrapKey(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(WRAP_AAD)
                doFinal(bytes.copyOfRange(12, bytes.size))
            }
        try {
            val publicLength = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
            val factory = KeyFactory.getInstance("EC")
            val public = factory.generatePublic(X509EncodedKeySpec(plain.copyOfRange(2, 2 + publicLength)))
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(plain.copyOfRange(2 + publicLength, plain.size)))
            return Kx.Software(KeyPair(public, private))
        } finally {
            plain.fill(0)
        }
    }

    private fun createSoftware(): Kx.Software {
        val pair = WaveCrypto.generateEphemeralKeyPair()
        val public = pair.public.encoded
        val private = pair.private.encoded
        val plain = byteArrayOf((public.size shr 8).toByte(), public.size.toByte()) + public + private
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey(create = true)) }
            cipher.updateAAD(WRAP_AAD)
            val sealed = cipher.iv + cipher.doFinal(plain)
            val stream = file.startWrite()
            try {
                stream.write(sealed)
                file.finishWrite(stream)
            } catch (error: java.io.IOException) {
                file.failWrite(stream)
                throw error
            }
        } finally {
            plain.fill(0)
            private.fill(0)
        }
        return Kx.Software(pair)
    }

    private fun wrapKey(create: Boolean): SecretKey {
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "kx wrap key missing" }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val KX_ALIAS = "wave-device-kx-v1"
        const val WRAP_ALIAS = "wave-device-kx-wrap-v1"
        val WRAP_AAD = "wave-device-kx-v1".encodeToByteArray()
    }
}
