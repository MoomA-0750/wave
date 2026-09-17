package xyz.mooma0750.wave.handoff.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 6 の暗号の部品（決定 K-4'）。プラットフォームの JCA（Android は Conscrypt）の ECDH・ECDSA・AES-GCM・HMAC だけを使い、
 * 暗号そのものは書かない。自前で書くのは HKDF（RFC 5869、テストベクタで検証）と、用途ラベル・連結の規則だけ。
 * 曲線は P-256 にそろえる（Pixel 3 の Keystore で ECDSA・ECDH が使え、X25519 は使えない。iOS の CryptoKit も P-256 を持つ）。
 */
object WaveCrypto {
    private val random = SecureRandom()

    /** P-256 の曲線のパラメータ（公開鍵が曲線上の点かを確かめる）。 */
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /** ペアリングのときだけの使い捨ての鍵（決定 K-9）。メモリだけに置く。 */
    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** 公開鍵の送る形（X.509 SubjectPublicKeyInfo）。 */
    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    /** 受け取った公開鍵を読む。P-256 で、無限遠点でなく、曲線上の点のときだけ（不正な曲線の点を使った攻撃を防ぐ）。 */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey? {
        if (encoded.size > 200) return null
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey }.getOrNull() ?: return null
        // 送る形を正規化したものだけ（圧縮点など、読み直すと別のバイト列になる形を受けると、端末と中継で端末 ID が食い違う）
        if (!key.encoded.contentEquals(encoded)) return null
        val params = key.params
        if (params.curve.a != A || params.curve.b != B || params.order != N || (params.curve.field as? java.security.spec.ECFieldFp)?.p != P) return null
        val x = key.w.affineX ?: return null
        val y = key.w.affineY ?: return null
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return null
        // BigInteger.TWO は Android の API 33 から（minSdk 31）なので使わない
        val lhs = y.multiply(y).mod(P)
        val rhs = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        return if (lhs == rhs) key else null
    }

    /** 端末 ID・指紋: 署名公開鍵の送る形の SHA-256。 */
    fun fingerprint(encodedPublicKey: ByteArray): ByteArray = sha256(encodedPublicKey)

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun ecdh(
        privateKey: PrivateKey,
        publicKey: ECPublicKey,
    ): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    /** 用途ラベルを前置して署名する（同じ鍵の署名を別の用途に流用させない）。privateKey は Android Keystore の鍵でもよい。 */
    fun sign(
        privateKey: PrivateKey,
        label: String,
        message: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(labeled(label, message))
            sign()
        }

    fun verify(
        publicKey: PublicKey,
        label: String,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(labeled(label, message))
                verify(signature)
            }
        }.getOrDefault(false)

    fun hmacSha256(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** AES-256-GCM で封じる。nonce は毎回 96 ビットの乱数（seq から導かない。送る前に落ちて seq を再使用しても nonce は重ならない）。 */
    fun seal(
        key: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        require(key.size == 32)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce to cipher.doFinal(plaintext)
    }

    /** 認証に失敗したら null。 */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? {
        if (key.size != 32 || nonce.size != 12) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /** 定数時間の比較。 */
    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)

    /** 長さ付きで連結する（区切りの曖昧さで別の入力と同じバイト列にならないように）。 */
    fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(part.size).array())
            out.write(part)
        }
        return out.toByteArray()
    }

    private fun labeled(
        label: String,
        message: ByteArray,
    ): ByteArray = lengthPrefixed(label.encodeToByteArray(), message)
}

/** HKDF（RFC 5869）、HMAC-SHA256。 */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray = WaveCrypto.hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..(255 * HASH_LEN))
        val out = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = WaveCrypto.hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}
