namespace Wave.Core.Connect;

/// <summary>
/// 止まる規則（D-8、L-3・L-10）。再生中の端末が、ほかの player の署名を確かめた証拠を受けたときに、止まるかを決める（Android 版と同じ）。
/// 端末の時計は使わない。「相手の再生を見てから始めた方が後」で決め、互いを見ずに始めたときだけ、中継が claim に振った順番（claimOrder）で決める。
/// 順番がまだ分からなければ保留し、分かったときに決め直す。
/// 呼ぶ前に確かめておくこと（ここでは見ない）: 証拠の署名・書き手が家の player・一覧の頭が分岐していない・accountTag が自分と一致。
/// </summary>
public static class PlaybackArbiter
{
    /// <summary>この端末の今の再生のセッション。</summary>
    /// <param name="Base">セッションを始めたときに見ていた最新のカーソルの版</param>
    /// <param name="StartVersion">このセッションで自分が最初に書けたカーソルの版（まだ書けていなければ null）</param>
    public sealed record Session(long Base, long? StartVersion);

    public abstract record Evidence(string From, long SessionBase)
    {
        /// <summary>自分宛の yield（相手は新しくセッションを始めたところで、まだ書いた版が無い）。</summary>
        public sealed record Yield(string From, long SessionBase) : Evidence(From, SessionBase);

        /// <summary>相手の、再生中のカーソル。一時停止のカーソルは証拠にしない。</summary>
        public sealed record PlayingCursor(string From, long SessionBase, long? SessionStartVersion) : Evidence(From, SessionBase);
    }

    public enum Decision
    {
        Stop,
        Keep,

        /// <summary>同時に始めたが、どちらかの claimOrder がまだ分からない。分かったら決め直す。</summary>
        Defer,
    }

    public enum Reason
    {
        /// <summary>相手は、こちらのセッションを見てから始めた（相手が後）。</summary>
        OtherStartedAfterMine,

        /// <summary>こちらが、相手のセッションを見てから始めた（こちらが後）。</summary>
        MineStartedAfterOther,

        /// <summary>互いを見ずに始めた。claimOrder で決めた（または保留）。</summary>
        Concurrent,

        /// <summary>自分自身の証拠。</summary>
        Self,
    }

    public sealed record Result(Decision Decision, Reason Reason);

    /// <param name="myClaimOrder">このセッションの自分の claim に中継が振った順番（まだ届いていなければ null）</param>
    /// <param name="otherClaimOrder">相手の最新の claim の順番（まだ届いていなければ null）</param>
    public static Result Decide(string myId, Session mine, Evidence evidence, long? myClaimOrder, long? otherClaimOrder)
    {
        if (evidence.From == myId) return new Result(Decision.Keep, Reason.Self);
        if (mine.StartVersion is { } myStart && evidence.SessionBase >= myStart) return new Result(Decision.Stop, Reason.OtherStartedAfterMine);
        if (evidence is Evidence.PlayingCursor { SessionStartVersion: { } otherStart } && mine.Base >= otherStart) return new Result(Decision.Keep, Reason.MineStartedAfterOther);
        if (myClaimOrder is not { } my || otherClaimOrder is not { } other) return new Result(Decision.Defer, Reason.Concurrent);
        return new Result(my < other ? Decision.Stop : Decision.Keep, Reason.Concurrent);
    }
}
