package xyz.mooma0750.wave.handoff.v2

import kotlinx.serialization.Serializable

/** 端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。 */
@Serializable
data class ClientPersisted(
    val chain: List<ChainRecord> = emptyList(),
    val storeId: String? = null,
    val cursorFloor: Long = 0,
    val queueFloor: Long = 0,
    val retreated: Boolean = false,
    val handledClaimIds: List<String> = emptyList(),
)

sealed interface ClientEvent {
    /** ほかの端末の再生で、この端末が止まった。 */
    data class Stopped(
        val by: String,
    ) : ClientEvent

    /** ほかの端末の状態が変わった（表示）。accountMatches が false なら、別のアカウントの端末（表示だけ）。 */
    data class RemoteChanged(
        val writer: String,
        val cursor: CursorBody,
        val accountMatches: Boolean,
    ) : ClientEvent

    data object ChainForked : ClientEvent

    data object ChainRolledBack : ClientEvent

    /** サーバーの状態が作り直された。acceptStoreReset を呼ぶまで、その状態で止まらない・取り込まない（L-12）。 */
    data class StoreReset(
        val newStoreId: String,
    ) : ClientEvent

    /** 同じ書き手・同じ版で中身が違う状態を見た（サーバーの不正か不具合）。 */
    data object StateEquivocation : ClientEvent

    data class TransferFailed(
        val reason: String,
    ) : ClientEvent

    data class TransferCompleted(
        val from: String,
    ) : ClientEvent

    data class ServerError(
        val code: String,
    ) : ClientEvent
}

/**
 * Connect v2 の端末側（Phase 6b、D-2・D-6〜D-9）。受信・画面の操作・時間の経過（tick）を1本ずつ呼ぶこと（スレッドを跨いで同時に呼ばない）。
 * 暗号と署名はここで行い、再生そのものは Effects に任せる。端末の時計は順序の判断に使わない（L-10）。
 */
class ConnectClient(
    private val signer: DeviceSigner,
    private val opener: KxOpener,
    private val role: Role,
    private val send: (RelayV2) -> Unit,
    private val effects: Effects,
    /** 単調時計（ミリ秒）。transfer の3秒と外挿の足し算にだけ使う。 */
    private val nowMs: () -> Long,
    /** 表示用の時刻（startedAtEpochMs）。 */
    private val epochMs: () -> Long,
    private val newId: () -> String,
    initial: ClientPersisted = ClientPersisted(),
) {
    interface Effects {
        fun pause()

        /** 取り込んで positionMs から再生を始める。受け付けなければ false（画面が前面でない・ログインが変わった等）。 */
        fun adopt(
            snapshot: String,
            cursor: CursorBody,
            positionMs: Long,
        ): Boolean

        fun event(event: ClientEvent)

        fun persist(state: ClientPersisted)

        /** 今のログインの accountTag（Verified でなければ null、ADR-0007・L-11）。 */
        fun accountTag(): String?
    }

    private class Opened<T>(
        val stored: StoredState,
        val body: T,
        val hash: String,
        val receivedAtLocalMs: Long,
    ) {
        val writer: String get() = stored.doc.header.writerId
        val version: Long get() = stored.version
    }

    private class Inflight(
        val kind: StateKind,
        val expectVersion: Long,
        val doc: SealedDoc,
        val hash: String,
        val cursor: CursorBody?,
        val queue: QueueBody?,
    )

    private class Transfer(
        val claimId: String,
        val basedOnVersion: Long,
        val previousWriter: String,
        val startedAtMs: Long,
        var retried: Boolean = false,
        var adopting: Boolean = false,
    )

    private var records: List<ChainRecord> = initial.chain
    private var chain: VerifiedChain? = (MemberChain.verify(records) as? ChainVerification.Ok)?.chain
    private var storeId: String? = initial.storeId
    private var pendingStoreId: String? = null
    private var cursorFloor = initial.cursorFloor
    private var queueFloor = initial.queueFloor
    private var retreated = initial.retreated
    private val handledClaims = ArrayDeque(initial.handledClaimIds)

    private var latestCursor: Opened<CursorBody>? = null
    private var latestQueue: Opened<QueueBody>? = null
    private val deferred = ArrayList<StoredState>()
    private var serverActive: String? = null
    private var pendingOwner: String? = null

    // 手元の再生
    private var localQueue: String? = null
    private var localQueueRevision = 0L
    private var local: LocalCursor? = null
    private var session: PlaybackArbiter.Session? = null
    private var deviceSeq = 0L
    private var lastOwnCursorHash: String? = null
    private val inflight = HashMap<String, Inflight>()
    private var dirtyCursor = false
    private var dirtyQueue = false
    private var transfer: Transfer? = null
    private var reclaimAfterState = false

    /** 手元の再生の、カーソルに入れる値。 */
    data class LocalCursor(
        val currentItemId: String,
        val positionMs: Long,
        val playing: Boolean,
        val repeat: xyz.mooma0750.wave.handoff.HandoffRepeat,
        val listenedMs: Long,
        val historySent: Boolean,
    )

    val isWriter: Boolean get() = session != null

    /** 検証した家の ID（アカウントタグの計算に使う）。家に入っていなければ null。 */
    val householdId: String? get() = chain?.householdId

    /** 検証した端末一覧（表示）。 */
    val members: List<MemberKeys> get() = chain?.members?.values?.map { it.keys }.orEmpty()

    /** 検証した一覧の頭（加入の Confirm に入れる）。 */
    val head: MemberChain.Head? get() = chain?.let { MemberChain.Head(it.headSeq, it.headHash) }

    val knownStoreId: String? get() = storeId

    /** サーバーの状態が作り直されたと気づき、本人の確認を待っている（L-12）。 */
    val storeResetPending: Boolean get() = pendingStoreId != null

    val floors: Pair<Long, Long> get() = cursorFloor to queueFloor

    /** 加入の手順で、家の鎖を使う（A が B を加えるときの add の署名に使う）。 */
    fun verifiedChain(): VerifiedChain? = chain

    /** 加入の Confirm で受け取った床（D-3）。6桁の照合で確かめた相手（inviter）から、認証された通信路で届いた値。 */
    @Serializable
    data class JoinInfo(
        val householdId: String,
        val headSeq: Long,
        val headHash: String,
        val storeId: String,
        val cursorFloor: Long,
        val queueFloor: Long,
    )

    private var expectedJoin: Pair<JoinInfo, String>? = null

    /** A 側: Confirm に入れる床（家に入っていなければ null）。 */
    fun joinInfo(): JoinInfo? {
        val current = chain ?: return null
        val store = storeId ?: return null
        return JoinInfo(current.householdId, current.headSeq, current.headHash, store, cursorFloor, queueFloor)
    }

    /**
     * B 側: 加入を待つ。次に受ける鎖が、この家の ID・頭を含み、その後に inviter の署名で自分を加える記録があるときだけ受け入れる。
     * storeId と版の床もここで決まる（最初に受けた welcome の storeId を無条件に信じない）。
     */
    fun expectJoin(
        info: JoinInfo,
        inviterDeviceId: String,
    ) {
        if (chain != null) return
        expectedJoin = info to inviterDeviceId
        storeId = info.storeId
        cursorFloor = info.cursorFloor
        queueFloor = info.queueFloor
        persist()
    }
    val activeDeviceId: String? get() = serverActive
    val hasRetreated: Boolean get() = retreated

    /** 表示用の最新のカーソル（別アカウントのものを含む）。 */
    fun latestCursorBody(): Pair<String, CursorBody>? = latestCursor?.let { it.writer to it.body }

    // --- 受信 ---

    fun onMessage(message: RelayV2) {
        when (message) {
            is RelayV2.Welcome -> onWelcome(message)
            is RelayV2.Chain -> onChain(message.records)
            // 自分の追記の返事（chain_ok）は、他の端末への chain_changed を受けないので、同じく取り直す（宛先を書き直すため）
            is RelayV2.ChainChanged -> onHeadAnnounced(message.headSeq, message.headHash)
            is RelayV2.ChainOk -> onHeadAnnounced(message.headSeq, message.headHash)
            is RelayV2.State -> onState(message.state)
            is RelayV2.StateOk -> onStateOk(message)
            is RelayV2.StateConflict -> onStateConflict(message)
            is RelayV2.Error -> onError(message)
            is RelayV2.YieldDelivery -> onYield(message)
            is RelayV2.ActiveChanged -> {
                serverActive = message.activeDeviceId
                pendingOwner = message.pendingOwner
            }
            is RelayV2.PendingExpired ->
                if (transfer?.claimId == message.claimId) {
                    transfer = null
                    effects.event(ClientEvent.TransferFailed("expired"))
                }
            else -> Unit
        }
    }

    private fun onHeadAnnounced(
        headSeq: Long,
        headHash: String,
    ) {
        val current = chain
        if (current == null || headSeq > current.headSeq) {
            send(RelayV2.ChainGet(records.size.toLong()))
        } else if (current.hashAt(headSeq) != headHash) {
            effects.event(ClientEvent.ChainForked)
        }
    }

    private fun onWelcome(welcome: RelayV2.Welcome) {
        serverActive = welcome.activeDeviceId
        val known = storeId
        if (known == null) {
            storeId = welcome.storeId
            persist()
        } else if (known != welcome.storeId) {
            pendingStoreId = welcome.storeId
            effects.event(ClientEvent.StoreReset(welcome.storeId))
        }
        val current = chain
        val headSeq = welcome.headSeq
        if (headSeq != null && (current == null || headSeq > current.headSeq)) {
            send(RelayV2.ChainGet(records.size.toLong()))
        } else if (headSeq != null && current != null && current.hashAt(headSeq) != welcome.headHash) {
            effects.event(ClientEvent.ChainForked)
        }
    }

    /** 本人が「この状態から始める」を押した（L-12）。床を捨てて新しい storeId を受け入れる。 */
    fun acceptStoreReset() {
        val next = pendingStoreId ?: return
        storeId = next
        pendingStoreId = null
        cursorFloor = 0
        queueFloor = 0
        latestCursor = null
        latestQueue = null
        persist()
        send(RelayV2.StateGet)
    }

    private fun onChain(newRecords: List<ChainRecord>) {
        val candidate = records + newRecords
        val join = expectedJoin
        val trusted = chain?.let { MemberChain.Head(it.headSeq, it.headHash) } ?: join?.let { MemberChain.Head(it.first.headSeq, it.first.headHash) }
        when (val result = MemberChain.verify(candidate, trusted)) {
            is ChainVerification.Ok -> {
                if (join != null && chain == null) {
                    // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
                    val added = candidate.drop((join.first.headSeq + 1).toInt()).any { it.type == ChainType.Add && it.target?.deviceId == signer.deviceId && it.signerId == join.second }
                    if (result.chain.householdId != join.first.householdId) {
                        effects.event(ClientEvent.ChainForked)
                        return
                    }
                    // まだ加えられていない（A の追記の前に取った）: 受け入れずに次の chain_changed を待つ
                    if (!added) return
                    expectedJoin = null
                }
                val headChanged = result.chain.headHash != chain?.headHash
                records = candidate
                chain = result.chain
                persist()
                // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
                if (headChanged && isWriter) {
                    dirtyCursor = true
                    dirtyQueue = localQueue != null
                    flush()
                }
                val pending = deferred.toList()
                deferred.clear()
                pending.forEach(::onState)
            }
            ChainVerification.Fork -> effects.event(ClientEvent.ChainForked)
            ChainVerification.Rollback -> effects.event(ClientEvent.ChainRolledBack)
            is ChainVerification.Invalid -> effects.event(ClientEvent.ChainForked)
        }
    }

    private fun onState(stored: StoredState) {
        if (pendingStoreId != null && stored.storeId == pendingStoreId) return
        if (stored.storeId != storeId) return
        val current = chain ?: return run { deferred += stored }
        val opened =
            when (val result = DocSealer.open(stored.doc, current, opener)) {
                is DocOpen.Ok -> result
                is DocOpen.NeedChain -> {
                    deferred += stored
                    send(RelayV2.ChainGet(records.size.toLong()))
                    return
                }
                DocOpen.Fork -> return effects.event(ClientEvent.ChainForked)
                is DocOpen.Rejected -> return
            }
        if (opened.header.version != stored.version) return
        val hash = DocSealer.hash(stored.doc)
        when (stored.kind) {
            StateKind.Cursor -> {
                if (stored.version < cursorFloor) return
                val existing = latestCursor
                if (existing != null && existing.version == stored.version) {
                    if (existing.hash != hash && existing.writer == stored.doc.header.writerId) effects.event(ClientEvent.StateEquivocation)
                    return
                }
                if (existing != null && existing.version > stored.version) return
                val body = runCatching { ConnectJson.json.decodeFromString(CursorBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() ?: return
                latestCursor = Opened(stored, body, hash, nowMs())
                cursorFloor = stored.version
                persist()
                onCursor(latestCursor!!)
            }
            StateKind.Queue -> {
                if (stored.version < queueFloor) return
                if ((latestQueue?.version ?: -1) >= stored.version) return
                val body = runCatching { ConnectJson.json.decodeFromString(QueueBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() ?: return
                latestQueue = Opened(stored, body, hash, nowMs())
                queueFloor = stored.version
                persist()
            }
        }
        if (reclaimAfterState && isWriter) {
            reclaimAfterState = false
            claimAgain()
        }
    }

    private fun onCursor(cursor: Opened<CursorBody>) {
        if (cursor.writer == signer.deviceId) return
        val accountMatches = cursor.body.accountTag == effects.accountTag()
        effects.event(ClientEvent.RemoteChanged(cursor.writer, cursor.body, accountMatches))
        val waiting = transfer
        if (waiting != null && !waiting.adopting && cursor.writer == waiting.previousWriter && !cursor.body.playing) {
            // 前の再生中の端末が最後のカーソル（一時停止）を置いた → すぐ取り込む
            completeTransfer()
            return
        }
        if (!accountMatches || !cursor.body.playing) return
        decide(PlaybackArbiter.Evidence.PlayingCursor(cursor.writer, cursor.body.sessionBase, cursor.body.sessionStartVersion))
    }

    private fun onYield(delivery: RelayV2.YieldDelivery) {
        val current = chain ?: return
        val opened = DocSealer.open(delivery.yieldDoc, current, opener) as? DocOpen.Ok ?: return
        val command = runCatching { ConnectJson.json.decodeFromString(CommandBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() as? CommandBody.Yield ?: return
        if (command.to != signer.deviceId || command.from != opened.header.writerId || command.claimId != delivery.claimId) return
        if (command.claimId in handledClaims) return
        remember(command.claimId)
        // 別のアカウントの端末の再生では止まらない（L-11）
        if (command.accountTag != effects.accountTag()) return
        val wasWriter = isWriter
        val stopped = decide(PlaybackArbiter.Evidence.Yield(command.from, command.sessionBase))
        // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
        if (stopped && wasWriter && pendingOwner == command.from) sendYielded(command.claimId)
    }

    /** @return 書き手でなくなったか */
    private fun decide(evidence: PlaybackArbiter.Evidence): Boolean {
        val mine = session ?: return false
        val result = PlaybackArbiter.decide(signer.deviceId, mine, evidence, serverActive)
        if (result.decision != PlaybackArbiter.Decision.Stop) return false
        val wasPlaying = local?.playing == true
        session = null
        retreated = true
        persist()
        // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
        if (wasPlaying) {
            effects.pause()
            local = local?.copy(playing = false)
            effects.event(ClientEvent.Stopped(evidence.from))
        }
        return true
    }

    private fun sendYielded(claimId: String) {
        val current = chain ?: return
        val body = cursorBody(sessionOverride = null) ?: return
        val expect = maxOf(latestCursor?.version ?: 0, cursorFloor)
        val doc = DocSealer.seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.json.encodeToString(CursorBody.serializer(), body).encodeToByteArray())
        send(RelayV2.Yielded(claimId, expect, doc))
    }

    private fun onStateOk(ok: RelayV2.StateOk) {
        val sent = inflight.remove(ok.requestId)
        if (sent == null) return
        val stored = StoredState(sent.kind, ok.version, sent.doc, storeId.orEmpty(), 0, 0, serverActive)
        when (ok.kind) {
            StateKind.Cursor -> {
                sent.cursor?.let { latestCursor = Opened(stored, it, sent.hash, nowMs()) }
                cursorFloor = maxOf(cursorFloor, ok.version)
                val mine = session
                if (mine != null && mine.startVersion == null) session = mine.copy(startVersion = ok.version)
                if (transfer?.adopting == true) {
                    val from = transfer!!.previousWriter
                    transfer = null
                    effects.event(ClientEvent.TransferCompleted(from))
                }
            }
            StateKind.Queue -> {
                sent.queue?.let { latestQueue = Opened(stored, it, sent.hash, nowMs()) }
                queueFloor = maxOf(queueFloor, ok.version)
            }
        }
        persist()
        flush()
    }

    /** 接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。 */
    fun onDisconnected() {
        inflight.values.forEach { if (it.kind == StateKind.Cursor) dirtyCursor = true else dirtyQueue = true }
        inflight.clear()
        serverActive = null
        pendingOwner = null
    }

    private fun onStateConflict(conflict: RelayV2.StateConflict) {
        val waiting = transfer
        if (waiting != null && conflict.requestId == waiting.claimId) {
            conflict.latest?.let(::onState)
            if (!waiting.retried && transfer === waiting) {
                transfer = null
                requestTransfer(retried = true)
            } else {
                transfer = null
                effects.event(ClientEvent.TransferFailed("conflict"))
            }
            return
        }
        val sent = inflight[conflict.requestId] ?: return
        val latest = conflict.latest
        // 返事が落ちて送り直した自分の書き込みと同じものがサーバーにあるなら、成功とみなす
        if (latest != null && latest.doc.header.writerId == signer.deviceId && DocSealer.hash(latest.doc) == sent.hash) {
            onStateOk(RelayV2.StateOk(conflict.requestId, sent.kind, latest.version))
            return
        }
        inflight.remove(conflict.requestId)
        latest?.let(::onState)
        // 止まっていなければ、最新の版に積み直す
        if (isWriter) {
            if (sent.kind == StateKind.Cursor) dirtyCursor = true else dirtyQueue = true
            flush()
        }
    }

    private fun onError(error: RelayV2.Error) {
        val sent = error.requestId?.let { inflight.remove(it) }
        when (error.code) {
            // 書き込みを断られた: 最新を取り、止まる理由が無ければ claim し直す（D-7）
            "not_active" ->
                if (sent != null && isWriter) {
                    reclaimAfterState = true
                    send(RelayV2.StateGet)
                }
            "stale_head" -> {
                if (sent != null) {
                    if (sent.kind == StateKind.Cursor) dirtyCursor = true else dirtyQueue = true
                }
                send(RelayV2.ChainGet(records.size.toLong()))
            }
            else -> {
                if (error.requestId != null && error.requestId == transfer?.claimId) {
                    transfer = null
                    effects.event(ClientEvent.TransferFailed(error.code))
                } else {
                    effects.event(ClientEvent.ServerError(error.code))
                }
            }
        }
    }

    // --- 手元の操作 ---

    /** 手元のキューが変わった（HandoffCodec.encode の文字列）。書き手なら置く。 */
    fun setLocalQueue(snapshot: String) {
        localQueue = snapshot
        localQueueRevision++
        if (isWriter) {
            dirtyQueue = true
            flush()
        }
    }

    /** 手元の再生の位置・再生中か等が変わった。書き手なら置く（間引きは呼ぶ側）。 */
    fun setLocalCursor(cursor: LocalCursor) {
        local = cursor
        if (isWriter) {
            dirtyCursor = true
            flush()
        }
    }

    /**
     * 本人が手元で新しく再生を始めた・再開した（L-3）。新しいセッションとして claim し、キューとカーソルを置く。
     * @return claim を送ったか（家に入っていない・Verified でない・controller なら false。呼ぶ側は手元だけで再生する）
     */
    fun userStartedPlayback(): Boolean {
        if (role != Role.Player || chain == null || storeId == null || pendingStoreId != null) return false
        val tag = effects.accountTag() ?: return false
        session = PlaybackArbiter.Session(base = maxOf(latestCursor?.version ?: 0, cursorFloor), startVersion = null)
        retreated = false
        persist()
        sendClaim(tag)
        dirtyQueue = localQueue != null
        dirtyCursor = true
        flush()
        return true
    }

    private fun claimAgain() {
        val tag = effects.accountTag() ?: return
        val mine = session ?: return
        // 最新を見た上で止まらなかったので、見た版を足して始め直す
        session = mine.copy(base = maxOf(mine.base, latestCursor?.version ?: 0), startVersion = null)
        sendClaim(tag)
        dirtyQueue = localQueue != null
        dirtyCursor = true
        flush()
    }

    private fun sendClaim(tag: String) {
        val current = chain ?: return
        val claimId = newId()
        remember(claimId)
        val to = latestCursor?.writer?.takeIf { it != signer.deviceId }.orEmpty()
        val command = CommandBody.Yield(claimId, signer.deviceId, to, session!!.base, tag)
        send(RelayV2.Claim(claimId, sealCommand(current, command)))
    }

    /**
     * 「ここで再生」（D-7）。最新のカーソルとキューを開けていて、アカウントが一致するときだけ送る。
     * 取り込みは、前の端末の最後のカーソルが届いたとき、または3秒後（tick）に行う。
     */
    fun requestTransfer(): Boolean = requestTransfer(retried = false)

    private fun requestTransfer(retried: Boolean): Boolean {
        if (role != Role.Player || transfer != null || pendingStoreId != null) return false
        val current = chain ?: return false
        val tag = effects.accountTag() ?: return false
        val cursor = latestCursor ?: return false
        val queue = latestQueue ?: return false
        if (cursor.writer == signer.deviceId || cursor.body.accountTag != tag || queue.body.accountTag != tag || queue.body.queueRevision != cursor.body.queueRevision) return false
        val claimId = newId()
        remember(claimId)
        transfer = Transfer(claimId, cursor.version, cursor.writer, nowMs(), retried = retried)
        val command = CommandBody.Yield(claimId, signer.deviceId, cursor.writer, cursor.version, tag)
        send(RelayV2.Transfer(claimId, cursor.version, sealCommand(current, command)))
        return true
    }

    /** 時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。 */
    fun tick() {
        val waiting = transfer ?: return
        if (!waiting.adopting && nowMs() - waiting.startedAtMs >= TRANSFER_WAIT_MS) completeTransfer()
    }

    private fun completeTransfer() {
        val waiting = transfer ?: return
        val cursor = latestCursor
        val queue = latestQueue
        if (cursor == null || queue == null || queue.body.queueRevision != cursor.body.queueRevision) {
            transfer = null
            send(RelayV2.Release(waiting.claimId))
            effects.event(ClientEvent.TransferFailed("no_state"))
            return
        }
        val position = extrapolatedPosition(cursor)
        waiting.adopting = true
        if (!effects.adopt(queue.body.snapshot, cursor.body, position)) {
            transfer = null
            send(RelayV2.Release(waiting.claimId))
            effects.event(ClientEvent.TransferFailed("not_adopted"))
            return
        }
        localQueue = queue.body.snapshot
        localQueueRevision = queue.body.queueRevision
        session = PlaybackArbiter.Session(base = cursor.version, startVersion = null)
        retreated = false
        local = LocalCursor(cursor.body.currentItemId, position, true, cursor.body.repeat, cursor.body.listenedMs, cursor.body.historySent)
        persist()
        // B の最初のカーソルで再生中が確定する（D-7）
        dirtyCursor = true
        flush()
    }

    /** 経過はサーバーの時計で測り、手元で受けてからの時間を足す。9分を超える経過は一時停止として扱う（D-5）。 */
    private fun extrapolatedPosition(cursor: Opened<CursorBody>): Long {
        if (!cursor.body.playing) return cursor.body.positionMs
        val elapsed = (cursor.stored.serverNowMs - cursor.stored.receivedAtServerMs) + (nowMs() - cursor.receivedAtLocalMs)
        if (elapsed < 0 || elapsed > MAX_EXTRAPOLATION_MS) return cursor.body.positionMs
        return cursor.body.positionMs + elapsed
    }

    // --- 書き込み ---

    private fun flush() {
        val current = chain ?: return
        if (!isWriter) return
        if (dirtyQueue && inflight.values.none { it.kind == StateKind.Queue }) {
            val snapshot = localQueue
            val tag = effects.accountTag()
            if (snapshot != null && tag != null) {
                dirtyQueue = false
                val body = QueueBody(localQueueRevision, tag, snapshot)
                val expect = maxOf(latestQueue?.version ?: 0, queueFloor)
                val doc = DocSealer.seal(signer, current, DocKind.Queue, expect + 1, null, epochMs(), ConnectJson.json.encodeToString(QueueBody.serializer(), body).encodeToByteArray())
                val requestId = newId()
                inflight[requestId] = Inflight(StateKind.Queue, expect, doc, DocSealer.hash(doc), null, body)
                send(RelayV2.StatePut(requestId, StateKind.Queue, expect, doc))
            }
        }
        if (dirtyCursor && inflight.values.none { it.kind == StateKind.Cursor }) {
            val body = cursorBody(session) ?: return
            dirtyCursor = false
            val expect = maxOf(latestCursor?.version ?: 0, cursorFloor)
            val doc = DocSealer.seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.json.encodeToString(CursorBody.serializer(), body).encodeToByteArray())
            val hash = DocSealer.hash(doc)
            lastOwnCursorHash = hash
            val requestId = newId()
            inflight[requestId] = Inflight(StateKind.Cursor, expect, doc, hash, body, null)
            send(RelayV2.StatePut(requestId, StateKind.Cursor, expect, doc))
        }
    }

    private fun cursorBody(sessionOverride: PlaybackArbiter.Session?): CursorBody? {
        val cursor = local ?: return null
        val tag = effects.accountTag() ?: return null
        val mine = sessionOverride
        return CursorBody(
            queueRevision = localQueueRevision,
            currentItemId = cursor.currentItemId,
            positionMs = cursor.positionMs,
            playing = cursor.playing && mine != null,
            repeat = cursor.repeat,
            sessionBase = mine?.base ?: 0,
            sessionStartVersion = mine?.startVersion,
            deviceSeq = ++deviceSeq,
            listenedMs = cursor.listenedMs,
            historySent = cursor.historySent,
            accountTag = tag,
            startedAtEpochMs = epochMs(),
        )
    }

    private fun sealCommand(
        current: VerifiedChain,
        command: CommandBody,
    ): SealedDoc = DocSealer.seal(signer, current, DocKind.Command, 1, null, epochMs(), ConnectJson.json.encodeToString(CommandBody.serializer(), command).encodeToByteArray())

    private fun remember(claimId: String) {
        handledClaims.addLast(claimId)
        while (handledClaims.size > MAX_HANDLED_CLAIMS) handledClaims.removeFirst()
    }

    private fun persist() {
        effects.persist(ClientPersisted(records, storeId, cursorFloor, queueFloor, retreated, handledClaims.toList()))
    }

    companion object {
        const val TRANSFER_WAIT_MS = 3_000L
        const val MAX_EXTRAPOLATION_MS = 9 * 60 * 1000L
        private const val MAX_HANDLED_CLAIMS = 64
    }
}
