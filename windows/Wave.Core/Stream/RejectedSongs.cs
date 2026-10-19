namespace Wave.Core.Stream;

/// <summary>
/// 解決し直しても音の取得を拒否された曲（Android の ResolvedStreamCache.markRejected と同じ）。
/// しばらく（2分）はその曲を解決し直さずに飛ばす（/player の連打を防ぐ）。覚えるのはログインの世代ごとで、ログイン・ログアウトで忘れる。
/// </summary>
public sealed class RejectedSongs(Func<long> nowMs)
{
    public const long CooldownMs = 2 * 60 * 1000;

    private readonly Dictionary<string, long> rejectedAt = [];
    private long generation;

    public void MarkRejected(string videoId, long loginGeneration)
    {
        lock (rejectedAt)
        {
            Follow(loginGeneration);
            rejectedAt[videoId] = nowMs();
        }
    }

    public bool RecentlyRejected(string videoId, long loginGeneration)
    {
        lock (rejectedAt)
        {
            Follow(loginGeneration);
            if (!rejectedAt.TryGetValue(videoId, out var at)) return false;
            if (nowMs() - at < CooldownMs) return true;
            rejectedAt.Remove(videoId);
            return false;
        }
    }

    private void Follow(long loginGeneration)
    {
        if (loginGeneration == generation) return;
        generation = loginGeneration;
        rejectedAt.Clear();
    }
}
