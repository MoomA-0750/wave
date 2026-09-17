package xyz.mooma0750.wave.handoff.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9180 HPKE の base モード、`DHKEM(P-256, HKDF-SHA256)`・`HKDF-SHA256`・`AES-128-GCM`（Phase 6b、決定 L-7）。
 * 付録 A.3 のテストベクタで確かめる。書き込みごとの内容鍵を、家の端末それぞれの受け取りの鍵（kx）へ包むのに使う。
 *
 * 受け手の DH は関数で受ける（Android では Keystore の kx 鍵で ECDH する。鍵の値はアプリに出ない）。
 * 公開鍵の送る形は非圧縮 SEC1（65バイト）。受け取った点は正規形と曲線上の点を確かめる（WaveCrypto.decodePublicKey）。
 */
object Hpke {
    private const val KEM_ID = 0x0010
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0001
    private const val MODE_BASE = 0x00
    private const val N_SECRET = 32
    private const val N_K = 16
    private const val N_N = 12
    const val N_ENC = 65

    private val VERSION_LABEL = "HPKE-v1".encodeToByteArray()
    private val KEM_SUITE = "KEM".encodeToByteArray() + i2osp(KEM_ID, 2)
    private val HPKE_SUITE = "HPKE".encodeToByteArray() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)

    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** 封じた結果。enc は使い捨ての鍵の公開鍵（SEC1）。 */
    class Sealed(
        val enc: ByteArray,
        val ciphertext: ByteArray,
    )

    /** 受け手の公開鍵 pkR へ、1回だけの暗号化（シーケンス番号 0）。 */
    fun seal(
        recipient: ECPublicKey,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): Sealed = seal(recipient, info, aad, plaintext, WaveCrypto.generateEphemeralKeyPair())

    /** テスト用: 使い捨ての鍵を外から与える（付録 A.3 のベクタ）。 */
    internal fun seal(
        recipient: ECPublicKey,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        ephemeral: KeyPair,
    ): Sealed {
        val (sharedSecret, enc) = encap(recipient, ephemeral)
        val context = keySchedule(sharedSecret, info)
        return Sealed(enc, context.seal(0, aad, plaintext))
    }

    /**
     * 開く。認証に失敗したら null。
     * @param recipientPublic 受け手自身の公開鍵（kem_context に入る）
     * @param dh 受け手の秘密鍵で、渡された公開鍵との ECDH の x 座標（32バイト）を返す
     */
    fun open(
        enc: ByteArray,
        recipientPublic: ECPublicKey,
        dh: (ECPublicKey) -> ByteArray,
        info: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? {
        val sharedSecret = decap(enc, recipientPublic, dh) ?: return null
        return keySchedule(sharedSecret, info).open(0, aad, ciphertext)
    }

    /** 非圧縮 SEC1（0x04 ‖ X ‖ Y）。 */
    fun serializePublicKey(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    /** 非圧縮 SEC1 を読む。長さ・形・曲線上の点を確かめる。 */
    fun deserializePublicKey(encoded: ByteArray): ECPublicKey? {
        if (encoded.size != N_ENC || encoded[0] != 0x04.toByte()) return null
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey }.getOrNull() ?: return null
        return WaveCrypto.decodePublicKey(key.encoded)
    }

    /** テスト用: 付録 A.3 の ikm から秘密鍵のスカラーを導く（DeriveKeyPair、P-256 のビットマスクは 0xff）。 */
    internal fun deriveSecretScalar(ikm: ByteArray): BigInteger {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "dkp_prk", ikm)
        for (counter in 0..255) {
            val bytes = labeledExpand(KEM_SUITE, prk, "candidate", byteArrayOf(counter.toByte()), N_SECRET)
            val candidate = BigInteger(1, bytes)
            if (candidate.signum() > 0 && candidate < N) return candidate
        }
        error("DeriveKeyPair failed")
    }

    /** テスト用: スカラーと公開鍵から JCA の鍵の組を作る。 */
    internal fun keyPairOf(
        secret: BigInteger,
        public: ECPublicKey,
    ): KeyPair = KeyPair(public, KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(secret, P256)))

    private fun encap(
        recipient: ECPublicKey,
        ephemeral: KeyPair,
    ): Pair<ByteArray, ByteArray> {
        val validated = WaveCrypto.decodePublicKey(recipient.encoded) ?: throw IllegalArgumentException("invalid recipient key")
        val dh = WaveCrypto.ecdh(ephemeral.private, validated)
        val enc = serializePublicKey(ephemeral.public as ECPublicKey)
        return extractAndExpand(dh, enc + serializePublicKey(validated)) to enc
    }

    private fun decap(
        enc: ByteArray,
        recipientPublic: ECPublicKey,
        dh: (ECPublicKey) -> ByteArray,
    ): ByteArray? {
        val ephemeral = deserializePublicKey(enc) ?: return null
        val shared = runCatching { dh(ephemeral) }.getOrNull() ?: return null
        if (shared.size != N_SECRET) return null
        return extractAndExpand(shared, enc + serializePublicKey(recipientPublic))
    }

    private fun extractAndExpand(
        dh: ByteArray,
        kemContext: ByteArray,
    ): ByteArray {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
        return labeledExpand(KEM_SUITE, prk, "shared_secret", kemContext, N_SECRET)
    }

    internal class Context(
        val key: ByteArray,
        val baseNonce: ByteArray,
        val exporterSecret: ByteArray,
    ) {
        fun seal(
            sequence: Long,
            aad: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = cipher(Cipher.ENCRYPT_MODE, sequence, aad).doFinal(plaintext)

        fun open(
            sequence: Long,
            aad: ByteArray,
            ciphertext: ByteArray,
        ): ByteArray? = runCatching { cipher(Cipher.DECRYPT_MODE, sequence, aad).doFinal(ciphertext) }.getOrNull()

        private fun cipher(
            mode: Int,
            sequence: Long,
            aad: ByteArray,
        ): Cipher {
            val nonce = baseNonce.copyOf()
            val seq = i2osp(sequence, N_N)
            for (i in nonce.indices) nonce[i] = (nonce[i].toInt() xor seq[i].toInt()).toByte()
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }
        }
    }

    internal fun keySchedule(
        sharedSecret: ByteArray,
        info: ByteArray,
    ): Context {
        val pskIdHash = labeledExtract(HPKE_SUITE, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE, ByteArray(0), "info_hash", info)
        val keyScheduleContext = byteArrayOf(MODE_BASE.toByte()) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", ByteArray(0))
        return Context(
            key = labeledExpand(HPKE_SUITE, secret, "key", keyScheduleContext, N_K),
            baseNonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", keyScheduleContext, N_N),
            exporterSecret = labeledExpand(HPKE_SUITE, secret, "exp", keyScheduleContext, N_SECRET),
        )
    }

    /** テスト用: 共有秘密を外から与える encap（付録 A.3 の shared_secret の確認）。 */
    internal fun encapForTest(
        recipient: ECPublicKey,
        ephemeral: KeyPair,
    ): Pair<ByteArray, ByteArray> = encap(recipient, ephemeral)

    private fun labeledExtract(
        suite: ByteArray,
        salt: ByteArray,
        label: String,
        ikm: ByteArray,
    ): ByteArray = Hkdf.extract(salt, VERSION_LABEL + suite + label.encodeToByteArray() + ikm)

    private fun labeledExpand(
        suite: ByteArray,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray = Hkdf.expand(prk, i2osp(length, 2) + VERSION_LABEL + suite + label.encodeToByteArray() + info, length)

    private fun i2osp(
        value: Int,
        length: Int,
    ): ByteArray = i2osp(value.toLong(), length)

    private fun i2osp(
        value: Long,
        length: Int,
    ): ByteArray = ByteArray(length) { i -> if (length - 1 - i >= 8) 0 else (value ushr (8 * (length - 1 - i))).toByte() }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> throw IllegalArgumentException("coordinate too large")
        }
    }
}
