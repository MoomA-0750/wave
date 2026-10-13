package xyz.mooma0750.wave.relay.v2

import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.StateKind

/**
 * 端末からのメッセージを家の状態に当てて、返事と配信を決める（通信路に依らない）。
 * 中継（HubV2）と、端末なしの多端末テストの中継が同じものを使う（テストが本物の振り分けを通るように）。
 * 呼ぶ側は、家の変更と配信を1本に並べる（HubV2 は synchronized(order) の中で呼ぶ。レビュー p6b12 高-1）。配信の順番を変えないこと。
 */
internal class HouseholdRouter(
    private val store: () -> HouseholdStore,
    private val log: (String) -> Unit = {},
) {
    /** 返事と配信の出口。 */
    interface Outlet {
        fun reply(message: RelayV2)

        fun broadcast(
            message: RelayV2,
            except: String? = null,
        )

        /** 家から外された端末（接続を閉じる）。 */
        fun removed(deviceId: String)

        /** この接続にライブラリを配るようにする（library_get を送った接続だけに配る、ADR-0013）。 */
        fun subscribeLibrary() {}

        /** ライブラリを、頼んだ接続にだけ配る。 */
        fun broadcastLibrary(
            message: RelayV2,
            except: String? = null,
        ) {}
    }

    /** @return 扱ったか（加入の手順など、家の状態に当てないメッセージは false。呼ぶ側で扱う） */
    fun route(
        deviceId: String,
        message: RelayV2?,
        out: Outlet,
    ): Boolean {
        val store = store()
        when (message) {
            is RelayV2.HouseholdCreate ->
                when (val result = store.createHousehold(deviceId, message.genesis)) {
                    is HouseholdStore.ChainResult.Ok -> {
                        out.reply(RelayV2.ChainOk(result.headSeq, result.headHash))
                        log("household created")
                    }
                    is HouseholdStore.ChainResult.Conflict -> out.reply(RelayV2.ChainConflict(result.headSeq, result.headHash))
                    is HouseholdStore.ChainResult.Rejected -> out.reply(RelayV2.Error(result.code))
                }
            is RelayV2.ChainGet -> out.reply(RelayV2.Chain(store.chainFrom(message.fromSeq)))
            is RelayV2.ChainAppend ->
                when (val result = store.append(deviceId, message.record)) {
                    is HouseholdStore.ChainResult.Ok -> {
                        out.reply(RelayV2.ChainOk(result.headSeq, result.headHash))
                        out.broadcast(RelayV2.ChainChanged(result.headSeq, result.headHash), except = deviceId)
                        result.activeChanged?.let { out.broadcast(it) }
                        result.removed?.let(out::removed)
                        log("chain appended seq=${result.headSeq}")
                    }
                    is HouseholdStore.ChainResult.Conflict -> out.reply(RelayV2.ChainConflict(result.headSeq, result.headHash))
                    is HouseholdStore.ChainResult.Rejected -> out.reply(RelayV2.Error(result.code))
                }
            RelayV2.StateGet -> store.latest().forEach { out.reply(RelayV2.State(it)) }
            is RelayV2.StatePut ->
                when (val result = store.put(deviceId, message)) {
                    is HouseholdStore.PutResult.Ok -> {
                        out.reply(RelayV2.StateOk(message.requestId, message.kind, result.state.version))
                        out.broadcast(RelayV2.State(result.state), except = deviceId)
                        result.activeChanged?.let { out.broadcast(it) }
                        log("state kind=${message.kind} chars=${message.doc.ciphertext.length}")
                    }
                    is HouseholdStore.PutResult.Conflict -> out.reply(RelayV2.StateConflict(message.requestId, message.kind, result.latest))
                    is HouseholdStore.PutResult.Rejected -> out.reply(RelayV2.Error(result.code, message.requestId))
                }
            RelayV2.LibraryGet -> {
                if (!store.isMember(deviceId)) {
                    out.reply(RelayV2.Error("not_member"))
                } else {
                    out.subscribeLibrary()
                    out.reply(RelayV2.Library(store.library()))
                }
            }
            is RelayV2.LibraryPut ->
                when (val result = store.putLibrary(deviceId, message)) {
                    is HouseholdStore.LibraryResult.Ok -> {
                        out.reply(RelayV2.LibraryOk(message.requestId, result.state.version))
                        out.broadcastLibrary(RelayV2.Library(result.state), except = deviceId)
                        log("library version=${result.state.version} chars=${message.doc.ciphertext.length}")
                    }
                    is HouseholdStore.LibraryResult.Conflict -> out.reply(RelayV2.LibraryConflict(message.requestId, result.latest))
                    is HouseholdStore.LibraryResult.Rejected -> out.reply(RelayV2.Error(result.code, message.requestId))
                }
            is RelayV2.Claim -> onClaim(store.claim(deviceId, message.claimId, message.yieldDoc), deviceId, message.claimId, message.yieldDoc, out, "claim")
            is RelayV2.Transfer ->
                onClaim(store.transfer(deviceId, message.claimId, message.basedOnVersion, message.yieldDoc), deviceId, message.claimId, message.yieldDoc, out, "transfer")
            is RelayV2.Yielded ->
                when (val result = store.yielded(deviceId, message)) {
                    is HouseholdStore.PutResult.Ok -> {
                        out.reply(RelayV2.StateOk(message.claimId, StateKind.Cursor, result.state.version))
                        out.broadcast(RelayV2.State(result.state), except = deviceId)
                        log("yielded")
                    }
                    is HouseholdStore.PutResult.Conflict -> out.reply(RelayV2.StateConflict(message.claimId, StateKind.Cursor, result.latest))
                    is HouseholdStore.PutResult.Rejected -> out.reply(RelayV2.Error(result.code, message.claimId))
                }
            is RelayV2.Release -> store.release(deviceId, message.claimId)?.let { out.broadcast(it) }
            is RelayV2.Control ->
                // 書き手が送り主で、命令の doc であるときだけ配る（中身は読まない。保存しない）
                // 命令は詰め物の1ブロック（4KB）に収まる。大きなものを家の全端末へ配らない
                if (message.doc.header.writerId == deviceId && message.doc.header.kind == xyz.mooma0750.wave.handoff.v2.DocKind.Command && message.doc.ciphertext.length <= MAX_CONTROL_CIPHERTEXT_CHARS) {
                    out.broadcast(message, except = deviceId)
                    log("control")
                } else {
                    out.reply(RelayV2.Error("bad_control"))
                }
            else -> return false
        }
        return true
    }

    /** claim と transfer の結果（途中の引き取りを捨てた知らせ → 再生中の端末の変更 → yield の順）。 */
    private fun onClaim(
        result: HouseholdStore.ClaimResult,
        deviceId: String,
        claimId: String,
        yieldDoc: xyz.mooma0750.wave.handoff.v2.SealedDoc,
        out: Outlet,
        label: String,
    ) {
        when (result) {
            is HouseholdStore.ClaimResult.Ok -> {
                store().takeDroppedPending()?.let { out.broadcast(RelayV2.PendingExpired(it)) }
                out.broadcast(result.activeChanged)
                // yield は家の全端末に配る（各端末は宛先で判断する。サーバーの再生中の端末に依らない）
                out.broadcast(RelayV2.YieldDelivery(claimId, yieldDoc), except = deviceId)
                log(label)
            }
            is HouseholdStore.ClaimResult.Conflict -> out.reply(RelayV2.StateConflict(claimId, StateKind.Cursor, result.latest))
            is HouseholdStore.ClaimResult.Rejected -> out.reply(RelayV2.Error(result.code, claimId))
        }
    }

    /** 期限切れ・持ち主の切断で捨てた引き取りを知らせる（期限切れ → 再生中の端末の変更の順）。 */
    fun broadcastExpired(
        expired: Pair<String, RelayV2.ActiveChanged>?,
        out: Outlet,
    ) {
        val (claimId, active) = expired ?: return
        out.broadcast(RelayV2.PendingExpired(claimId))
        out.broadcast(active)
    }

    private companion object {
        /** 命令の暗号文の上限（4KB の詰め物1ブロック + GCM のタグを base64url にした長さに余裕を持たせる）。 */
        const val MAX_CONTROL_CIPHERTEXT_CHARS = 8 * 1024
    }
}
