package xyz.mooma0750.wave.handoff.v2

import xyz.mooma0750.wave.handoff.crypto.Hpke
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** この端末の受け取りの鍵（Android では Keystore の kx 鍵で ECDH する）。 */
interface KxOpener {
    val deviceId: String
    val kxPub: ECPublicKey

    fun dh(peer: ECPublicKey): ByteArray
}

sealed interface DocOpen {
    data class Ok(
        val header: DocHeader,
        val plaintext: ByteArray,
        /** 自分の一覧で外された端末が宛先にいる（古い一覧で書かれた）。開いてよいが、書き手に書き直しを促す。 */
        val staleRecipients: Boolean,
    ) : DocOpen

    /** 書き手の一覧の頭が自分より新しい。鎖を取り直してから開く。 */
    data class NeedChain(
        val headSeq: Long,
    ) : DocOpen

    /** 書き手の一覧の頭が、同じ seq で自分の鎖と違う（サーバーが分岐させた）。取り込まず、止まる根拠にもしない。 */
    data object Fork : DocOpen

    data class Rejected(
        val reason: String,
    ) : DocOpen
}

/**
 * SealedDoc（D-1）: 本文を書き込みごとの内容鍵（AES-256-GCM）で暗号化し、内容鍵を家の端末ごとに HPKE で包み、全体に書き手が署名する。
 */
object DocSealer {
    const val DOC_LABEL = "wave-v2-doc"
    private const val WRAP_INFO = "wave-v2-wrap"
    private const val PAD_BLOCK = 4096
    const val MAX_PLAINTEXT = 1024 * 1024

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    /** 宛先は、書き手が検証した一覧の今の全端末。 */
    fun seal(
        signer: DeviceSigner,
        chain: VerifiedChain,
        kind: DocKind,
        version: Long,
        prevHash: String?,
        issuedAtEpochMs: Long,
        plaintext: ByteArray,
        /** 包む宛先を家の一部の端末に絞る（ログインを渡す命令、ADR-0015）。null なら家の全端末。 */
        only: Set<String>? = null,
    ): SealedDoc {
        require(plaintext.size <= MAX_PLAINTEXT)
        require(only == null || (only.isNotEmpty() && only.all { it in chain.members })) { "recipient is not a member" }
        require(signer.deviceId in chain.members) { "writer is not a member" }
        val header = DocHeader(kind = kind, householdId = chain.householdId, writerId = signer.deviceId, version = version, prevHash = prevHash, headSeq = chain.headSeq, headHash = chain.headHash, issuedAtEpochMs = issuedAtEpochMs)
        val headerHash = WaveCrypto.sha256(canonicalHeader(header))
        val contentKey = WaveCrypto.randomBytes(32)
        val (nonce, ciphertext) = WaveCrypto.seal(contentKey, headerHash, pad(plaintext))
        val recipients =
            chain.members.values.filter { only == null || it.keys.deviceId in only }.sortedBy { it.keys.deviceId }.map { member ->
                val sealed = Hpke.seal(member.kx, wrapInfo(kind, chain.householdId), wrapAad(headerHash, member.keys.deviceId), contentKey)
                DocRecipient(member.keys.deviceId, b64.encodeToString(sealed.enc), b64.encodeToString(sealed.ciphertext))
            }
        val unsigned = SealedDoc(header, b64.encodeToString(nonce), b64.encodeToString(ciphertext), recipients, "")
        return unsigned.copy(signature = b64.encodeToString(signer.sign(DOC_LABEL, signedBytes(unsigned))))
    }

    /** 書き手・役割・署名・一覧の頭を確かめてから開く。 */
    fun open(
        doc: SealedDoc,
        chain: VerifiedChain,
        me: KxOpener,
    ): DocOpen {
        val header = doc.header
        if (header.v != DocHeader.VERSION) return DocOpen.Rejected("version")
        if (header.householdId != chain.householdId) return DocOpen.Rejected("household")
        if (header.headSeq > chain.headSeq) return DocOpen.NeedChain(header.headSeq)
        if (chain.hashAt(header.headSeq) != header.headHash) return DocOpen.Fork
        val writer = chain.members[header.writerId] ?: return DocOpen.Rejected("writer_not_member")
        if (!roleAllowed(header.kind, writer.keys.role)) return DocOpen.Rejected("role")
        if (doc.recipients.map { it.deviceId }.toSet().size != doc.recipients.size) return DocOpen.Rejected("duplicate_recipient")
        val signature = decode(doc.signature) ?: return DocOpen.Rejected("malformed")
        if (!WaveCrypto.verify(writer.identity, DOC_LABEL, signedBytes(doc), signature)) return DocOpen.Rejected("signature")
        val mine = doc.recipients.firstOrNull { it.deviceId == me.deviceId } ?: return DocOpen.Rejected("not_for_me")
        val headerHash = WaveCrypto.sha256(canonicalHeader(header))
        val enc = decode(mine.enc) ?: return DocOpen.Rejected("malformed")
        val wrapped = decode(mine.wrappedKey) ?: return DocOpen.Rejected("malformed")
        val contentKey = Hpke.open(enc, me.kxPub, me::dh, wrapInfo(header.kind, header.householdId), wrapAad(headerHash, me.deviceId), wrapped) ?: return DocOpen.Rejected("unwrap")
        val nonce = decode(doc.nonce) ?: return DocOpen.Rejected("malformed")
        val ciphertext = decode(doc.ciphertext) ?: return DocOpen.Rejected("malformed")
        val padded = WaveCrypto.open(contentKey, nonce, headerHash, ciphertext) ?: return DocOpen.Rejected("content")
        val plaintext = unpad(padded) ?: return DocOpen.Rejected("padding")
        val stale = doc.recipients.any { it.deviceId in chain.removed }
        return DocOpen.Ok(header, plaintext, stale)
    }

    /** doc のハッシュ（prevHash・食い違いの検出・キューの参照）。署名は含めない（書き換えで別のハッシュを作らせない）。 */
    fun hash(doc: SealedDoc): String = b64.encodeToString(WaveCrypto.sha256(signedBytes(doc)))

    fun roleAllowed(
        kind: DocKind,
        role: Role,
    ): Boolean =
        when (kind) {
            DocKind.Cursor, DocKind.Queue -> role == Role.Player
            DocKind.Command, DocKind.Profile, DocKind.Library -> true
        }

    private fun wrapInfo(
        kind: DocKind,
        householdId: String,
    ): ByteArray = WaveCrypto.lengthPrefixed(WRAP_INFO.encodeToByteArray(), kind.name.encodeToByteArray(), householdId.encodeToByteArray())

    private fun wrapAad(
        headerHash: ByteArray,
        recipientId: String,
    ): ByteArray = WaveCrypto.lengthPrefixed(headerHash, recipientId.encodeToByteArray())

    private fun canonicalHeader(header: DocHeader): ByteArray =
        WaveCrypto.lengthPrefixed(
            *listOf(
                header.v.toString(),
                header.kind.name,
                header.householdId,
                header.writerId,
                header.version.toString(),
                header.prevHash.orEmpty(),
                header.headSeq.toString(),
                header.headHash,
                header.issuedAtEpochMs.toString(),
            ).map { it.encodeToByteArray() }.toTypedArray(),
        )

    /** 署名の対象: 見出し・nonce・暗号文・宛先ごとの包み（宛先 ID の順）。 */
    private fun signedBytes(doc: SealedDoc): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += canonicalHeader(doc.header)
        parts += doc.nonce.encodeToByteArray()
        parts += doc.ciphertext.encodeToByteArray()
        doc.recipients.sortedBy { it.deviceId }.forEach {
            parts += it.deviceId.encodeToByteArray()
            parts += it.enc.encodeToByteArray()
            parts += it.wrappedKey.encodeToByteArray()
        }
        return WaveCrypto.lengthPrefixed(*parts.toTypedArray())
    }

    /** 長さ（4バイト）‖ 本文 ‖ 0 埋めで、4KB 単位にそろえる（大きさからキューの長さを細かく読ませない）。 */
    private fun pad(plaintext: ByteArray): ByteArray {
        val total = ((plaintext.size + 4 + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK
        return ByteBuffer.allocate(total).putInt(plaintext.size).put(plaintext).array()
    }

    private fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 4 || padded.size % PAD_BLOCK != 0) return null
        val size = ByteBuffer.wrap(padded, 0, 4).int
        if (size < 0 || size > padded.size - 4) return null
        return padded.copyOfRange(4, 4 + size)
    }

    private fun decode(value: String): ByteArray? = runCatching { unb64.decode(value) }.getOrNull()
}
