namespace Wave.Core.Connect;

/// <summary>
/// 中継の接続の見張り（Android の RelayWatch と同じ規則。docs/decisions.md 2026-11-14）。
/// 死んだソケットを「つながっている」と思い込んだまま、ほかの端末の再生が届かないのを防ぐ。
/// つなぎ始めてから 15 秒のうちに welcome が来なければ切る。つながっている間、20 秒何も届かなければ確かめを送り、
/// 8 秒のうちに何も届かなければ切る。前面に戻った・ネットにつながり直した・スリープから戻ったら（Wake）、すぐ確かめを送る。
/// 時刻は呼ぶ側の単調時計（ミリ秒）。呼ぶ側で1本に並べる。
/// </summary>
public sealed class RelayWatch
{
    public enum Action
    {
        None,

        /// <summary>確かめを送る。</summary>
        Probe,

        /// <summary>このソケットは死んでいる。切ってつなぎ直す。</summary>
        Dead,
    }

    public const long WelcomeTimeoutMs = 15_000;
    public const long IdleMs = 20_000;
    public const long ProbeTimeoutMs = 8_000;
    public const int TickMs = 1_000;

    /// <summary>確かめに送るもの。鎖のずっと先を頼む（中継は空の鎖を返すだけで、どの版の中継も答える）。</summary>
    public const string Probe = "{\"op\":\"chain_get\",\"fromSeq\":2147483647}";

    private long openedAt;
    private bool welcomed;
    private long lastReceivedAt;
    private long? probeSentAt;

    /// <summary>新しいソケットでつなぎ始めた。</summary>
    public void Opened(long nowMs)
    {
        openedAt = nowMs;
        welcomed = false;
        lastReceivedAt = nowMs;
        probeSentAt = null;
    }

    /// <summary>中継から何か届いた。</summary>
    public void Received(long nowMs, bool welcome = false)
    {
        if (welcome) welcomed = true;
        lastReceivedAt = nowMs;
        probeSentAt = null;
    }

    /// <summary>前面に戻った・ネットにつながり直した。つながっていれば、すぐ確かめる。</summary>
    public Action Wake(long nowMs)
    {
        if (!welcomed || probeSentAt is not null) return Action.None;
        probeSentAt = nowMs;
        return Action.Probe;
    }

    /// <summary>1秒ごとに呼ぶ。</summary>
    public Action Tick(long nowMs)
    {
        if (!welcomed) return nowMs - openedAt >= WelcomeTimeoutMs ? Action.Dead : Action.None;
        if (probeSentAt is { } sent) return nowMs - sent >= ProbeTimeoutMs ? Action.Dead : Action.None;
        if (nowMs - lastReceivedAt >= IdleMs)
        {
            probeSentAt = nowMs;
            return Action.Probe;
        }
        return Action.None;
    }

    /// <summary>
    /// つなぎ直すまでの待ち。failedAttempts は welcome を受けてから続けて失敗した数（つながっていたソケットが切れた直後は 0）。
    /// 1秒、2秒、4秒、8秒、16秒、以後30秒。
    /// </summary>
    public static TimeSpan ReconnectDelay(int failedAttempts) =>
        TimeSpan.FromMilliseconds(failedAttempts <= 0 ? 1_000 : Math.Min(2_000L << Math.Min(failedAttempts - 1, 4), 30_000));
}
