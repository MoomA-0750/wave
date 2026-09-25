package xyz.mooma0750.wave.handoff.v2

import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Hpke
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** 端末の身元鍵での署名（Android では Keystore の鍵。値はアプリに出ない）。 */
interface DeviceSigner {
    val deviceId: String

    /** 身元の署名公開鍵（SPKI）。 */
    val identityPub: ByteArray

    fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray
}

class ChainMember(
    val keys: MemberKeys,
    val identity: ECPublicKey,
    val kx: ECPublicKey,
    val addedSeq: Long,
)

/** genesis から検証した端末一覧。 */
class VerifiedChain(
    val householdId: String,
    val headSeq: Long,
    val headHash: String,
    val members: Map<String, ChainMember>,
    val removed: Set<String>,
    private val hashes: List<String>,
) {
    fun hashAt(seq: Long): String? = hashes.getOrNull(seq.toInt())
}

sealed interface ChainVerification {
    data class Ok(
        val chain: VerifiedChain,
    ) : ChainVerification

    data class Invalid(
        val seq: Long,
        val reason: String,
    ) : ChainVerification

    /** 自分が検証した頭より短い（サーバーが巻き戻した）。 */
    data object Rollback : ChainVerification

    /** 自分が検証した頭と同じ seq で、ハッシュが違う（サーバーが分岐を見せた）。 */
    data object Fork : ChainVerification
}

/** 端末一覧の鎖（D-2）。記録の作成と、genesis からの検証。 */
object MemberChain {
    const val CHAIN_LABEL = "wave-v2-chain"
    const val KX_LABEL = "wave-v2-kx"
    private const val MAX_RECORDS = 1024

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    class Head(
        val seq: Long,
        val hash: String,
    )

    /** この端末が家に加わるときの鍵の記述（kx を身元鍵で自己署名する）。 */
    fun memberKeys(
        signer: DeviceSigner,
        kxPub: ECPublicKey,
        role: Role,
    ): MemberKeys {
        val kx = Hpke.serializePublicKey(kxPub)
        val signature = signer.sign(KX_LABEL, kxMessage(signer.deviceId, kx, role))
        return MemberKeys(signer.deviceId, b64.encodeToString(signer.identityPub), b64.encodeToString(kx), role, b64.encodeToString(signature))
    }

    fun genesis(
        signer: DeviceSigner,
        self: MemberKeys,
    ): ChainRecord {
        require(self.deviceId == signer.deviceId && self.role == Role.Player)
        return signed(signer, ChainRecord(seq = 0, type = ChainType.Genesis, target = self, signerId = signer.deviceId, signature = ""))
    }

    fun add(
        signer: DeviceSigner,
        chain: VerifiedChain,
        target: MemberKeys,
    ): ChainRecord =
        signed(
            signer,
            ChainRecord(householdId = chain.householdId, seq = chain.headSeq + 1, prevHash = chain.headHash, type = ChainType.Add, target = target, signerId = signer.deviceId, signature = ""),
        )

    fun remove(
        signer: DeviceSigner,
        chain: VerifiedChain,
        deviceId: String,
    ): ChainRecord =
        signed(
            signer,
            ChainRecord(householdId = chain.householdId, seq = chain.headSeq + 1, prevHash = chain.headHash, type = ChainType.Remove, removedDeviceId = deviceId, signerId = signer.deviceId, signature = ""),
        )

    /**
     * 記録のハッシュ（householdId・prevHash・頭）。署名は含めない（ECDSA の署名は、秘密鍵なしに別の有効な署名へ書き換えられるので、
     * 中継が署名を書き換えるだけで偽の分岐を作れてしまう。レビュー p6b12 低）。署名は検証で内容に結び付いている
     */
    fun hash(record: ChainRecord): String = b64.encodeToString(WaveCrypto.sha256(canonical(record)))

    /**
     * genesis から検証する。trusted（この端末が前に検証した頭）があれば、それより短い鎖と、その seq でハッシュが違う鎖を拒む。
     */
    fun verify(
        records: List<ChainRecord>,
        trusted: Head? = null,
    ): ChainVerification {
        if (records.isEmpty() || records.size > MAX_RECORDS) return ChainVerification.Invalid(0, "size")
        val members = LinkedHashMap<String, ChainMember>()
        val removed = HashSet<String>()
        val hashes = ArrayList<String>()
        var householdId = ""
        records.forEachIndexed { index, record ->
            val seq = index.toLong()
            fun invalid(reason: String) = ChainVerification.Invalid(seq, reason)
            if (record.v != ChainRecord.VERSION || record.seq != seq) return invalid("seq")
            if (index == 0) {
                if (record.type != ChainType.Genesis || record.householdId != null || record.prevHash != null) return invalid("genesis")
                val target = record.target ?: return invalid("genesis_target")
                if (target.role != Role.Player || record.signerId != target.deviceId || record.removedDeviceId != null) return invalid("genesis_signer")
                val member = memberOf(target, seq) ?: return invalid("genesis_keys")
                if (!verifySignature(record, member.identity)) return invalid("signature")
                members[target.deviceId] = member
                householdId = hash(record)
            } else {
                if (record.householdId != householdId || record.prevHash != hashes.last()) return invalid("link")
                val signer = members[record.signerId] ?: return invalid("signer_not_member")
                if (!verifySignature(record, signer.identity)) return invalid("signature")
                when (record.type) {
                    ChainType.Genesis -> return invalid("second_genesis")
                    ChainType.Add -> {
                        val target = record.target ?: return invalid("add_target")
                        if (record.removedDeviceId != null) return invalid("add_shape")
                        // player を加えられるのは player だけ（L-11）。controller も当面は player だけが加える
                        if (signer.keys.role != Role.Player) return invalid("add_by_controller")
                        if (target.deviceId in members || target.deviceId in removed) return invalid("add_existing")
                        members[target.deviceId] = memberOf(target, seq) ?: return invalid("add_keys")
                    }
                    ChainType.Remove -> {
                        val targetId = record.removedDeviceId ?: return invalid("remove_target")
                        if (record.target != null) return invalid("remove_shape")
                        val target = members[targetId] ?: return invalid("remove_not_member")
                        // controller は自分だけを外せる
                        if (signer.keys.role != Role.Player && targetId != signer.keys.deviceId) return invalid("remove_by_controller")
                        // player がいなくなる remove は拒む（アカウントタグの鍵の担い手が消える）
                        if (target.keys.role == Role.Player && members.values.count { it.keys.role == Role.Player } <= 1) return invalid("last_player")
                        members.remove(targetId)
                        removed += targetId
                    }
                }
            }
            hashes += hash(record)
        }
        if (trusted != null) {
            if (records.size <= trusted.seq) return ChainVerification.Rollback
            if (hashes[trusted.seq.toInt()] != trusted.hash) return ChainVerification.Fork
        }
        return ChainVerification.Ok(VerifiedChain(householdId, records.size - 1L, hashes.last(), members, removed, hashes))
    }

    private fun memberOf(
        keys: MemberKeys,
        seq: Long,
    ): ChainMember? {
        val identityBytes = runCatching { unb64.decode(keys.identityPub) }.getOrNull() ?: return null
        val identity = WaveCrypto.decodePublicKey(identityBytes) ?: return null
        if (EnvelopeSealer.deviceId(identityBytes) != keys.deviceId) return null
        val kxBytes = runCatching { unb64.decode(keys.kxPub) }.getOrNull() ?: return null
        val kx = Hpke.deserializePublicKey(kxBytes) ?: return null
        val kxSignature = runCatching { unb64.decode(keys.kxSignature) }.getOrNull() ?: return null
        if (!WaveCrypto.verify(identity, KX_LABEL, kxMessage(keys.deviceId, kxBytes, keys.role), kxSignature)) return null
        return ChainMember(keys, identity, kx, seq)
    }

    private fun signed(
        signer: DeviceSigner,
        record: ChainRecord,
    ): ChainRecord = record.copy(signature = b64.encodeToString(signer.sign(CHAIN_LABEL, canonical(record))))

    private fun verifySignature(
        record: ChainRecord,
        identity: ECPublicKey,
    ): Boolean {
        val signature = runCatching { unb64.decode(record.signature) }.getOrNull() ?: return false
        return WaveCrypto.verify(identity, CHAIN_LABEL, canonical(record), signature)
    }

    private fun kxMessage(
        deviceId: String,
        kx: ByteArray,
        role: Role,
    ): ByteArray = WaveCrypto.lengthPrefixed(deviceId.encodeToByteArray(), kx, role.name.encodeToByteArray())

    /** 署名とハッシュの対象（署名そのものを除く全部を長さ付きで連結）。 */
    private fun canonical(record: ChainRecord): ByteArray {
        val target = record.target
        return WaveCrypto.lengthPrefixed(
            *listOf(
                record.v.toString(),
                record.householdId.orEmpty(),
                record.seq.toString(),
                record.prevHash.orEmpty(),
                record.type.name,
                target?.deviceId.orEmpty(),
                target?.identityPub.orEmpty(),
                target?.kxPub.orEmpty(),
                target?.role?.name.orEmpty(),
                target?.kxSignature.orEmpty(),
                record.removedDeviceId.orEmpty(),
                record.signerId,
            ).map { it.encodeToByteArray() }.toTypedArray(),
        )
    }
}
