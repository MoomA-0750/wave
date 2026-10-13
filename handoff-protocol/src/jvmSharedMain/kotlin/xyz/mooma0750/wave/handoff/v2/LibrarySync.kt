package xyz.mooma0750.wave.handoff.v2

/**
 * ライブラリ（RSS で登録した番組・聴いたところ）を、中継の家の端末の間で合わせる（ADR-0013）。
 * 中継の最新の文書を開いて手元と合わせ、手元にしか無い・手元のほうが新しいものがあれば、版の CAS で書く。
 * 書き込みは1本ずつ。食い違ったら（ほかの端末が先に書いた）、届いた最新と合わせて書き直す。
 * 呼ぶ側は ConnectClient と同じ1本の流れから呼ぶ（スレッドをまたがない）。
 */
class LibrarySync(
    private val signer: DeviceSigner,
    private val opener: KxOpener,
    private val send: (RelayV2) -> Boolean,
    /** 検証した今の一覧（無ければ書かない・開かない）。 */
    private val chain: () -> VerifiedChain?,
    /** 中継の保存の ID（ConnectClient が受け入れたもの）。違う保存の文書は取り込まない。 */
    private val storeId: () -> String?,
    private val epochMs: () -> Long,
    private val newId: () -> String,
    /** 中継から合わせた結果が届いた（手元の状態に取り込む）。 */
    private val onRemote: (LibraryBody) -> Unit,
) {
    /** 中継の最新の版と中身（開けたもの）。 */
    private var remoteVersion: Long = 0
    private var remoteBody: LibraryBody? = null
    private var remoteHash: String? = null

    /** remoteVersion の保存の ID（中継が作り直されたら、版の大小で捨てない）。 */
    private var remoteStore: String? = null

    /** 手元の今の中身（書きたいもの）。 */
    private var local: LibraryBody? = null

    /** 返事を待っている書き込み。 */
    private var inflight: String? = null

    /** 接続して library_get の返事を受けたか（受けるまで書かない。中継の版を知らずに 0 で書くと食い違う）。 */
    private var synced = false

    /** 開けなかった文書（一覧が古い）。一覧が届いたら開き直す。 */
    private var deferred: LibraryState? = null

    /** つながった（welcome の後）。最新を頼む。 */
    fun onConnected() {
        synced = false
        inflight = null
        send(RelayV2.LibraryGet)
    }

    fun onDisconnected() {
        synced = false
        inflight = null
    }

    /** 一覧が新しくなった。開けずにいた文書を開き直す。 */
    fun onChainUpdated() {
        val waiting = deferred ?: return
        deferred = null
        adopt(waiting)
        flush()
    }

    /** 手元が変わった。中継のものと合わせて、違えば書く。 */
    fun publish(body: LibraryBody) {
        local = body
        flush()
    }

    fun onMessage(message: RelayV2) {
        when (message) {
            is RelayV2.Library -> {
                synced = true
                message.state?.let(::adopt) ?: forgetRemote()
                flush()
            }
            is RelayV2.LibraryOk -> {
                if (message.requestId != inflight) return
                inflight = null
                remoteVersion = message.version
                remoteBody = pendingBody
                remoteHash = null
                remoteStore = storeId()
                pendingBody = null
                flush()
            }
            is RelayV2.LibraryConflict -> {
                if (message.requestId != inflight) return
                inflight = null
                pendingBody = null
                message.latest?.let(::adopt) ?: forgetRemote()
                flush()
            }
            else -> Unit
        }
    }

    /** 書いている中身（返事が来たら中継の中身とする）。 */
    private var pendingBody: LibraryBody? = null

    /**
     * 中継にまだ文書が無い（作り直された中継など）。覚えていた版を捨てて 0 から書く
     * （覚えた版のまま書くと、食い違いが返り続けて書き込みが回り続ける）。
     */
    private fun forgetRemote() {
        if (storeId() == null) return
        remoteVersion = 0
        remoteBody = null
        remoteHash = null
        remoteStore = null
    }

    /** 中継の文書を開いて取り込む。古い版・違う保存・開けないものは捨てる。 */
    private fun adopt(state: LibraryState) {
        if (state.storeId != storeId()) return
        // 覚えている版が別の保存のもの（中継が作り直された）なら、版の大小では捨てない
        val sameStore = state.storeId == remoteStore
        if (sameStore && state.version < remoteVersion) return
        val hash = DocSealer.hash(state.doc)
        if (sameStore && state.version == remoteVersion && hash == remoteHash) return
        val current = chain() ?: return run { deferred = state }
        val opened =
            when (val result = DocSealer.open(state.doc, current, opener)) {
                is DocOpen.Ok -> result
                is DocOpen.NeedChain -> return run { deferred = state }
                else -> return
            }
        if (opened.header.kind != DocKind.Library || opened.header.version != state.version) return
        val body = runCatching { ConnectJson.json.decodeFromString(LibraryBody.serializer(), opened.plaintext.decodeToString()) }.getOrNull() ?: return
        remoteVersion = state.version
        remoteHash = hash
        remoteBody = body
        remoteStore = state.storeId
        // 手元と合わせたものを取り込んでもらう（手元のほうが新しい分は、次の flush で書く）
        val merged = local?.merge(body) ?: body
        local = merged
        onRemote(merged)
    }

    /** 手元に中継より新しいものがあれば書く。 */
    private fun flush() {
        if (!synced || inflight != null || storeId() == null) return
        val mine = local ?: return
        val remote = remoteBody
        // 同じ時刻なら中継の側を残す
        val merged = remote?.let(mine::merge) ?: mine
        if (remote != null && merged == remote) return
        if (remote == null && merged.feeds.isEmpty() && merged.positions.isEmpty()) return
        val current = chain() ?: return
        if (signer.deviceId !in current.members) return
        val plaintext = ConnectJson.json.encodeToString(LibraryBody.serializer(), merged).encodeToByteArray()
        if (plaintext.size > DocSealer.MAX_PLAINTEXT) return
        val requestId = newId()
        val doc = DocSealer.seal(signer, current, DocKind.Library, remoteVersion + 1, null, epochMs(), plaintext)
        if (send(RelayV2.LibraryPut(requestId, remoteVersion, doc))) {
            inflight = requestId
            pendingBody = merged
            local = merged
        }
    }
}
