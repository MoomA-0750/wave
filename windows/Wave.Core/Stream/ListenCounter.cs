namespace Wave.Core.Stream;

/// <summary>
/// 1回の再生で聴いた時間（Android の ListenCounter と同じ。ADR-0006 の 1）。位置ではなく、単調時計で再生中の区間だけを足す。
/// シークしても足す量は変わらないので、シークしただけでは送る時間に届かない。
/// </summary>
public sealed class ListenCounter
{
    public const long ThresholdMs = 30_000;
    public const long MinThresholdMs = 5_000;

    private long listenedMs;
    private long? playingSinceMs;

    /// <param name="initialListenedMs">他の端末で聴いた時間（引き取ったとき）。</param>
    public ListenCounter(long initialListenedMs = 0) => listenedMs = Math.Max(0, initialListenedMs);

    public bool Running => playingSinceMs is not null;

    /// <summary>再生が始まった（既に数えていれば何もしない）。</summary>
    public void Start(long nowMs) => playingSinceMs ??= nowMs;

    /// <summary>止まった。それまでの区間を足す。</summary>
    public void Stop(long nowMs)
    {
        if (playingSinceMs is { } since) listenedMs = SaturatingAdd(listenedMs, Math.Max(0, nowMs - since));
        playingSinceMs = null;
    }

    /// <summary>数え直す（新しい再生。引き取った曲は相手の値から）。再生中なら nowMs から数え始める。</summary>
    public void Reset(long initialListenedMs, long? playingFromMs)
    {
        listenedMs = Math.Max(0, initialListenedMs);
        playingSinceMs = playingFromMs;
    }

    public long ListenedMs(long nowMs) => SaturatingAdd(listenedMs, playingSinceMs is { } since ? Math.Max(0, nowMs - since) : 0);

    /// <summary>送る時間に届いたか（長さが分からなければ 30 秒）。</summary>
    public bool Reached(long nowMs, long? durationMs) => ListenedMs(nowMs) >= ThresholdFor(durationMs);

    /// <summary>30秒。曲が30秒より短ければ長さの半分（5秒以上）。長さが分からなければ30秒。</summary>
    public static long ThresholdFor(long? durationMs)
    {
        if (durationMs is not { } duration || duration <= 0) return ThresholdMs;
        return duration < ThresholdMs ? Math.Max(duration / 2, MinThresholdMs) : ThresholdMs;
    }

    private static long SaturatingAdd(long a, long b) => a > long.MaxValue - b ? long.MaxValue : a + b;
}
