package xyz.mooma0750.wave.handoff.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * ペアリングの手順（Phase 6、ADR-0008）。通信に依らない純粋なロジック。A = 再生中の端末（QR を出す）、B = 手元の端末（QR を読む）。
 *
 * 1. A は使い捨ての鍵 eA を作り、QR `{v, pkA, eA, token}` を出す（秘密は載せない）
 * 2. B は使い捨ての鍵 eB を作り、ECDH(eB, eA) から導いた鍵で `{pkB, sigB(transcript), 端末名, アカウントの HMAC}` を封じて、eB と一緒に A へ送る
 * 3. A は開いて sigB を確かめ、`{sigA(transcript), 端末名, アカウントの HMAC}` を封じて返す
 * 4. B は sigA を確かめ、確認用の6桁を表示する。A は本人がその6桁を入力し、A の計算と一致したときだけペアを確定する（決定 K-10）
 *
 * 中継は中間者になりうると仮定する。QR は A→B の真正な片方向路で、pkA・eA・token を中継は差し替えられない。
 * 第三者が QR を撮って B より先に 2 を送っても、6桁が B の画面と一致しないので A が機械的に落とす。
 */
object Pairing {
    const val VERSION = 1
    const val TOKEN_BYTES = 16
    const val LIFETIME_MS = 2 * 60 * 1000L
    const val MAX_ATTEMPTS = 3
    private const val MAX_EXTENSION_CHARS = 2048

    private const val LABEL = "wave-pair-v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    @Serializable
    data class Offer(
        val v: Int,
        val pkA: String,
        val eA: String,
        val token: String,
    )

    /** B → A（中継の /pair 経由）。eB は平文（A が鍵を導くのに要る）、残りは封じる。 */
    @Serializable
    data class Hello(
        val v: Int,
        val eB: String,
        val nonce: String,
        val ciphertext: String,
    )

    /** A → B。 */
    @Serializable
    data class Confirm(
        val nonce: String,
        val ciphertext: String,
    )

    @Serializable
    private data class HelloBody(
        val pkB: String,
        val sigB: String,
        val deviceName: String,
        val accountHmac: String,
        /** Connect v2（Phase 6b）: B の家の鍵の記述（MemberKeys の JSON）。v1 は null。sigB の対象に入る。 */
        val extension: String? = null,
    )

    @Serializable
    private data class ConfirmBody(
        val sigA: String,
        val deviceName: String,
        val accountHmac: String,
        /** Connect v2: 家の ID・一覧の頭・storeId・版の床（6桁の照合の前に渡すので、秘密を入れない）。sigA の対象に入る。 */
        val extension: String? = null,
    )

    /** 確定したペア。鍵は保存するときに Keystore の AES 鍵で包む。 */
    class Established(
        val peerPublicKey: ByteArray,
        val peerDeviceName: String,
        /** 自分 → 相手の封筒の鍵。 */
        val sendKey: ByteArray,
        /** 相手 → 自分の封筒の鍵。 */
        val receiveKey: ByteArray,
        /** アカウントの HMAC の鍵。 */
        val accountKey: ByteArray,
        /** 相手の拡張（A から見た B の Hello、B から見た A の Confirm）。署名で確かめた値。 */
        val peerExtension: String? = null,
    )

    sealed interface Failure {
        data object Malformed : Failure

        data object BadSignature : Failure

        data object Expired : Failure

        data object WrongCode : Failure

        data object TooManyAttempts : Failure

        /** 別のアカウントでログインした端末（v1 は受け渡さない）。 */
        data object AccountMismatch : Failure

        data object AlreadyUsed : Failure
    }

    fun encodeOffer(offer: Offer): String = "wave-pair:" + b64.encodeToString(json.encodeToString(Offer.serializer(), offer).encodeToByteArray())

    fun decodeOffer(text: String): Offer? {
        if (!text.startsWith("wave-pair:") || text.length > 1024) return null
        val offer = runCatching { json.decodeFromString(Offer.serializer(), unb64.decode(text.removePrefix("wave-pair:")).decodeToString()) }.getOrNull() ?: return null
        if (offer.v != VERSION) return null
        if (WaveCrypto.decodePublicKey(unb64(offer.pkA) ?: return null) == null) return null
        if (WaveCrypto.decodePublicKey(unb64(offer.eA) ?: return null) == null) return null
        if (unb64(offer.token)?.size != TOKEN_BYTES) return null
        return offer
    }

    /** A 側。QR を出してから、1回の B の Hello と、6桁の入力を扱う。 */
    class Responder(
        private val identityPrivate: PrivateKey,
        identityPublic: PublicKey,
        private val deviceName: String,
        /** アカウントの識別子（正規化した DATASYNC_ID）。HMAC にだけ使う。 */
        private val accountId: String,
        private val nowMs: () -> Long,
        /** テスト用: Confirm に入れるアカウント（B 側の照合を確かめる）。 */
        private val confirmAccountId: String = accountId,
        /** Connect v2: Confirm に入れる拡張（秘密を入れない）。 */
        private val confirmExtension: String? = null,
    ) {
        private val pkA = WaveCrypto.encodePublicKey(identityPublic)
        private val ephemeral: KeyPair = WaveCrypto.generateEphemeralKeyPair()
        private val eA = WaveCrypto.encodePublicKey(ephemeral.public)
        val token: ByteArray = WaveCrypto.randomBytes(TOKEN_BYTES)
        private val createdAtMs = nowMs()
        private var used = false
        private var attempts = 0
        private var pending: PendingPair? = null

        private class PendingPair(
            val sas: String,
            val established: Established,
        )

        val offer: Offer = Offer(VERSION, b64.encodeToString(pkA), b64.encodeToString(eA), b64.encodeToString(token))

        /** 中継に預ける値（token そのものは渡さない）。 */
        val tokenHash: ByteArray = WaveCrypto.sha256(token)

        /** B の Hello を受けて Confirm を返す。token は最初の正しい Hello で消費する。 */
        @Synchronized
        fun onHello(hello: Hello): Result<Confirm> {
            if (expired()) return failure(Failure.Expired)
            if (used) return failure(Failure.AlreadyUsed)
            if (hello.v != VERSION) return failure(Failure.Malformed)
            val eBBytes = unb64(hello.eB) ?: return failure(Failure.Malformed)
            val eB = WaveCrypto.decodePublicKey(eBBytes) ?: return failure(Failure.Malformed)
            val shared = WaveCrypto.ecdh(ephemeral.private, eB)
            val transcript0 = WaveCrypto.lengthPrefixed(LABEL.encodeToByteArray(), pkA, eA, token, eBBytes)
            val prk = Hkdf.extract(token, shared)
            val body =
                WaveCrypto
                    .open(handshakeKey(prk, "b->a", transcript0), unb64(hello.nonce) ?: return failure(Failure.Malformed), transcript0, unb64(hello.ciphertext) ?: return failure(Failure.Malformed))
                    ?.let { runCatching { json.decodeFromString(HelloBody.serializer(), it.decodeToString()) }.getOrNull() }
                    ?: return failure(Failure.Malformed)
            val pkBBytes = unb64(body.pkB) ?: return failure(Failure.Malformed)
            val pkB = WaveCrypto.decodePublicKey(pkBBytes) ?: return failure(Failure.Malformed)
            // 自分自身とのペアは受けない（方向の鍵が崩れる）
            if (pkBBytes.contentEquals(pkA)) return failure(Failure.Malformed)
            val transcript = WaveCrypto.lengthPrefixed(transcript0, pkBBytes)
            if (body.extension != null && body.extension.length > MAX_EXTENSION_CHARS) return failure(Failure.Malformed)
            if (!WaveCrypto.verify(pkB, "$LABEL-b", signedTranscript(transcript, body.extension), unb64(body.sigB) ?: return failure(Failure.Malformed))) return failure(Failure.BadSignature)
            val keys = schedule(prk, transcript, self = pkA, peer = pkBBytes)
            if (!WaveCrypto.constantTimeEquals(accountHmac(keys.accountKey, accountId), unb64(body.accountHmac) ?: ByteArray(0))) return failure(Failure.AccountMismatch)
            used = true
            pending = PendingPair(keys.sas, Established(pkBBytes, sanitizeName(body.deviceName), keys.sendKey, keys.receiveKey, keys.accountKey, body.extension))
            val confirm = ConfirmBody(b64.encodeToString(WaveCrypto.sign(identityPrivate, "$LABEL-a", signedTranscript(transcript, confirmExtension))), deviceName, b64.encodeToString(accountHmac(keys.accountKey, confirmAccountId)), confirmExtension)
            val (nonce, ciphertext) = WaveCrypto.seal(handshakeKey(prk, "a->b", transcript0), transcript0, json.encodeToString(ConfirmBody.serializer(), confirm).encodeToByteArray())
            return Result.success(Confirm(b64.encodeToString(nonce), b64.encodeToString(ciphertext)))
        }

        /** 本人が入力した6桁（B の画面の数字）。一致したらペアを確定する。3回まで。 */
        @Synchronized
        fun onCodeEntered(code: String): Result<Established> {
            if (expired()) return failure(Failure.Expired)
            if (attempts >= MAX_ATTEMPTS) return failure(Failure.TooManyAttempts)
            val current = pending ?: return failure(Failure.Malformed)
            attempts++
            if (!WaveCrypto.constantTimeEquals(code.trim().encodeToByteArray(), current.sas.encodeToByteArray())) {
                if (attempts >= MAX_ATTEMPTS) pending = null
                return failure(if (attempts >= MAX_ATTEMPTS) Failure.TooManyAttempts else Failure.WrongCode)
            }
            pending = null
            return Result.success(current.established)
        }

        private fun expired() = nowMs() - createdAtMs > LIFETIME_MS
    }

    /** B 側。QR を読んで Hello を作り、A の Confirm で6桁とペアを得る。 */
    class Initiator(
        private val offer: Offer,
        private val identityPrivate: PrivateKey,
        identityPublic: PublicKey,
        private val deviceName: String,
        private val accountId: String,
        /** Connect v2: Hello に入れる拡張（B の家の鍵の記述）。 */
        private val helloExtension: String? = null,
    ) {
        private val pkB = WaveCrypto.encodePublicKey(identityPublic)
        private val ephemeral = WaveCrypto.generateEphemeralKeyPair()
        private val eB = WaveCrypto.encodePublicKey(ephemeral.public)
        private val pkA = requireNotNull(unb64(offer.pkA)?.takeIf { WaveCrypto.decodePublicKey(it) != null }) { "offer は decodeOffer を通すこと" }
        private val eA = requireNotNull(unb64(offer.eA)?.takeIf { WaveCrypto.decodePublicKey(it) != null }) { "offer は decodeOffer を通すこと" }
        private val token = requireNotNull(unb64(offer.token)?.takeIf { it.size == TOKEN_BYTES }) { "offer は decodeOffer を通すこと" }
        private val transcript0 = WaveCrypto.lengthPrefixed(LABEL.encodeToByteArray(), pkA, eA, token, eB)
        private val transcript = WaveCrypto.lengthPrefixed(transcript0, pkB)
        private val prk = Hkdf.extract(token, WaveCrypto.ecdh(ephemeral.private, WaveCrypto.decodePublicKey(eA)!!))
        private val keys = schedule(prk, transcript, self = pkB, peer = pkA)

        fun hello(): Hello {
            val body = HelloBody(b64.encodeToString(pkB), b64.encodeToString(WaveCrypto.sign(identityPrivate, "$LABEL-b", signedTranscript(transcript, helloExtension))), deviceName, b64.encodeToString(accountHmac(keys.accountKey, accountId)), helloExtension)
            val (nonce, ciphertext) = WaveCrypto.seal(handshakeKey(prk, "b->a", transcript0), transcript0, json.encodeToString(HelloBody.serializer(), body).encodeToByteArray())
            return Hello(VERSION, b64.encodeToString(eB), b64.encodeToString(nonce), b64.encodeToString(ciphertext))
        }

        /** A の Confirm を確かめ、（表示する6桁, 確定したときに保存するペア）を返す。 */
        fun onConfirm(confirm: Confirm): Result<Pair<String, Established>> {
            val body =
                WaveCrypto
                    .open(handshakeKey(prk, "a->b", transcript0), unb64(confirm.nonce) ?: return failure(Failure.Malformed), transcript0, unb64(confirm.ciphertext) ?: return failure(Failure.Malformed))
                    ?.let { runCatching { json.decodeFromString(ConfirmBody.serializer(), it.decodeToString()) }.getOrNull() }
                    ?: return failure(Failure.Malformed)
            val pkAKey = WaveCrypto.decodePublicKey(pkA) ?: return failure(Failure.Malformed)
            if (body.extension != null && body.extension.length > MAX_EXTENSION_CHARS) return failure(Failure.Malformed)
            if (!WaveCrypto.verify(pkAKey, "$LABEL-a", signedTranscript(transcript, body.extension), unb64(body.sigA) ?: return failure(Failure.Malformed))) return failure(Failure.BadSignature)
            if (!WaveCrypto.constantTimeEquals(accountHmac(keys.accountKey, accountId), unb64(body.accountHmac) ?: ByteArray(0))) return failure(Failure.AccountMismatch)
            return Result.success(keys.sas to Established(pkA, sanitizeName(body.deviceName), keys.sendKey, keys.receiveKey, keys.accountKey, body.extension))
        }
    }

    class PairingFailure(
        val reason: Failure,
    ) : Exception(reason.toString())

    private fun <T> failure(reason: Failure): Result<T> = Result.failure(PairingFailure(reason))

    private class Keys(
        val sas: String,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val accountKey: ByteArray,
    )

    /** 拡張があれば、署名の対象に拡張のハッシュを足す（無ければ v1 と同じ）。 */
    private fun signedTranscript(
        transcript: ByteArray,
        extension: String?,
    ): ByteArray = if (extension == null) transcript else WaveCrypto.lengthPrefixed(transcript, WaveCrypto.sha256(extension.encodeToByteArray()))

    private fun handshakeKey(
        prk: ByteArray,
        direction: String,
        transcript0: ByteArray,
    ): ByteArray = Hkdf.expand(prk, "$LABEL handshake $direction".encodeToByteArray() + WaveCrypto.sha256(transcript0), 32)

    /**
     * 確定したペアの鍵。方向は端末 ID（公開鍵の SHA-256）の大小で決める（ペアリングの役割で決めると、以後の受け渡しで A・B が入れ替わって混乱する）。
     */
    private fun schedule(
        prk: ByteArray,
        transcript: ByteArray,
        self: ByteArray,
        peer: ByteArray,
    ): Keys {
        val hash = WaveCrypto.sha256(transcript)
        val sasBytes = Hkdf.expand(prk, "$LABEL sas".encodeToByteArray() + hash, 8)
        val sasNumber = java.nio.ByteBuffer.wrap(sasBytes).long.let { java.lang.Long.remainderUnsigned(it, 1_000_000) }
        val lowToHigh = Hkdf.expand(prk, "$LABEL key low->high".encodeToByteArray() + hash, 32)
        val highToLow = Hkdf.expand(prk, "$LABEL key high->low".encodeToByteArray() + hash, 32)
        val accountKey = Hkdf.expand(prk, "$LABEL account".encodeToByteArray() + hash, 32)
        val selfIsLow = compareUnsigned(WaveCrypto.fingerprint(self), WaveCrypto.fingerprint(peer)) < 0
        return Keys(
            sas = "%06d".format(sasNumber),
            sendKey = if (selfIsLow) lowToHigh else highToLow,
            receiveKey = if (selfIsLow) highToLow else lowToHigh,
            accountKey = accountKey,
        )
    }

    /** 同じアカウントかを確かめる HMAC（誤操作の防止で、防御ではない。ADR-0008）。 */
    fun accountHmac(
        accountKey: ByteArray,
        accountId: String,
    ): ByteArray = WaveCrypto.hmacSha256(accountKey, "wave-account-v1".encodeToByteArray(), accountId.encodeToByteArray())

    private fun compareUnsigned(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun unb64(value: String): ByteArray? = runCatching { unb64.decode(value) }.getOrNull()

    /** 端末名から制御文字・書字方向の上書き文字を落とし、64文字までにする。 */
    fun sanitizeName(name: String): String =
        name
            .filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '\u200E' || it == '\u200F' }
            .trim()
            .take(64)
            .ifEmpty { "端末" }
}
