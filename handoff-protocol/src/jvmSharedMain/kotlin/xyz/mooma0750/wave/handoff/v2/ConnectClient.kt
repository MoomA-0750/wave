package xyz.mooma0750.wave.handoff.v2

import kotlinx.serialization.Serializable
import xyz.mooma0750.wave.handoff.crypto.Hpke

/** 端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。 */
@Serializable
data class ClientPersisted(
    val chain: List<ChainRecord> = emptyList(),
    val storeId: String? = null,
    val cursorFloor: Long = 0,
    val queueFloor: Long = 0,
    val retreated: Boolean = false,
    val handledClaimIds: List<String> = emptyList(),
    /** 加入を待っている（Confirm の床と招いた端末）。プロセスが落ちても、無条件に最初の鎖を信じない（レビュー p6b34 高-6）。 */
    val expectedJoin: ConnectClient.JoinInfo? = null,
    val expectedInviter: String? = null,
    /** 家を作ると押した（最初の鎖が自分の genesis なら受け入れる）。 */
    val creatingHousehold: Boolean = false,
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

    /** 中継が、検証した一覧と同じ seq で別の記録を見せた。 */
    data object ChainForked : ClientEvent

    data object ChainRolledBack : ClientEvent

    /** 受けた記録が検証を通らなかった（改ざん・不正な追記）。 */
    data class ChainInvalid(
        val reason: String,
    ) : ClientEvent

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

    /** 家に入った（加入の鎖を受け入れた、または自分の genesis の鎖を受け入れた）。 */
    data object Joined : ClientEvent

    /** 家の端末が表示名を知らせてきた（表示だけ。署名と家の一覧で確かめた送り主）。 */
    data class Profile(
        val deviceId: String,
        val name: String,
        val kind: String? = null,
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
    /** 中継へ送る。つながっていなくて送れなければ false。 */
    private val send: (RelayV2) -> Boolean,
    private val effects: Effects,
    /** 単調時計（ミリ秒）。transfer の3秒と外挿の足し算にだけ使う。 */
    private val nowMs: () -> Long,
    /** 表示用の時刻（startedAtEpochMs）。 */
    private val epochMs: () -> Long,
    private val newId: () -> String,
    initial: ClientPersisted = ClientPersisted(),
) {
    interface Effects {
        /** 一時停止し、止めた時点の手元の再生を返す（最後のカーソルの位置を今に合わせる、レビュー p6b34 高-3）。 */
        fun pause(): LocalCursor?

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

        /** 遠隔操作に従う（この端末が鳴らしている）。受け付けなければ false。 */
        fun control(
            action: ControlAction,
            positionMs: Long?,
        ): Boolean = false

        /** ほかの端末と合わせたライブラリが届いた（手元に取り込む、ADR-0013）。 */
        fun libraryChanged(body: LibraryBody) {}
    }

    /** 表示用: ほかの端末の最新のカーソルと、今の時刻に合わせた位置。 */
    data class RemoteNow(
        val writer: String,
        val cursor: CursorBody,
        val positionMs: Long,
    )

    /** 手元の再生の、カーソルに入れる値。 */
    data class LocalCursor(
        val currentItemId: String,
        val positionMs: Long,
        val playing: Boolean,
        val repeat: xyz.mooma0750.wave.handoff.HandoffRepeat,
        val listenedMs: Long,
        val historySent: Boolean,
        /** positionMs を取った時刻（nowMs と同じ単調時計）。再生中なら、書くときに経過を足す。 */
        val capturedAtMs: Long,
    )

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
        val doc: SealedDoc,
        val hash: String,
        val cursor: CursorBody?,
        val queue: QueueBody?,
        /** 送ったときのセッション（前のセッションの返事で、新しいセッションの startVersion を決めない）。 */
        val sessionId: Long,
    )

    private class Transfer(
        val claimId: String,
        val previousWriter: String,
        val startedAtMs: Long,
        val retried: Boolean,
        var adopting: Boolean = false,
    )

    private class Session(
        val id: Long,
        var arbiter: PlaybackArbiter.Session,
        var claimId: String?,
        var claimOrder: Long? = null,
    )

    private var records: List<ChainRecord> = initial.chain
    private var chain: VerifiedChain? = (MemberChain.verify(records) as? ChainVerification.Ok)?.chain
    private var storeId: String? = initial.storeId
    private var pendingStoreId: String? = null
    private var cursorFloor = initial.cursorFloor
    private var queueFloor = initial.queueFloor
    private var retreated = initial.retreated
    private val handledClaims = ArrayDeque(initial.handledClaimIds)

    /** 開いた遠隔操作の doc のハッシュ（同じ doc の再送を開く前に捨てる）。 */
    private val seenControlDocs = ArrayDeque<String>()
    private var expectedJoin: Pair<JoinInfo, String>? = initial.expectedJoin?.let { info -> initial.expectedInviter?.let { info to it } }
    private var creatingHousehold = initial.creatingHousehold

    /** タグ（Verified のアカウント）が無い間に本人が再生を始めた。タグができたら claim する（レビュー p6b34 高-4）。 */
    private var startWaitingForTag = false

    private var latestCursor: Opened<CursorBody>? = null
    private var latestQueue: Opened<QueueBody>? = null
    private val deferred = ArrayList<StoredState>()
    private var serverActive: String? = null
    private var pendingOwner: String? = null
    private val claimOrders = HashMap<String, Long>()

    /** 中継につながっている家の端末（中継の申告。表示と D-9 の判断だけに使い、止まる根拠にしない）。 */
    private var online: Set<String> = emptySet()

    /** 今の接続で welcome を処理した（切れたら false。メモリに残った古いカーソルを最新とみなさない、再レビュー 高-A）。 */
    private var welcomed = false

    /** 同時に始めた相手の証拠で、claimOrder を待って保留しているもの（D-8）。 */
    private val deferredEvidence = ArrayList<PlaybackArbiter.Evidence>()

    // 手元の再生
    private var localQueue: String? = null
    private var localQueueRef: String? = null
    private var local: LocalCursor? = null
    private var session: Session? = null
    private var sessionCounter = 0L
    private var deviceSeq = 0L
    private var lastOwnCursorHash: String? = null
    private val inflight = HashMap<String, Inflight>()
    private var dirtyCursor = false
    private var dirtyQueue = false
    private var transfer: Transfer? = null

    /** つながり直した後、最新の状態と鎖をそろえてから書き込みを再開する（レビュー p6b12 高-2）。 */
    private var resumePending = false

    val isWriter: Boolean get() = session != null
    val activeDeviceId: String? get() = serverActive
    val hasRetreated: Boolean get() = retreated

    /** 検証した家の ID（アカウントタグの計算に使う）。家に入っていなければ null。 */
    val householdId: String? get() = chain?.householdId

    /** 検証した端末一覧（表示）。 */
    val members: List<MemberKeys> get() = chain?.members?.values?.map { it.keys }.orEmpty()

    /** サーバーの状態が作り直されたと気づき、本人の確認を待っている（L-12）。 */
    val storeResetPending: Boolean get() = pendingStoreId != null

    /** 加入の手順で、家の鎖を使う（A が B を加えるときの add の署名に使う）。 */
    fun verifiedChain(): VerifiedChain? = chain

    /** 検証した鎖の記録（追記する前に、追記後の鎖が通るかを確かめるのに使う）。家に入っていなければ null。 */
    fun verifiedRecords(): List<ChainRecord>? = if (chain == null) null else records

    /** 表示用の最新のカーソル（別アカウントのものを含む）。 */
    fun latestCursorBody(): Pair<String, CursorBody>? = latestCursor?.let { it.writer to it.body }

    /**
     * 同じアカウントの他の端末が今鳴っているか（D-9 の再生ボタンの判断）。最後のカーソルが再生中で、その端末が中継につながっているとき。
     * 落ちた・通信を失った端末の最後の「再生中」で、再生ボタンを止め続けない（レビュー p6b34 中-2）。通信を失ったまま鳴っている端末は、つながり直したら止まる（L-4）
     */
    /** 今の接続で最新の状態をそろえた（welcome を処理し、鎖の取り直しを待っている状態が無い）。D-9 の判断はこれが true のときだけ行う。 */
    val stateSettled: Boolean get() = welcomed && deferred.isEmpty()

    fun remotePlayingNow(): Boolean {
        val cursor = latestCursor ?: return false
        val tag = effects.accountTag() ?: return false
        return cursor.writer != signer.deviceId && cursor.body.playing && cursor.body.accountTag == tag && cursor.writer in online
    }

    /** 表示用: 最新のキューの doc の中身（別アカウントのものを含む）。 */
    fun latestQueueBody(): Pair<String, QueueBody>? = latestQueue?.let { it.writer to it.body }

    /** 表示用: 最新のキューの doc のハッシュ（カーソルの queueRef と照らす）。 */
    fun latestQueueHash(): String? = latestQueue?.hash

    /** 表示用: 中継につながっている家の端末（中継の申告）。 */
    fun onlineDevices(): Set<String> = online

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
        creatingHousehold = false
        storeId = info.storeId
        cursorFloor = info.cursorFloor
        queueFloor = info.queueFloor
        persist()
    }

    // --- 受信 ---

    /** ライブラリの同期（ADR-0013）。手元が変わったら library.publish を呼ぶ。 */
    val library: LibrarySync by lazy {
        LibrarySync(signer, opener, send, { chain }, { storeId.takeIf { pendingStoreId == null } }, epochMs, newId) { effects.libraryChanged(it) }
    }

    fun onMessage(message: RelayV2) {
        when (message) {
            is RelayV2.Library, is RelayV2.LibraryOk, is RelayV2.LibraryConflict -> library.onMessage(message)
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
            is RelayV2.Control -> onControl(message)
            is RelayV2.ActiveChanged -> onActiveChanged(message)
            is RelayV2.PendingExpired -> onPendingExpired(message.claimId)
            is RelayV2.Presence -> online = message.online.toSet()
            else -> Unit
        }
    }

    /** 接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。 */
    fun onDisconnected() {
        resetConnection()
        library.onDisconnected()
        // 取り込む前に切れた引き取りは捨てる（つながり直した後に、古い状態から突然取り込まない。レビュー p6b34 中-4）
        val waiting = transfer
        if (waiting != null && !waiting.adopting) {
            failTransfer("disconnected")
        }
    }

    private fun resetConnection() {
        inflight.values.forEach { markUnsent(it.kind) }
        inflight.clear()
        serverActive = null
        pendingOwner = null
        online = emptySet()
        welcomed = false
    }

    /** 家を作ると押した。次に受ける最初の鎖が自分の genesis なら受け入れる。 */
    fun beginCreateHousehold() {
        if (chain != null) return
        creatingHousehold = true
        // 途中でやめた加入の待ちを残さない（自分の genesis を分岐として捨てない、再レビュー 低-L）
        expectedJoin = null
        persist()
    }

    /** 加入の待ちをやめた（時間切れ・取り下げ）。後でつながったときに、黙って家に入らない（再レビュー 低-L）。 */
    fun cancelJoin() {
        if (chain != null || expectedJoin == null) return
        expectedJoin = null
        persist()
    }

    /** ログアウト・別のログインに変わった: 手元のキュー・再生・セッションを捨てる（前のアカウントのキューを新しいタグで書かない、レビュー p6b34 高-5）。 */
    fun clearLocal() {
        session = null
        localQueue = null
        localQueueRef = null
        local = null
        lastOwnCursorHash = null
        dirtyCursor = false
        dirtyQueue = false
        inflight.clear()
        deferredEvidence.clear()
        startWaitingForTag = false
        resumePending = false
        val waiting = transfer
        transfer = null
        if (waiting != null) send(RelayV2.Release(waiting.claimId))
    }

    /**
     * 手元の再生が無くなった（ログインの世代が変わった・キューが空になった）。書き手なら、finalTag（その再生のアカウントのタグ）で
     * 一時停止のカーソルを1回だけ送ってから、手元を捨てる（中継に「再生中」を残して、同じアカウントの他の端末の再生ボタンを止め続けない、再レビュー 中-D）。
     * 返事は待たない（版が行き違えば書けないが、次に鳴らした端末が上書きする）。
     */
    fun endLocal(finalTag: String?) {
        val current = chain
        val mine = session
        val cursor = local
        val queueRef = localQueueRef
        if (current != null && mine != null && cursor != null && queueRef != null && finalTag != null && pendingStoreId == null) {
            val body = cursorBody(null, finalTag)?.copy(playing = false)
            if (body != null) {
                val expect = maxOf(latestCursor?.version ?: 0, cursorFloor)
                val doc = DocSealer.seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.json.encodeToString(CursorBody.serializer(), body).encodeToByteArray())
                send(RelayV2.StatePut(newId(), StateKind.Cursor, expect, doc))
            }
        }
        clearLocal()
    }

    /** アカウントタグができた（Verified になった）。タグが無い間に始めた再生が続いていれば claim する。 */
    fun onAccountTagAvailable() {
        if (!startWaitingForTag) return
        startWaitingForTag = false
        if (local?.playing == true && session == null) userStartedPlayback()
    }

    private fun onHeadAnnounced(
        headSeq: Long,
        headHash: String?,
    ) {
        val current = chain
        if (current == null || headSeq > current.headSeq) {
            send(RelayV2.ChainGet(records.size.toLong()))
        } else if (current.hashAt(headSeq) != headHash) {
            effects.event(ClientEvent.ChainForked)
        }
    }

    private fun onWelcome(welcome: RelayV2.Welcome) {
        // 前の接続の書き込みは届いていないかもしれない（返事を待っていたものは積み直す）。
        // transfer は捨てない（Connected にしてから welcome を処理するまでに送った「ここで再生」を捨てない、再レビュー 低-I。本当に切れたときは onDisconnected が捨てる）
        resetConnection()
        welcomed = true
        serverActive = welcome.activeDeviceId
        online = welcome.online.toSet()
        claimOrders.putAll(welcome.claimOrders)
        val known = storeId
        if (known == null) {
            storeId = welcome.storeId
            persist()
        } else if (known != welcome.storeId) {
            pendingStoreId = welcome.storeId
            effects.event(ClientEvent.StoreReset(welcome.storeId))
        }
        resumePending = isWriter
        welcome.headSeq?.let { onHeadAnnounced(it, welcome.headHash) }
        // 最新の状態を先に見る（通信を失ったまま鳴っていた端末は、ここで止まる）
        welcome.states.forEach(::onState)
        maybeResume()
        library.onConnected()
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
        // 書き手なら、新しい保存に最初から書き直す（前の版のセッションのままにしない）
        session?.let {
            it.arbiter = PlaybackArbiter.Session(base = 0, startVersion = null)
            sendClaimFor(it)
            markDirty()
        }
        send(RelayV2.StateGet)
        flush()
        library.onConnected()
    }

    private fun onChain(newRecords: List<ChainRecord>) {
        // 取得が重なって同じ記録が2回届いても、分岐にしない（seq で並べ、既に持っている分はハッシュを比べる）
        val fresh = ArrayList<ChainRecord>()
        for (record in newRecords) {
            val index = record.seq.toInt()
            when {
                index < 0 -> return effects.event(ClientEvent.ChainInvalid("seq"))
                index < records.size -> if (MemberChain.hash(record) != MemberChain.hash(records[index])) return effects.event(ClientEvent.ChainForked)
                index == records.size + fresh.size -> fresh += record
                else -> return effects.event(ClientEvent.ChainInvalid("gap"))
            }
        }
        if (fresh.isEmpty()) {
            flush()
            return
        }
        val candidate = records + fresh
        val join = expectedJoin
        val trusted = chain?.let { MemberChain.Head(it.headSeq, it.headHash) } ?: join?.let { MemberChain.Head(it.first.headSeq, it.first.headHash) }
        when (val result = MemberChain.verify(candidate, trusted)) {
            is ChainVerification.Ok -> {
                val joining = chain == null
                if (joining) {
                    when {
                        join != null -> {
                            // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
                            if (result.chain.householdId != join.first.householdId) return effects.event(ClientEvent.ChainForked)
                            // 招いた端末が自分を加えた記録があるか、6桁で確かめた A の頭の時点で自分が既に同じ鍵でいる
                            // （保存を作り直した端末が加わり直す。頭より後に誰かが加えた記録では入らない、再レビュー 中-F）
                            val existing = result.chain.members[signer.deviceId]
                            val added =
                                candidate.drop((join.first.headSeq + 1).toInt()).any { it.type == ChainType.Add && it.target?.deviceId == signer.deviceId && it.signerId == join.second } ||
                                    (existing != null && existing.addedSeq <= join.first.headSeq && Hpke.serializePublicKey(existing.kx).contentEquals(Hpke.serializePublicKey(opener.kxPub)))
                            // まだ加えられていない（A の追記の前に取った）: 受け入れずに次の chain_changed を待つ
                            if (!added) return
                            expectedJoin = null
                        }
                        // 家を作ると押した端末: 最初の記録が自分の genesis であること
                        creatingHousehold && candidate.first().target?.deviceId == signer.deviceId && candidate.first().type == ChainType.Genesis -> creatingHousehold = false
                        // 加入も家を作るのも待っていない: 中継が勝手に作った家を信じない（D-12）
                        else -> return effects.event(ClientEvent.ChainInvalid("unexpected_household"))
                    }
                }
                val headChanged = result.chain.headHash != chain?.headHash
                records = candidate
                chain = result.chain
                persist()
                // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
                if (headChanged && isWriter) markDirty()
                if (joining) effects.event(ClientEvent.Joined)
                val pending = deferred.toList()
                deferred.clear()
                pending.forEach(::onState)
                maybeResume()
                flush()
                // 家に入ったばかりなら、つながったときの library_get は家の外として断られている。頼み直す
                if (joining) library.onConnected() else library.onChainUpdated()
            }
            ChainVerification.Fork -> effects.event(ClientEvent.ChainForked)
            ChainVerification.Rollback -> effects.event(ClientEvent.ChainRolledBack)
            is ChainVerification.Invalid -> effects.event(ClientEvent.ChainInvalid(result.reason))
        }
    }

    private fun onState(stored: StoredState) {
        if (pendingStoreId != null && stored.storeId == pendingStoreId) return
        if (stored.storeId != storeId) return
        val current = chain ?: return run { deferred += stored }
        // 開く前に、開いても捨てるもの（床より古い・持っている版より古い・持っているものと同じ）を除く。
        // 開くたびに受け取りの鍵で鍵交換をする（Pixel 3 では Keystore のソフトウェア実装の枠を使う）ので、つなぎ直しのたびの同じ状態で使わない
        if (alreadyHave(stored)) return
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
        if (opened.header.version != stored.version || opened.header.kind != stored.kind.docKind) return
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
                val opened2 = Opened(stored, body, hash, nowMs())
                latestCursor = opened2
                cursorFloor = stored.version
                persistFloor()
                onCursor(opened2)
            }
            StateKind.Queue -> {
                if (stored.version < queueFloor) return
                if ((latestQueue?.version ?: -1) >= stored.version) return
                val body = runCatching { ConnectJson.json.decodeFromString(QueueBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() ?: return
                latestQueue = Opened(stored, body, hash, nowMs())
                queueFloor = stored.version
                persistFloor()
            }
        }
    }

    /** 開いても捨てるだけの状態か（同じ版で中身が違うものは、署名を確かめて食い違いを知らせるために開く）。 */
    private fun alreadyHave(stored: StoredState): Boolean =
        when (stored.kind) {
            StateKind.Cursor -> {
                val existing = latestCursor
                stored.version < cursorFloor ||
                    (existing != null && (existing.version > stored.version || (existing.version == stored.version && existing.hash == DocSealer.hash(stored.doc))))
            }
            StateKind.Queue -> stored.version < queueFloor || (latestQueue?.version ?: -1) >= stored.version
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
        // yield を出せるのは player だけ（controller の遠隔操作は後、L-5）
        if (current.members[command.from]?.keys?.role != Role.Player) return
        if (command.claimId in handledClaims) return
        remember(command.claimId)
        // 別のアカウントの端末の再生では止まらない（L-11）
        if (command.accountTag != effects.accountTag()) return
        val wasWriter = isWriter
        val stopped = decide(PlaybackArbiter.Evidence.Yield(command.from, command.sessionBase))
        // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
        if (stopped && wasWriter && pendingOwner == command.from) sendYielded(command.claimId)
    }

    private fun onActiveChanged(message: RelayV2.ActiveChanged) {
        serverActive = message.activeDeviceId
        pendingOwner = message.pendingOwner
        val claimer = message.pendingOwner ?: message.activeDeviceId
        if (message.claimId != null && claimer != null) {
            claimOrders[claimer] = message.claimOrder
            session?.let { if (it.claimId == message.claimId) it.claimOrder = message.claimOrder }
        }
        // 順番が分かったので、保留していた証拠で決め直す
        if (deferredEvidence.isNotEmpty()) {
            val pending = deferredEvidence.toList()
            deferredEvidence.clear()
            for (evidence in pending) if (decide(evidence)) break
        }
        // 自分が再生中になったら、断られていた書き込みを送る
        if (message.activeDeviceId == signer.deviceId || message.pendingOwner == signer.deviceId) flush()
    }

    private fun onPendingExpired(claimId: String) {
        val waiting = transfer ?: return
        if (waiting.claimId != claimId) return
        transfer = null
        if (waiting.adopting && isWriter) {
            // 取り込んで鳴っているのに、最初のカーソルが期限に間に合わなかった: 新しく始めたものとして claim し直す（レビュー p6b12 高-3）
            restartSession()
        } else {
            effects.event(ClientEvent.TransferFailed("expired"))
        }
    }

    /** @return 書き手でなくなったか */
    private fun decide(evidence: PlaybackArbiter.Evidence): Boolean {
        val mine = session ?: return false
        val result = PlaybackArbiter.decide(signer.deviceId, mine.arbiter, evidence, mine.claimOrder, claimOrders[evidence.from])
        when (result.decision) {
            PlaybackArbiter.Decision.Keep -> return false
            PlaybackArbiter.Decision.Defer -> {
                deferredEvidence.removeAll { it.from == evidence.from }
                deferredEvidence += evidence
                return false
            }
            PlaybackArbiter.Decision.Stop -> Unit
        }
        val wasPlaying = local?.playing == true
        endSession()
        // 止めた時点の位置を取る（引き取られるときの最後のカーソルを、最後の出来事の位置にしない）
        local = effects.pause() ?: local?.let { it.copy(playing = false, positionMs = currentPosition(it), capturedAtMs = nowMs()) }
        // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
        if (wasPlaying) effects.event(ClientEvent.Stopped(evidence.from))
        return true
    }

    private fun endSession() {
        session = null
        retreated = true
        deferredEvidence.clear()
        dirtyCursor = false
        dirtyQueue = false
        persist()
    }

    private fun sendYielded(claimId: String) {
        val current = chain ?: return
        val body = cursorBody(null) ?: return
        val expect = maxOf(latestCursor?.version ?: 0, cursorFloor)
        val doc = DocSealer.seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.json.encodeToString(CursorBody.serializer(), body).encodeToByteArray())
        send(RelayV2.Yielded(claimId, expect, doc))
    }

    private fun onStateOk(ok: RelayV2.StateOk) {
        val sent = inflight.remove(ok.requestId) ?: return
        val stored = StoredState(sent.kind, ok.version, sent.doc, storeId.orEmpty(), 0, 0, serverActive)
        when (ok.kind) {
            StateKind.Cursor -> {
                sent.cursor?.let { latestCursor = Opened(stored, it, sent.hash, nowMs()) }
                cursorFloor = maxOf(cursorFloor, ok.version)
                val mine = session
                if (mine != null && mine.id == sent.sessionId && mine.arbiter.startVersion == null) {
                    mine.arbiter = mine.arbiter.copy(startVersion = ok.version)
                }
                transfer?.takeIf { it.adopting }?.let { adopted ->
                    transfer = null
                    effects.event(ClientEvent.TransferCompleted(adopted.previousWriter))
                }
            }
            StateKind.Queue -> {
                sent.queue?.let { latestQueue = Opened(stored, it, sent.hash, nowMs()) }
                queueFloor = maxOf(queueFloor, ok.version)
            }
        }
        persist()
        flush()
        maybeHandOff()
    }

    private fun onStateConflict(conflict: RelayV2.StateConflict) {
        val waiting = transfer
        if (waiting != null && conflict.requestId == waiting.claimId) {
            conflict.latest?.let(::onState)
            transfer = null
            if (!waiting.retried) {
                requestTransfer(retried = true)
            } else {
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
            markUnsent(sent.kind)
        }
        flush()
    }

    private fun onError(error: RelayV2.Error) {
        val sent = error.requestId?.let { inflight.remove(it) }
        when (error.code) {
            // 書き込みを断られた: 持っている最新の証拠で止まる理由が無く、自分の claim が一番後（または誰も再生中でない）なら、すぐ claim し直す
            // （新しい状態を待たない、レビュー p6b12 高-3）。他の端末の claim の方が後なら、その端末の証拠を待つ（断られるたびに奪い合わない）
            "not_active" ->
                if (sent != null && isWriter) {
                    markUnsent(sent.kind)
                    val stopped = latestCursor?.takeIf { it.writer != signer.deviceId && it.body.playing && it.body.accountTag == effects.accountTag() }?.let { decide(PlaybackArbiter.Evidence.PlayingCursor(it.writer, it.body.sessionBase, it.body.sessionStartVersion)) } == true
                    val mine = session
                    val latestOther = claimOrders.filterKeys { it != signer.deviceId }.values.maxOrNull()
                    val mineIsLatest = mine?.claimOrder != null && (latestOther == null || mine.claimOrder!! > latestOther)
                    if (!stopped && isWriter && (serverActive == null || mineIsLatest) && mine?.claimOrder != null) restartSession()
                }
            "stale_head" -> {
                if (sent != null && isWriter) {
                    markUnsent(sent.kind)
                }
                send(RelayV2.ChainGet(records.size.toLong()))
            }
            else -> {
                if (error.requestId != null && error.requestId == transfer?.claimId) {
                    failTransfer(error.code)
                } else {
                    effects.event(ClientEvent.ServerError(error.code))
                }
            }
        }
        // 断られた書き込みは、ここで送り直さない（次の状態・active_changed・鎖の取得で送る。すぐ送ると断られ続けて止まらない）
    }

    // --- 手元の操作 ---

    /** 手元のキューが変わった（HandoffCodec.encode の文字列）。書き手なら置く。 */
    fun setLocalQueue(snapshot: String) {
        // 中身が同じなら置き直さない（キューは最大 約210KB、レビュー p6b34 中-1）
        if (snapshot == localQueue) return
        localQueue = snapshot
        if (isWriter) {
            dirtyQueue = true
            // キューを置き直すので、カーソルも新しいキューを指し直す
            dirtyCursor = true
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
     * @return claim を送ったか（家に入っていない・Verified でない・controller・家の再生が別のアカウントなら false。呼ぶ側は手元だけで再生する）
     */
    fun userStartedPlayback(): Boolean {
        if (role != Role.Player || chain == null || storeId == null || pendingStoreId != null) return false
        val tag = effects.accountTag()
        if (tag == null) {
            startWaitingForTag = true
            return false
        }
        // 家の最新の再生が別のアカウントなら、claim しない（書き込みのたびに再生中の端末を奪い合わない、レビュー p6b12 中-5）
        val latest = latestCursor
        if (latest != null && latest.writer != signer.deviceId && latest.body.accountTag != tag) return false
        retreated = false
        restartSession()
        return true
    }

    /** 新しいセッションを始めて claim する（手元で始めた・書き込みを断られた・取り込みが期限に遅れた）。 */
    private fun restartSession() {
        val next = Session(++sessionCounter, PlaybackArbiter.Session(base = maxOf(latestCursor?.version ?: 0, cursorFloor), startVersion = null), claimId = null)
        session = next
        deferredEvidence.clear()
        // 前のセッションの書き込みの返事は待たない（新しいセッションで積み直す）
        inflight.clear()
        persist()
        sendClaimFor(next)
        markDirty()
        flush()
    }

    private fun sendClaimFor(target: Session) {
        val current = chain ?: return
        val tag = effects.accountTag() ?: return
        val claimId = newId()
        remember(claimId)
        target.claimId = claimId
        target.claimOrder = null
        val to = latestCursor?.writer?.takeIf { it != signer.deviceId }.orEmpty()
        val command = CommandBody.Yield(claimId, signer.deviceId, to, target.arbiter.base, tag)
        send(RelayV2.Claim(claimId, sealCommand(current, command)))
    }

    /** つながり直した後: 最新の状態と鎖がそろっていて、まだ書き手なら、claim し直して積み直す。 */
    private fun maybeResume() {
        if (!resumePending || deferred.isNotEmpty() || chain == null) return
        resumePending = false
        val mine = session ?: return
        if (serverActive != signer.deviceId) {
            // 切れている間に始めた再生（claim が届いていない）や、切れている間に再生中でなくなった場合
            sendClaimFor(mine)
        }
        markDirty()
        flush()
    }

    /** 送った書き込みが届いていない・断られた: その種類を積み直す。 */
    private fun markUnsent(kind: StateKind) {
        if (kind == StateKind.Cursor) dirtyCursor = true else dirtyQueue = true
    }

    private fun markDirty() {
        dirtyCursor = local != null
        dirtyQueue = localQueue != null
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
        if (cursor.writer == signer.deviceId || cursor.body.accountTag != tag || queue.body.accountTag != tag || cursor.body.queueRef != queue.hash) return false
        val claimId = newId()
        remember(claimId)
        transfer = Transfer(claimId, cursor.writer, nowMs(), retried)
        val command = CommandBody.Yield(claimId, signer.deviceId, cursor.writer, cursor.version, tag)
        if (!send(RelayV2.Transfer(claimId, cursor.version, sealCommand(current, command)))) {
            transfer = null
            return false
        }
        return true
    }

    /**
     * 遠隔操作を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。
     * @return 送ったか（家の再生が自分・別のアカウント・最新の状態をまだ見ていないなら false）
     */
    fun sendControl(
        action: ControlAction,
        positionMs: Long? = null,
    ): Boolean {
        if (isWriter || pendingStoreId != null) return false
        val current = chain ?: return false
        val tag = effects.accountTag() ?: return false
        val cursor = latestCursor ?: return false
        if (cursor.writer == signer.deviceId || cursor.body.accountTag != tag) return false
        val onItem = cursor.body.currentItemId.takeIf { action == ControlAction.Next || action == ControlAction.Previous || action == ControlAction.Seek }
        val command = CommandBody.Control(newId(), signer.deviceId, cursor.writer, action, positionMs?.coerceAtLeast(0), cursor.version, tag, onItem)
        return send(RelayV2.Control(sealCommand(current, command)))
    }

    /**
     * 家の端末 target に「ここで再生（引き取り）」を頼む（デバイスの一覧で端末を選んだ）。target は書き手でない端末。
     * @return 送ったか
     */
    fun requestRemoteTransfer(target: String): Boolean {
        if (pendingStoreId != null || target == signer.deviceId) return false
        val current = chain ?: return false
        if (current.members[target]?.keys?.role != Role.Player) return false
        val tag = effects.accountTag() ?: return false
        val cursor = latestCursor ?: return false
        if (cursor.body.accountTag != tag) return false
        val command = CommandBody.Control(newId(), signer.deviceId, target, ControlAction.Transfer, null, cursor.version, tag)
        return send(RelayV2.Control(sealCommand(current, command)))
    }

    /**
     * 手元のキューで家の再生を引き受け（音は出さない）、それを書き終えたら target に引き取ってもらう
     * （ほかの端末で鳴っている間に曲を選んだら、その端末で鳴らす。Spotify と同じ見え方）。呼ぶ前に setLocalQueue・setLocalCursor（再生中でない）を置く。
     */
    fun playOn(target: String): Boolean {
        if (target == signer.deviceId) return false
        if (!userStartedPlayback()) return false
        handOff = HandOff(target, nowMs())
        return true
    }

    private class HandOff(
        val target: String,
        val startedAtMs: Long,
    )

    private var handOff: HandOff? = null

    /** 引き受けたキューとカーソルを書き終えたら、相手に引き取りを頼む。 */
    private fun maybeHandOff() {
        val waiting = handOff ?: return
        val cursor = latestCursor ?: return
        val queue = latestQueue ?: return
        if (session == null || cursor.writer != signer.deviceId || queue.writer != signer.deviceId || cursor.body.queueRef != queue.hash) return
        handOff = null
        requestRemoteTransfer(waiting.target)
    }

    /** この端末の表示名を家のほかの端末に知らせる。 */
    fun sendProfile(
        name: String,
        kind: String? = null,
    ): Boolean {
        val current = chain ?: return false
        if (signer.deviceId !in current.members) return false
        return send(RelayV2.Control(sealCommand(current, CommandBody.Profile(signer.deviceId, name.take(64), kind?.take(16)))))
    }

    /** 表示用: ほかの端末の最新のカーソル（別アカウントのものを含む）と、今の時刻に合わせた位置。 */
    fun remoteNow(): RemoteNow? {
        val cursor = latestCursor ?: return null
        if (cursor.writer == signer.deviceId) return null
        return RemoteNow(cursor.writer, cursor.body, extrapolatedPosition(cursor))
    }

    /**
     * 遠隔操作を受けた。自分宛て・送り主が署名した端末・処理していない ID・この端末が書き手・同じアカウント・
     * 送り手が見ていた版がこの端末の最新の書き込みに近いときだけ従う（中継が取っておいた古い操作を後から流しても、版が離れていれば従わない）。
     */
    private fun onControl(message: RelayV2.Control) {
        val current = chain ?: return
        if (message.doc.header.kind != DocKind.Command) return
        // 中継が同じ命令を流し直しても、開く（受け取りの鍵で鍵交換をする）前に捨てる。署名の対象のハッシュなので、再送なら必ず同じ値
        val docHash = DocSealer.hash(message.doc)
        if (docHash in seenControlDocs) return
        seenControlDocs.addLast(docHash)
        while (seenControlDocs.size > MAX_HANDLED_CLAIMS) seenControlDocs.removeFirst()
        val opened = DocSealer.open(message.doc, current, opener) as? DocOpen.Ok ?: return
        val body = runCatching { ConnectJson.json.decodeFromString(CommandBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() ?: return
        if (body is CommandBody.Profile) {
            if (body.from == opened.header.writerId && body.from != signer.deviceId && body.from in current.members) effects.event(ClientEvent.Profile(body.from, body.name, body.kind))
            return
        }
        val command = body as? CommandBody.Control ?: return
        if (command.to != signer.deviceId || command.from != opened.header.writerId || command.from == signer.deviceId) return
        // 出してから5分を超えた命令には従わない（中継が取っておいて後から届けるのを、時間でも縛る。順序の判断には時計を使わない L-10 とは別の目的）
        if (kotlin.math.abs(epochMs() - opened.header.issuedAtEpochMs) > MAX_CONTROL_AGE_MS) return
        // 遠隔操作を出せるのは player だけ（yield と同じ。controller の遠隔操作は Phase 7 で決める）
        if (current.members[command.from]?.keys?.role != Role.Player) return
        if (command.commandId in handledClaims) return
        remember(command.commandId)
        if (command.accountTag != effects.accountTag()) return
        if (command.action == ControlAction.Transfer) {
            // 「ここで再生」を頼まれた: 書き手でなく、送り手が見ていた家の最新のカーソルが自分の見ているものに近いときだけ
            if (isWriter) return
            val latest = latestCursor ?: return
            if (command.basedOnVersion > latest.version || latest.version - command.basedOnVersion > MAX_CONTROL_LAG) return
            if (effects.control(ControlAction.Transfer, null)) requestTransfer()
            return
        }
        val own = latestCursor?.takeIf { it.writer == signer.deviceId } ?: return
        if (command.basedOnVersion > own.version || own.version - command.basedOnVersion > MAX_CONTROL_LAG) return
        // 今のセッションより前の見え方に基づく命令には従わない（前のセッションの命令を中継が取っておいて、新しいセッションに流しても効かない）
        val mine = session ?: return
        val sessionStart = mine.arbiter.startVersion ?: return
        if (command.basedOnVersion < sessionStart) return
        if (command.action == ControlAction.Seek && command.positionMs == null) return
        // 送り手が見ていた曲から、もう次の曲に変わっていたら従わない（遅れて届いた「次へ」で2曲飛ばさない）
        // 比べるのは手元で今鳴っている曲（中継に書いた最新より先に進んでいることがある）
        if (command.onItemId != null && command.onItemId != (local?.currentItemId ?: own.body.currentItemId)) return
        effects.control(command.action, command.positionMs)
    }

    /** 時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。 */
    fun tick() {
        if (floorDirty && lastPersistMs.let { it == null || nowMs() - it >= FLOOR_PERSIST_MS }) persist()
        // 引き受けたものを書けないまま10秒たったら、頼むのをやめる（手元に置いたまま）
        if (handOff?.let { nowMs() - it.startedAtMs > HAND_OFF_WAIT_MS } == true) handOff = null
        val waiting = transfer ?: return
        if (!waiting.adopting && nowMs() - waiting.startedAtMs >= TRANSFER_WAIT_MS) completeTransfer()
    }

    private fun completeTransfer() {
        val waiting = transfer ?: return
        val cursor = latestCursor
        val queue = latestQueue
        if (cursor == null || queue == null || cursor.body.queueRef != queue.hash) {
            failTransfer("no_state", release = waiting.claimId)
            return
        }
        val position = extrapolatedPosition(cursor)
        waiting.adopting = true
        if (!effects.adopt(queue.body.snapshot, cursor.body, position)) {
            failTransfer("not_adopted", release = waiting.claimId)
            return
        }
        localQueue = queue.body.snapshot
        localQueueRef = queue.hash
        session = Session(++sessionCounter, PlaybackArbiter.Session(base = cursor.version, startVersion = null), claimId = waiting.claimId)
        claimOrders[signer.deviceId]?.let { session?.claimOrder = it }
        deferredEvidence.clear()
        retreated = false
        local = LocalCursor(cursor.body.currentItemId, position, true, cursor.body.repeat, cursor.body.listenedMs, cursor.body.historySent, nowMs())
        persist()
        // B の最初のカーソルで再生中が確定する（D-7）。キューは前の端末の doc をそのまま指す
        dirtyCursor = true
        flush()
    }

    /** 引き取りをやめる（取り下げるときは中継に Release を送る）。 */
    private fun failTransfer(
        reason: String,
        release: String? = null,
    ) {
        transfer = null
        release?.let { send(RelayV2.Release(it)) }
        effects.event(ClientEvent.TransferFailed(reason))
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
        val mine = session ?: return
        if (resumePending) return
        if (dirtyQueue && inflight.values.none { it.kind == StateKind.Queue }) {
            val snapshot = localQueue
            val tag = effects.accountTag()
            if (snapshot != null && tag != null) {
                val body = QueueBody(tag, snapshot)
                val expect = maxOf(latestQueue?.version ?: 0, queueFloor)
                val doc = DocSealer.seal(signer, current, DocKind.Queue, expect + 1, null, epochMs(), ConnectJson.json.encodeToString(QueueBody.serializer(), body).encodeToByteArray())
                val requestId = newId()
                val hash = DocSealer.hash(doc)
                if (send(RelayV2.StatePut(requestId, StateKind.Queue, expect, doc))) {
                    dirtyQueue = false
                    localQueueRef = hash
                    inflight[requestId] = Inflight(StateKind.Queue, doc, hash, null, body, mine.id)
                }
            }
        }
        // カーソルは、指すキューが置けてから書く（キューの送信中は待つ）
        if (dirtyCursor && inflight.values.none { it.kind == StateKind.Cursor || it.kind == StateKind.Queue } && !dirtyQueue) {
            val body = cursorBody(mine) ?: return
            val expect = maxOf(latestCursor?.version ?: 0, cursorFloor)
            val doc = DocSealer.seal(signer, current, DocKind.Cursor, expect + 1, lastOwnCursorHash, epochMs(), ConnectJson.json.encodeToString(CursorBody.serializer(), body).encodeToByteArray())
            val hash = DocSealer.hash(doc)
            val requestId = newId()
            if (send(RelayV2.StatePut(requestId, StateKind.Cursor, expect, doc))) {
                dirtyCursor = false
                lastOwnCursorHash = hash
                inflight[requestId] = Inflight(StateKind.Cursor, doc, hash, body, null, mine.id)
            }
        }
    }

    private fun cursorBody(
        mine: Session?,
        tagOverride: String? = null,
    ): CursorBody? {
        val cursor = local ?: return null
        val tag = tagOverride ?: effects.accountTag() ?: return null
        val queueRef = localQueueRef ?: return null
        return CursorBody(
            queueRef = queueRef,
            currentItemId = cursor.currentItemId,
            positionMs = currentPosition(cursor),
            playing = cursor.playing && mine != null,
            repeat = cursor.repeat,
            sessionBase = mine?.arbiter?.base ?: 0,
            sessionStartVersion = mine?.arbiter?.startVersion,
            deviceSeq = ++deviceSeq,
            listenedMs = cursor.listenedMs,
            historySent = cursor.historySent,
            accountTag = tag,
            startedAtEpochMs = epochMs(),
        )
    }

    /** 再生中なら、位置を取ってからの経過を足す（書くのが遅れても位置を戻さない）。 */
    private fun currentPosition(cursor: LocalCursor): Long =
        if (cursor.playing) cursor.positionMs + (nowMs() - cursor.capturedAtMs).coerceIn(0, MAX_EXTRAPOLATION_MS) else cursor.positionMs

    private fun sealCommand(
        current: VerifiedChain,
        command: CommandBody,
    ): SealedDoc = DocSealer.seal(signer, current, DocKind.Command, 1, null, epochMs(), ConnectJson.json.encodeToString(CommandBody.serializer(), command).encodeToByteArray())

    private fun remember(claimId: String) {
        handledClaims.addLast(claimId)
        while (handledClaims.size > MAX_HANDLED_CLAIMS) handledClaims.removeFirst()
    }

    /** 版の床が上がるたびに保存しない（シークの多い相手で書き込みが増える、レビュー p6b34 低-10）。落ちても床が少し低いだけ（巻き戻しの窓が広がる）。 */
    private var floorDirty = false
    private var lastPersistMs: Long? = null

    private fun persistFloor() {
        val last = lastPersistMs
        if (last != null && nowMs() - last < FLOOR_PERSIST_MS) {
            floorDirty = true
            return
        }
        persist()
    }

    private fun persist() {
        floorDirty = false
        lastPersistMs = nowMs()
        effects.persist(ClientPersisted(records, storeId, cursorFloor, queueFloor, retreated, handledClaims.toList(), expectedJoin?.first, expectedJoin?.second, creatingHousehold))
    }

    companion object {
        const val TRANSFER_WAIT_MS = 3_000L
        const val FLOOR_PERSIST_MS = 30_000L
        const val MAX_EXTRAPOLATION_MS = 9 * 60 * 1000L
        private const val MAX_HANDLED_CLAIMS = 64

        /** 遠隔操作の送り手が見ていた版と、受け手の最新の書き込みの版の差の上限（それより古い操作には従わない）。 */
        private const val MAX_CONTROL_LAG = 20L

        /** 遠隔操作の鮮度の上限（命令の見出しの時刻と受け手の時計の差）。 */
        private const val MAX_CONTROL_AGE_MS = 5 * 60 * 1000L

        /** 引き受けてから相手に頼むまでの待ちの上限。 */
        private const val HAND_OFF_WAIT_MS = 10_000L
    }
}
