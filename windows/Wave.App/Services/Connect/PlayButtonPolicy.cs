namespace Wave.App.Services.Connect;

/// <summary>本人が再生を始めたときの判断（D-9、Android の PlayButtonPolicy と同じ）。</summary>
public static class PlayButtonPolicy
{
    public enum Decision
    {
        /// <summary>手元のキューで新しい再生として claim する。</summary>
        Claim,

        /// <summary>家で最後に鳴った再生（キューと位置）に手元を合わせてから claim する。</summary>
        SyncAndClaim,

        /// <summary>一時停止して、最新の状態とアカウントタグがそろうのを待つ。</summary>
        Wait,

        /// <summary>他の端末で鳴っている: 引き取る（前面）。</summary>
        TakeOver,

        /// <summary>他の端末で鳴っている: 鳴らさずに知らせる（裏）。</summary>
        BlockRemotePlaying,

        /// <summary>待ちで止めた再生を戻して claim する。</summary>
        ResumeAndClaim,

        /// <summary>何もしない（待っている間に本人が別の操作をした・もう退いた端末でない）。</summary>
        Nothing,
        NotifyNotMember,
        NotifyUnreachable,
        NotifyUnverified,
    }

    public enum WaitOutcome { Ready, NotMember, Unreachable, Unverified }

    /// <param name="newSelection">曲を選んだ（キューを置き換えた・飛んだ）</param>
    /// <param name="retreated">他の端末に引き取られた・止められた印がある</param>
    /// <param name="stateSettled">今の接続で中継の最新の状態をそろえた</param>
    /// <param name="hasLocalQueue">手元にキューがある（前に聴いていたキューを戻してある）</param>
    public static Decision OnPlay(bool newSelection, bool retreated, bool inHousehold, bool stateSettled, bool hasAccountTag, bool remotePlaying, bool foreground, bool hasLocalQueue = false)
    {
        // 曲を選ぶのは、いつでも新しい再生（L-3）
        if (newSelection || !inHousehold) return Decision.Claim;
        // 前面で再生ボタンを押したら、待たずにこの端末で鳴らす。キューは家で最後に鳴ったもの（ほかの端末で鳴っていればその位置）に合わせる
        // （Android と同じ。止まっている間も合わせてある。ほかの端末は claim で止まる）。状態がそろう前は手元のまま鳴らす
        if (foreground && (hasLocalQueue || !retreated)) return stateSettled && hasAccountTag ? Decision.SyncAndClaim : Decision.Claim;
        if (!retreated) return Decision.Claim;
        // 古い状態で「鳴っていない」と決めない
        if (!stateSettled || !hasAccountTag) return Decision.Wait;
        if (!remotePlaying) return Decision.Claim;
        return foreground ? Decision.TakeOver : Decision.BlockRemotePlaying;
    }

    /// <summary>待ちの1回分。そろっていれば Ready、まだなら null。</summary>
    public static WaitOutcome? WaitStep(bool notMember, bool tailnetUnreachable, Func<bool> settledAndTagged) =>
        notMember ? WaitOutcome.NotMember
        : tailnetUnreachable ? WaitOutcome.Unreachable
        : settledAndTagged() ? WaitOutcome.Ready
        : null;

    /// <summary>時間切れ: 状態はそろっているならアカウントの問題、そうでなければ中継の問題。</summary>
    public static WaitOutcome TimeoutOutcome(bool settled) => settled ? WaitOutcome.Unverified : WaitOutcome.Unreachable;

    public static Decision AfterWait(WaitOutcome outcome, bool intentUnchanged, bool stillRetreated, bool remotePlaying, bool foreground)
    {
        if (!intentUnchanged || !stillRetreated) return Decision.Nothing;
        return outcome switch
        {
            WaitOutcome.Ready => !remotePlaying ? Decision.ResumeAndClaim : foreground ? Decision.TakeOver : Decision.BlockRemotePlaying,
            WaitOutcome.NotMember => Decision.NotifyNotMember,
            WaitOutcome.Unreachable => Decision.NotifyUnreachable,
            _ => Decision.NotifyUnverified,
        };
    }
}
