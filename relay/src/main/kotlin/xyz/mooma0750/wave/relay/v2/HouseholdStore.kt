package xyz.mooma0750.wave.relay.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ChainRecord
import xyz.mooma0750.wave.handoff.v2.ChainVerification
import xyz.mooma0750.wave.handoff.v2.DocKind
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import xyz.mooma0750.wave.handoff.v2.SealedDoc
import xyz.mooma0750.wave.handoff.v2.StateKind
import xyz.mooma0750.wave.handoff.v2.StoredState
import xyz.mooma0750.wave.handoff.v2.VerifiedChain
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * 中継 v2 の家（Phase 6b、D-2・D-4・D-6・D-7）。家は1つ（L-13）。サーバーは SealedDoc の中身を読まず、見出しと送り手の接続の端末 ID で判断する。
 * 状態の書き込みはファイルに書いてから返事をする。保存するのは最新のカーソルとキューだけ。storeId はファイルと一緒に永続化する。
 * transfer の途中（pending）はメモリだけに持つ（再起動で消えてよい）。
 */
class HouseholdStore(
    private val file: File,
    bootstrapKeys: List<String>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class StoredDoc(
        val version: Long,
        val doc: SealedDoc,
        val receivedAtServerMs: Long,
    )

    @Serializable
    private data class Persisted(
        val storeId: String,
        val chain: List<ChainRecord> = emptyList(),
        val cursor: StoredDoc? = null,
        val queue: StoredDoc? = null,
        val activeDeviceId: String? = null,
        val claimOrder: Long = 0,
        /** 端末ごとの最新の claim の順番。 */
        val claimOrders: Map<String, Long> = emptyMap(),
    )

    private class Pending(
        val claimId: String,
        val owner: String,
        val previousActive: String?,
        val untilMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val bootstrap: Map<String, String> =
        bootstrapKeys.associate { key ->
            val bytes = requireNotNull(runCatching { Base64.getUrlDecoder().decode(key) }.getOrNull()) { "WAVE_RELAY_BOOTSTRAP_KEYS に読めない公開鍵があります" }
            requireNotNull(WaveCrypto.decodePublicKey(bytes)) { "WAVE_RELAY_BOOTSTRAP_KEYS に読めない公開鍵があります" }
            EnvelopeSealer.deviceId(bytes) to key
        }

    private var persisted: Persisted
    private var chain: VerifiedChain? = null
    private var pending: Pending? = null

    /** 直前の claim・transfer が黙って捨てた transfer（呼ぶ側が PendingExpired を配る）。 */
    private var droppedByClaim: String? = null

    /** 直前の claim・transfer が捨てた transfer の claimId を取り出す。 */
    @Synchronized
    fun takeDroppedPending(): String? = droppedByClaim.also { droppedByClaim = null }

    init {
        // ファイルがあるのに読めなければ起動を止める（storeId を作り直すと、全端末に「作り直された」と出る）
        persisted =
            if (file.exists()) {
                json.decodeFromString(Persisted.serializer(), file.readText())
            } else {
                Persisted(storeId = b64.encodeToString(WaveCrypto.randomBytes(16))).also(::write)
            }
        if (persisted.chain.isNotEmpty()) {
            chain = (MemberChain.verify(persisted.chain) as? ChainVerification.Ok)?.chain ?: error("保存した鎖を検証できません")
        }
    }

    val storeId: String
        @Synchronized get() = persisted.storeId

    @Synchronized
    fun welcome(
        deviceId: String,
        online: List<String>,
    ): RelayV2.Welcome {
        val current = chain
        return RelayV2.Welcome(deviceId, persisted.storeId, current?.householdId, current?.headSeq, current?.headHash, persisted.activeDeviceId, online, latest(), persisted.claimOrders)
    }

    /** 接続の認証に使う公開鍵。家があれば今の端末だけ、無ければ起動時の設定の鍵だけ。 */
    @Synchronized
    fun authKey(deviceId: String): ECPublicKey? {
        val current = chain
        if (current != null) return current.members[deviceId]?.identity
        val key = bootstrap[deviceId] ?: return null
        return WaveCrypto.decodePublicKey(Base64.getUrlDecoder().decode(key))
    }

    @Synchronized
    fun isMember(deviceId: String): Boolean = chain?.members?.containsKey(deviceId) == true

    @Synchronized
    fun members(): Set<String> = chain?.members?.keys.orEmpty()

    sealed interface ChainResult {
        data class Ok(
            val headSeq: Long,
            val headHash: String,
            val removed: String?,
            /** 外した端末が再生中だったので空にした（呼ぶ側が active_changed を配る）。 */
            val activeChanged: RelayV2.ActiveChanged? = null,
        ) : ChainResult

        data class Conflict(
            val headSeq: Long,
            val headHash: String,
        ) : ChainResult

        data class Rejected(
            val code: String,
        ) : ChainResult
    }

    @Synchronized
    fun createHousehold(
        sender: String,
        genesis: ChainRecord,
    ): ChainResult {
        if (chain != null) return ChainResult.Rejected("household_exists")
        if (sender !in bootstrap || genesis.signerId != sender) return ChainResult.Rejected("not_bootstrap")
        val verified = (MemberChain.verify(listOf(genesis)) as? ChainVerification.Ok)?.chain ?: return ChainResult.Rejected("invalid_record")
        save(persisted.copy(chain = listOf(genesis)))
        chain = verified
        return ChainResult.Ok(verified.headSeq, verified.headHash, null)
    }

    @Synchronized
    fun append(
        sender: String,
        record: ChainRecord,
    ): ChainResult {
        val current = chain ?: return ChainResult.Rejected("no_household")
        if (!current.members.containsKey(sender) || record.signerId != sender) return ChainResult.Rejected("not_member")
        if (record.seq != current.headSeq + 1 || record.prevHash != current.headHash) return ChainResult.Conflict(current.headSeq, current.headHash)
        val records = persisted.chain + record
        val verified = (MemberChain.verify(records, MemberChain.Head(current.headSeq, current.headHash)) as? ChainVerification.Ok)?.chain ?: return ChainResult.Rejected("invalid_record")
        val removed = record.removedDeviceId
        val previousActive = persisted.activeDeviceId
        // 外した端末が再生中のままなら空にする（D-11）
        val active = persisted.activeDeviceId?.takeIf { it != removed }
        save(persisted.copy(chain = records, activeDeviceId = active))
        chain = verified
        if (pending?.owner == removed) pending = null
        val changed = if (removed != null && removed == previousActive) RelayV2.ActiveChanged(null, null, persisted.claimOrder) else null
        return ChainResult.Ok(verified.headSeq, verified.headHash, removed, changed)
    }

    @Synchronized
    fun chainFrom(seq: Long): List<ChainRecord> = persisted.chain.drop(seq.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

    @Synchronized
    fun latest(): List<StoredState> = listOfNotNull(stateOf(StateKind.Cursor), stateOf(StateKind.Queue))

    sealed interface PutResult {
        data class Ok(
            val state: StoredState,
            /** transfer の新しい端末の最初のカーソルで、再生中の端末が確定した。 */
            val activeChanged: RelayV2.ActiveChanged?,
        ) : PutResult

        data class Conflict(
            val latest: StoredState?,
        ) : PutResult

        data class Rejected(
            val code: String,
        ) : PutResult
    }

    @Synchronized
    fun put(
        sender: String,
        message: RelayV2.StatePut,
    ): PutResult {
        val current = chain ?: return PutResult.Rejected("no_household")
        val member = current.members[sender] ?: return PutResult.Rejected("not_member")
        if (member.keys.role != Role.Player) return PutResult.Rejected("not_player")
        val canWrite = persisted.activeDeviceId == sender || pending?.owner == sender
        if (!canWrite) return PutResult.Rejected("not_active")
        headerProblem(sender, current, message.kind, message.expectVersion, message.doc)?.let { return PutResult.Rejected(it) }
        val stored = storedOf(message.kind)
        if ((stored?.version ?: 0L) != message.expectVersion) return PutResult.Conflict(stateOf(message.kind))
        val doc = StoredDoc(message.expectVersion + 1, message.doc, nowMs())
        var activeChanged: RelayV2.ActiveChanged? = null
        var next = if (message.kind == StateKind.Cursor) persisted.copy(cursor = doc) else persisted.copy(queue = doc)
        val waiting = pending
        if (message.kind == StateKind.Cursor && waiting?.owner == sender) {
            next = next.copy(activeDeviceId = sender)
            pending = null
            activeChanged = RelayV2.ActiveChanged(sender, waiting.claimId, next.claimOrder)
        }
        save(next)
        return PutResult.Ok(stateOf(message.kind)!!, activeChanged)
    }

    sealed interface ClaimResult {
        data class Ok(
            val activeChanged: RelayV2.ActiveChanged,
        ) : ClaimResult

        data class Conflict(
            val latest: StoredState?,
        ) : ClaimResult

        data class Rejected(
            val code: String,
        ) : ClaimResult
    }

    /** 手元で新しく再生を始めた。CAS なしで届いた順に再生中の端末を替える（D-7）。 */
    @Synchronized
    fun claim(
        sender: String,
        claimId: String,
        yieldDoc: SealedDoc,
    ): ClaimResult {
        val current = chain ?: return ClaimResult.Rejected("no_household")
        if (current.members[sender]?.keys?.role != Role.Player) return ClaimResult.Rejected("not_player")
        if (!commandFrom(sender, current, yieldDoc) || claimId.length !in 16..64) return ClaimResult.Rejected("bad_command")
        droppedByClaim = pending?.claimId
        pending = null
        val order = persisted.claimOrder + 1
        save(persisted.copy(activeDeviceId = sender, claimOrder = order, claimOrders = persisted.claimOrders + (sender to order)))
        return ClaimResult.Ok(RelayV2.ActiveChanged(sender, claimId, order))
    }

    /** 「ここで再生」。サーバーの最新のカーソルの版が basedOnVersion と同じときだけ、新しい端末の最初の書き込みを待つ（D-7）。 */
    @Synchronized
    fun transfer(
        sender: String,
        claimId: String,
        basedOnVersion: Long,
        yieldDoc: SealedDoc,
    ): ClaimResult {
        val current = chain ?: return ClaimResult.Rejected("no_household")
        if (current.members[sender]?.keys?.role != Role.Player) return ClaimResult.Rejected("not_player")
        if (!commandFrom(sender, current, yieldDoc) || claimId.length !in 16..64) return ClaimResult.Rejected("bad_command")
        val cursor = persisted.cursor ?: return ClaimResult.Rejected("no_state")
        if (cursor.version != basedOnVersion) return ClaimResult.Conflict(stateOf(StateKind.Cursor))
        droppedByClaim = pending?.claimId
        pending = Pending(claimId, sender, persisted.activeDeviceId, nowMs() + PENDING_MS)
        val order = persisted.claimOrder + 1
        save(persisted.copy(claimOrder = order, claimOrders = persisted.claimOrders + (sender to order)))
        return ClaimResult.Ok(RelayV2.ActiveChanged(persisted.activeDeviceId, claimId, order, pendingOwner = sender))
    }

    /** 引き取られた端末の最後のカーソル。今の transfer の前の再生中の端末からだけ、state_put と同じ CAS で受ける。 */
    @Synchronized
    fun yielded(
        sender: String,
        message: RelayV2.Yielded,
    ): PutResult {
        val current = chain ?: return PutResult.Rejected("no_household")
        val waiting = pending ?: return PutResult.Rejected("no_pending")
        if (waiting.claimId != message.claimId || waiting.previousActive != sender) return PutResult.Rejected("not_pending_previous")
        headerProblem(sender, current, StateKind.Cursor, message.expectVersion, message.doc)?.let { return PutResult.Rejected(it) }
        val stored = persisted.cursor
        if ((stored?.version ?: 0L) != message.expectVersion) return PutResult.Conflict(stateOf(StateKind.Cursor))
        save(persisted.copy(cursor = StoredDoc(message.expectVersion + 1, message.doc, nowMs())))
        return PutResult.Ok(stateOf(StateKind.Cursor)!!, null)
    }

    @Synchronized
    fun release(
        sender: String,
        claimId: String,
    ): RelayV2.ActiveChanged? {
        val waiting = pending ?: return null
        if (waiting.claimId != claimId || waiting.owner != sender) return null
        return dropPending()
    }

    /** 期限を過ぎた、または持ち主が切れた transfer を捨てる。 */
    @Synchronized
    fun expirePending(disconnected: String? = null): Pair<String, RelayV2.ActiveChanged>? {
        val waiting = pending ?: return null
        if (waiting.untilMs >= nowMs() && waiting.owner != disconnected) return null
        return waiting.claimId to dropPending()
    }

    /**
     * 新しい端末が引き取れなかった: 前の再生中の端末に戻す（止まっていれば止まったまま。本人が再開できる）。
     * 空にすると、yield を受けずに鳴り続けている前の端末の書き込みまで断ってしまう
     */
    private fun dropPending(): RelayV2.ActiveChanged {
        val previous = pending?.previousActive
        pending = null
        save(persisted.copy(activeDeviceId = previous))
        return RelayV2.ActiveChanged(previous, null, persisted.claimOrder)
    }

    private fun headerProblem(
        sender: String,
        current: VerifiedChain,
        kind: StateKind,
        expectVersion: Long,
        doc: SealedDoc,
    ): String? {
        val header = doc.header
        val expectedKind = if (kind == StateKind.Cursor) DocKind.Cursor else DocKind.Queue
        return when {
            header.writerId != sender -> "writer_mismatch"
            header.householdId != current.householdId -> "household"
            header.kind != expectedKind -> "kind"
            header.version != expectVersion + 1 -> "version"
            header.headSeq > current.headSeq -> "future_head"
            // 古い一覧で書かれた（外した端末に包んでいるかもしれない）。正直なサーバーが窓を狭めるだけ（D-2）
            header.headSeq < current.headSeq -> "stale_head"
            doc.recipients.size > MAX_RECIPIENTS -> "too_many_recipients"
            else -> null
        }
    }

    private fun commandFrom(
        sender: String,
        current: VerifiedChain,
        doc: SealedDoc,
    ): Boolean = doc.header.writerId == sender && doc.header.kind == DocKind.Command && doc.header.householdId == current.householdId

    private fun storedOf(kind: StateKind): StoredDoc? = if (kind == StateKind.Cursor) persisted.cursor else persisted.queue

    private fun stateOf(kind: StateKind): StoredState? =
        storedOf(kind)?.let { StoredState(kind, it.version, it.doc, persisted.storeId, it.receivedAtServerMs, nowMs(), persisted.activeDeviceId) }

    /** 書いてから入れ替える。書けなければ例外（メモリも変えない）。 */
    private fun save(next: Persisted) {
        write(next)
        persisted = next
    }

    private fun write(next: Persisted) {
        val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(Persisted.serializer(), next).encodeToByteArray())
            // 返事をした書き込みを、電源が落ちても失わない
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PENDING_MS = 10_000L
        const val MAX_RECIPIENTS = 32
    }
}
