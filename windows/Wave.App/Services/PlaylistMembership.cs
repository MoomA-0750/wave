namespace Wave.App.Services;

/// <summary>
/// 「この曲がどのプレイリストに入っているか」（Android の PlaylistMembership と同じ）。YouTube Music にそれを返す口が無いので、
/// プレイリストを1つずつ読む。結果はプレイリストごとに覚え、このアプリで足したらその1つだけ直す。古くなったら読み直す。
/// </summary>
public sealed class PlaylistMembership
{
    public enum Status { Checking, In, NotIn, Unknown }

    private static readonly TimeSpan MaxAge = TimeSpan.FromMinutes(10);
    private readonly Dictionary<string, (DateTime ReadAt, HashSet<string> Ids)> cache = [];
    private readonly SemaphoreSlim limit = new(3);

    /// <summary>そのプレイリストに曲が入っているか。最後まで読めなければ Unknown。UI スレッドから呼ぶ。</summary>
    public async Task<Status> CheckAsync(string playlistId, string videoId, CancellationToken cancel)
    {
        if (cache.TryGetValue(playlistId, out var hit) && DateTime.UtcNow - hit.ReadAt < MaxAge)
            return hit.Ids.Contains(videoId) ? Status.In : Status.NotIn;
        await limit.WaitAsync(cancel);
        try
        {
            var ids = await AppServices.Music.ScanPlaylistAsync(playlistId, cancel);
            if (ids is null) return Status.Unknown;
            cache[playlistId] = (DateTime.UtcNow, ids);
            return ids.Contains(videoId) ? Status.In : Status.NotIn;
        }
        finally
        {
            limit.Release();
        }
    }

    public void NoteAdded(string playlistId, string videoId)
    {
        if (cache.TryGetValue(playlistId, out var hit)) hit.Ids.Add(videoId);
    }

    public void Invalidate(string playlistId) => cache.Remove(playlistId);

    public void Clear() => cache.Clear();
}
