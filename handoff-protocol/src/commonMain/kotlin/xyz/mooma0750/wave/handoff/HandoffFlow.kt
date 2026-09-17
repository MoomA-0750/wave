package xyz.mooma0750.wave.handoff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 封筒の中の本文（ADR-0009）。アカウントの HMAC は暗号文の中だけで運ぶ（中継に見せない）。 */
@Serializable
sealed interface HandoffBody {
    @Serializable
    @SerialName("request")
    data class Request(
        val handoffId: String,
        val accountHmac: String,
    ) : HandoffBody

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val accountHmac: String,
        /** HandoffCodec.encode の文字列（受け取る側が decode で検証する）。 */
        val snapshot: String,
    ) : HandoffBody

    @Serializable
    @SerialName("ack")
    data class Ack(
        val handoffId: String,
    ) : HandoffBody

    @Serializable
    @SerialName("cancel")
    data class Cancel(
        val handoffId: String,
        val reason: String,
    ) : HandoffBody

    @Serializable
    @SerialName("unpair")
    data object Unpair : HandoffBody

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "kind"
            }

        fun encode(body: HandoffBody): String = json.encodeToString(serializer(), body)

        fun decode(text: String): HandoffBody? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * 引き取る側（手元の端末、B）の状態（ADR-0009）。要求は1つだけ持つ。時刻は単調時計。
 * 受けるのは「自分が発行して、いま待っている handoffId」の snapshot だけ（押し付けを受けない）。
 */
class HandoffTaker(
    private val nowMs: () -> Long,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    sealed interface State {
        data object Idle : State

        data class Requested(
            val peerId: String,
            val handoffId: String,
            val deadlineMs: Long,
        ) : State

        data class Adopted(
            val peerId: String,
            val handoffId: String,
        ) : State
    }

    sealed interface SnapshotDecision {
        /** 検証を通したので適用してよい。適用できたら onAdopted、できなければ onAdoptFailed を呼ぶ。 */
        data class Adopt(
            val snapshot: HandoffSnapshot,
        ) : SnapshotDecision

        /** 待っていない（押し付け・期限切れ・別の引き取り）。handoffId に向けて Cancel を送るべきなら sendCancel。 */
        data class Ignore(
            val reason: String,
            val sendCancel: Boolean,
        ) : SnapshotDecision

        /** 形式のバージョンが違う（相手を更新する必要がある）。 */
        data class Incompatible(
            val version: Int?,
        ) : SnapshotDecision
    }

    var state: State = State.Idle
        private set

    fun request(
        peerId: String,
        handoffId: String,
    ): Boolean {
        expire()
        if (state is State.Requested) return false
        state = State.Requested(peerId, handoffId, nowMs() + timeoutMs)
        return true
    }

    /**
     * @param accountMatches 本文のアカウントの HMAC が、自分の（確認済みの）アカウントと一致したか
     */
    fun onSnapshot(
        peerId: String,
        body: HandoffBody.Snapshot,
        accountMatches: Boolean,
    ): SnapshotDecision {
        val current = state
        if (current is State.Requested && nowMs() > current.deadlineMs) {
            state = State.Idle
            return SnapshotDecision.Ignore("expired", sendCancel = true)
        }
        if (current !is State.Requested || current.peerId != peerId) return SnapshotDecision.Ignore("not_waiting", sendCancel = false)
        if (!accountMatches) {
            state = State.Idle
            return SnapshotDecision.Ignore("account_mismatch", sendCancel = true)
        }
        return when (val decoded = HandoffCodec.decode(body.snapshot, current.handoffId)) {
            is HandoffDecodeResult.Ok -> SnapshotDecision.Adopt(decoded.snapshot)
            is HandoffDecodeResult.UnsupportedVersion -> {
                state = State.Idle
                SnapshotDecision.Incompatible(decoded.version)
            }
            is HandoffDecodeResult.Rejected -> {
                state = State.Idle
                SnapshotDecision.Ignore("rejected_${decoded.reason}", sendCancel = true)
            }
        }
    }

    fun onAdopted(handoffId: String) {
        val current = state as? State.Requested ?: return
        if (current.handoffId == handoffId) state = State.Adopted(current.peerId, handoffId)
    }

    /**
     * 相手が断った（渡せるキューが無い・アカウント違い）。待っている要求と同じ端末・handoffId のときだけ取り下げる。
     * @return 取り下げたか（呼び出し側は理由を利用者に知らせる）
     */
    fun onCancel(
        peerId: String,
        body: HandoffBody.Cancel,
    ): Boolean {
        val current = state as? State.Requested ?: return false
        if (current.peerId != peerId || current.handoffId != body.handoffId) return false
        state = State.Idle
        return true
    }

    /** 適用できなかった（前面でない・ログインが変わった等）。Cancel を送る。 */
    fun onAdoptFailed(handoffId: String) {
        if ((state as? State.Requested)?.handoffId == handoffId) state = State.Idle
    }

    /** 期限を過ぎた要求を取り下げる。取り下げたら、その要求（Cancel を送る先）を返す。 */
    fun expire(): State.Requested? {
        val current = state as? State.Requested ?: return null
        if (nowMs() <= current.deadlineMs) return null
        state = State.Idle
        return current
    }

    fun reset() {
        state = State.Idle
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}

/**
 * 引き取られる側（再生中の端末、A）の状態（ADR-0009）。snapshot を送ったら自動では再開しない（二重再生を避ける）。
 * Ack で確定（今の再生の履歴の責任を B に移す）、Cancel で再開、窓を過ぎたら「引き取られなかったかもしれない」。
 */
class HandoffGiver(
    private val nowMs: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    sealed interface State {
        data object Idle : State

        data class Given(
            val peerId: String,
            val handoffId: String,
            val untilMs: Long,
        ) : State
    }

    enum class RequestDecision {
        /** 一時停止して snapshot を作って送る。 */
        Capture,

        /** 同じ handoffId の再送（冪等に無視）。 */
        Duplicate,

        /** 受け渡せない（アカウント不一致・未確認・待ち受けていない）。 */
        Refuse,
    }

    var state: State = State.Idle
        private set
    private val seenHandoffIds = ArrayDeque<String>()

    fun onRequest(
        peerId: String,
        body: HandoffBody.Request,
        canGive: Boolean,
    ): RequestDecision {
        if (body.handoffId in seenHandoffIds) return RequestDecision.Duplicate
        remember(body.handoffId)
        if (!canGive) return RequestDecision.Refuse
        state = State.Given(peerId, body.handoffId, nowMs() + windowMs)
        return RequestDecision.Capture
    }

    /** @return 確定したか（呼び出し側は今の再生の履歴を送り済みにする） */
    fun onAck(
        peerId: String,
        body: HandoffBody.Ack,
    ): Boolean {
        val current = state as? State.Given ?: return false
        if (current.peerId != peerId || current.handoffId != body.handoffId) return false
        state = State.Idle
        return true
    }

    /** @return 再開してよいか */
    fun onCancel(
        peerId: String,
        body: HandoffBody.Cancel,
    ): Boolean {
        val current = state as? State.Given ?: return false
        if (current.peerId != peerId || current.handoffId != body.handoffId) return false
        state = State.Idle
        return true
    }

    /** 窓を過ぎたら取り下げる。@return 取り下げたか（「引き取られなかったかもしれない」と出す） */
    fun expire(): Boolean {
        val current = state as? State.Given ?: return false
        if (nowMs() <= current.untilMs) return false
        state = State.Idle
        return true
    }

    /** 受け渡しの窓の間は、一時停止の規則に関わらず中継への接続を保つ。 */
    val holdingConnection: Boolean
        get() = state is State.Given

    private fun remember(handoffId: String) {
        seenHandoffIds.addLast(handoffId)
        while (seenHandoffIds.size > 32) seenHandoffIds.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 90_000L
    }
}
