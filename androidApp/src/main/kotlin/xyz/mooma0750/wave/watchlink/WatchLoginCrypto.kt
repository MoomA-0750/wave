package xyz.mooma0750.wave.watchlink

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 時計へログインを渡すときの暗号（ADR-0011）。Data Layer はそれ自体も暗号化されているが、途中で読まれないよう、もう一段かける。
 * 時計がその場限りの鍵の組を作って公開鍵を送り、スマホも使い捨ての鍵で ECDH をして AES-GCM で包む。
 * 公開鍵から作る6桁の番号を両方の画面に出し、本人が見比べてから送る（すり替えられた鍵に送らない）。
 */
object WatchLoginCrypto {
    private const val CURVE = "secp256r1"
    private const val INFO = "wave-watch-login-v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair()

    fun publicKeyOf(encoded: ByteArray): PublicKey? = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) }.getOrNull()

    /** 見比べる番号（時計の公開鍵から作る6桁）。 */
    fun codeOf(watchPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(watchPublicKey)
        val number = ((digest[0].toLong() and 0xff) shl 24 or ((digest[1].toLong() and 0xff) shl 16) or ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)) % 1_000_000
        return number.toString().padStart(6, '0').let { "${it.take(3)} ${it.drop(3)}" }
    }

    /** スマホ側: 時計の公開鍵に向けて包む。[自分の公開鍵の長さ(2)][自分の公開鍵][IV][暗号文]。 */
    fun seal(
        watchPublicKey: ByteArray,
        plain: ByteArray,
    ): ByteArray? {
        val peer = publicKeyOf(watchPublicKey) ?: return null
        val mine = newKeyPair()
        val key = derive(mine, peer, mine.public.encoded, watchPublicKey)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv)) }
        val sealed = cipher.doFinal(plain)
        val pub = mine.public.encoded
        return byteArrayOf((pub.size shr 8).toByte(), pub.size.toByte()) + pub + iv + sealed
    }

    /** 時計側: 自分の鍵の組で開く。開けなければ null。 */
    fun open(
        watchKeys: KeyPair,
        message: ByteArray,
    ): ByteArray? =
        runCatching {
            val length = ((message[0].toInt() and 0xff) shl 8) or (message[1].toInt() and 0xff)
            val pub = message.copyOfRange(2, 2 + length)
            val iv = message.copyOfRange(2 + length, 2 + length + IV_BYTES)
            val sealed = message.copyOfRange(2 + length + IV_BYTES, message.size)
            val peer = publicKeyOf(pub) ?: return null
            val key = derive(watchKeys, peer, pub, watchKeys.public.encoded)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv)) }.doFinal(sealed)
        }.getOrNull()

    /** ECDH の結果から HKDF-SHA256 で鍵を作る。両方の公開鍵を混ぜる（スマホの鍵 → 時計の鍵の順）。 */
    private fun derive(
        mine: KeyPair,
        peer: PublicKey,
        phonePublic: ByteArray,
        watchPublic: ByteArray,
    ): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(peer, true) }.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(phonePublic + watchPublic)
        val prk = hmac(salt, shared)
        return hmac(prk, INFO.encodeToByteArray() + 1).copyOf(32)
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
