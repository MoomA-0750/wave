package xyz.mooma0750.wave.connect2

/**
 * 本人が再生を始めたときの判断（D-9）。Android にも中継にも依らないので、そのままテストできる（再レビュー「Android 側の判断にテストが無い」）。
 * 呼ぶ側（ConnectV2Manager）は、この判断のとおりに一時停止・引き取り・claim・知らせを行う。
 */
internal object PlayButtonPolicy {
    enum class Decision {
        /** 手元のキューで新しい再生として claim する。 */
        Claim,

        /** 一時停止して、最新の状態とアカウントタグがそろうのを待つ。 */
        Wait,

        /** 他の端末で鳴っている: 引き取る（前面）。 */
        TakeOver,

        /** 他の端末で鳴っている: 鳴らさずに知らせる（裏）。 */
        BlockRemotePlaying,

        /** 待ちで止めた再生を戻して claim する。 */
        ResumeAndClaim,

        /** 何もしない（待っている間に本人が別の操作をした・もう退いた端末でない）。 */
        Nothing,

        /** 家の端末として受け付けられなかったと知らせる。 */
        NotifyNotMember,

        /** 中継につながらなかったと知らせる。 */
        NotifyUnreachable,

        /** アカウントを確かめられなかったと知らせる。 */
        NotifyUnverified,
    }

    /** 待ちの結果。 */
    enum class WaitOutcome { Ready, NotMember, Unreachable, Unverified }

    /**
     * 再生ボタン・曲の選択を受けたとき。
     * @param newSelection 曲を選んだ（キューを置き換えた・飛んだ・止まっていたキューに足して鳴り出した）
     * @param retreated 他の端末に引き取られた・止められた印がある
     * @param stateSettled 今の接続で中継の最新の状態をそろえた
     */
    fun onPlay(
        newSelection: Boolean,
        retreated: Boolean,
        inHousehold: Boolean,
        stateSettled: Boolean,
        hasAccountTag: Boolean,
        remotePlaying: Boolean,
        foreground: Boolean,
        /** 手元にキューがある（前に聴いていたキューを戻してある）。 */
        hasLocalQueue: Boolean = false,
    ): Decision {
        // 曲を選ぶのは、いつでも新しい再生（L-3）。家に入っていない・退いていない端末も、そのまま鳴らして claim する
        if (newSelection || !retreated || !inHousehold) return Decision.Claim
        // 前面で手元のキューの再生ボタンを押したら、手元のキューのまま鳴らす（ほかの端末は claim で止まる）。
        // 中継の状態がそろうのを待たない（待つと開いてすぐの再生が遅れ、引き取りになると手元のキューがほかの端末のものに替わっていた）。
        // 裏（イヤホンのボタンなど）では、うっかりほかの端末の再生を止めないよう今までどおり確かめる
        if (hasLocalQueue && foreground) return Decision.Claim
        // 古い状態で「鳴っていない」と決めない（再レビュー 高-A・中-B）
        if (!stateSettled || !hasAccountTag) return Decision.Wait
        if (!remotePlaying) return Decision.Claim
        return if (foreground) Decision.TakeOver else Decision.BlockRemotePlaying
    }

    /**
     * 待ちの1回分: 家から外された・tailnet に届かないなら待たずに終わる（再レビュー 低-J）。そろっていれば Ready。まだなら null（待ち続ける）。
     * settledAndTagged は、終わりが決まっていないときだけ調べる。
     */
    inline fun waitStep(
        notMember: Boolean,
        tailnetUnreachable: Boolean,
        settledAndTagged: () -> Boolean,
    ): WaitOutcome? =
        when {
            notMember -> WaitOutcome.NotMember
            tailnetUnreachable -> WaitOutcome.Unreachable
            settledAndTagged() -> WaitOutcome.Ready
            else -> null
        }

    /** 時間切れ: 状態はそろっているならアカウントの問題、そうでなければ中継の問題。 */
    fun timeoutOutcome(settled: Boolean): WaitOutcome = if (settled) WaitOutcome.Unverified else WaitOutcome.Unreachable

    /**
     * 待った後。
     * @param intentUnchanged 待っている間に、本人が再生ボタン・曲の選択・一時停止をしていない
     */
    fun afterWait(
        outcome: WaitOutcome,
        intentUnchanged: Boolean,
        stillRetreated: Boolean,
        remotePlaying: Boolean,
        foreground: Boolean,
    ): Decision {
        if (!intentUnchanged || !stillRetreated) return Decision.Nothing
        return when (outcome) {
            WaitOutcome.Ready ->
                if (!remotePlaying) {
                    Decision.ResumeAndClaim
                } else if (foreground) {
                    Decision.TakeOver
                } else {
                    Decision.BlockRemotePlaying
                }
            WaitOutcome.NotMember -> Decision.NotifyNotMember
            WaitOutcome.Unreachable -> Decision.NotifyUnreachable
            WaitOutcome.Unverified -> Decision.NotifyUnverified
        }
    }
}
