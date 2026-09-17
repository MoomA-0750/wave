package xyz.mooma0750.wave.handoff.v2

/**
 * 止まる規則（D-8、L-3・L-10）。再生中の端末が、ほかの player の署名を確かめた証拠を受けたときに、止まるかを決める。
 * 端末の時計は使わない。「相手の再生を見てから始めた方が後」で決め、互いを見ずに始めたときだけ、中継が claim に振った順番（claimOrder）で決める。
 * 順番がまだ分からなければ保留し、分かったときに決め直す（受けた順番で判断が変わらないように。レビュー p6b12 高-1）。
 *
 * 呼ぶ前に確かめておくこと（ここでは見ない）: 証拠の署名・書き手が家の player・一覧の頭が分岐していない・accountTag が自分と一致（L-11）。
 */
object PlaybackArbiter {
    /**
     * この端末の今の再生のセッション。
     * @param base セッションを始めたときに見ていた最新のカーソルの版
     * @param startVersion このセッションで自分が最初に書けたカーソルの版（まだ書けていなければ null）
     */
    data class Session(
        val base: Long,
        val startVersion: Long?,
    )

    sealed interface Evidence {
        val from: String
        val sessionBase: Long

        /** 自分宛の yield（相手は新しくセッションを始めたところで、まだ書いた版が無い）。 */
        data class Yield(
            override val from: String,
            override val sessionBase: Long,
        ) : Evidence

        /** 相手の、再生中のカーソル。一時停止のカーソルは証拠にしない。 */
        data class PlayingCursor(
            override val from: String,
            override val sessionBase: Long,
            val sessionStartVersion: Long?,
        ) : Evidence
    }

    enum class Decision {
        Stop,
        Keep,

        /** 同時に始めたが、どちらかの claimOrder がまだ分からない。分かったら決め直す。 */
        Defer,
    }

    enum class Reason {
        /** 相手は、こちらのセッションを見てから始めた（相手が後）。 */
        OtherStartedAfterMine,

        /** こちらが、相手のセッションを見てから始めた（こちらが後）。 */
        MineStartedAfterOther,

        /** 互いを見ずに始めた。claimOrder で決めた（または保留）。 */
        Concurrent,

        /** 自分自身の証拠。 */
        Self,
    }

    data class Result(
        val decision: Decision,
        val reason: Reason,
    )

    /**
     * @param myClaimOrder このセッションの自分の claim に中継が振った順番（まだ届いていなければ null）
     * @param otherClaimOrder 相手の最新の claim の順番（まだ届いていなければ null）
     */
    fun decide(
        myId: String,
        mine: Session,
        evidence: Evidence,
        myClaimOrder: Long?,
        otherClaimOrder: Long?,
    ): Result {
        if (evidence.from == myId) return Result(Decision.Keep, Reason.Self)
        val myStart = mine.startVersion
        if (myStart != null && evidence.sessionBase >= myStart) return Result(Decision.Stop, Reason.OtherStartedAfterMine)
        val otherStart = (evidence as? Evidence.PlayingCursor)?.sessionStartVersion
        if (otherStart != null && mine.base >= otherStart) return Result(Decision.Keep, Reason.MineStartedAfterOther)
        if (myClaimOrder == null || otherClaimOrder == null) return Result(Decision.Defer, Reason.Concurrent)
        return Result(if (myClaimOrder < otherClaimOrder) Decision.Stop else Decision.Keep, Reason.Concurrent)
    }
}
