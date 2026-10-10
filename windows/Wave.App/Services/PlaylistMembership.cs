namespace Wave.App.Services;

/// <summary>
/// 「この曲がどのプレイリストに入っているか」（Android の PlaylistMembership と同じ）。YouTube Music にそれを返す口が無いので、
/// プレイリストを1つずつ読む。結果（曲ごとのプレイリスト内の項目の ID）はプレイリストごとに覚え、このアプリで足した・外したらその1つだけ直す。古くなったら読み直す。
/// </summary>
public sealed class PlaylistMembership
{
    public enum Status { Checking, In, NotIn, Unknown }

    // 再生のたびに先読みするので、短すぎると毎回全部を読み直す（Android と同じ）
    private static readonly TimeSpan MaxAge = TimeSpan.FromMinutes(30);
    private readonly Dictionary<string, (DateTime ReadAt, Dictionary<string, List<string>> Items)> cache = [];
    private readonly SemaphoreSlim limit = new(6);

    /// <summary>そのプレイリストに曲が入っているか。最後まで読めなければ Unknown。UI スレッドから呼ぶ。</summary>
    public async Task<Status> CheckAsync(string playlistId, string videoId, CancellationToken cancel)
    {
        var items = await ReadAsync(playlistId, false, cancel);
        return items is null ? Status.Unknown : items.ContainsKey(videoId) ? Status.In : Status.NotIn;
    }

    private async Task<Dictionary<string, List<string>>?> ReadAsync(string playlistId, bool fresh, CancellationToken cancel)
    {
        if (!fresh && cache.TryGetValue(playlistId, out var hit) && DateTime.UtcNow - hit.ReadAt < MaxAge) return hit.Items;
        await limit.WaitAsync(cancel);
        try
        {
            var items = await AppServices.Music.ScanPlaylistItemsAsync(playlistId, cancel);
            if (items is null) return null;
            cache[playlistId] = (DateTime.UtcNow, items);
            return items;
        }
        finally
        {
            limit.Release();
        }
    }

    /// <summary>曲を外すのに使う、プレイリスト内の項目の ID。覚えていなければ（このアプリで足した曲など）読み直す。読めなければ null、入っていなければ空。</summary>
    public async Task<List<string>?> SetVideoIdsAsync(string playlistId, string videoId, CancellationToken cancel = default)
    {
        if ((await ReadAsync(playlistId, false, cancel))?.GetValueOrDefault(videoId) is { Count: > 0 } known) return known;
        var items = await ReadAsync(playlistId, true, cancel);
        return items is null ? null : items.GetValueOrDefault(videoId) ?? [];
    }

    /// <summary>再生中の曲（先読みした曲）。</summary>
    private string? nowPlayingId;

    /// <summary>再生中の曲が入っている（と分かった）プレイリスト。</summary>
    private readonly HashSet<string> nowPlayingIn = [];

    /// <summary>再生中の曲がどのプレイリストに入っているかが変わった。</summary>
    public event Action? NowPlayingChanged;

    /// <summary>その曲がどれかのプレイリストに入っているか（先読みした再生中の曲だけ分かる。Spotify の✓の印に使う）。</summary>
    public bool IsSavedNowPlaying(string? videoId) => videoId is not null && videoId == nowPlayingId && nowPlayingIn.Count > 0;

    /// <summary>
    /// 再生が始まった曲について、自分のプレイリストを先に読んでおく（追加の画面を開いたときに待たせない。Android と同じ）。
    /// 読んだ結果は覚えるので、次の曲からは古くなったプレイリストだけを読み直す。UI スレッドから呼ぶ。
    /// </summary>
    public async Task PrefetchAsync(IReadOnlyList<string> playlistIds, string videoId, CancellationToken cancel)
    {
        if (nowPlayingId != videoId)
        {
            nowPlayingId = videoId;
            nowPlayingIn.Clear();
            NowPlayingChanged?.Invoke();
        }
        var started = DateTime.UtcNow;
        await Task.WhenAll(playlistIds.Select(async id =>
        {
            if (await CheckAsync(id, videoId, cancel) == Status.In && nowPlayingId == videoId && nowPlayingIn.Add(id)) NowPlayingChanged?.Invoke();
        }));
        AppPaths.Log($"membership prefetch playlists={playlistIds.Count} in={nowPlayingIn.Count} ms={(int)(DateTime.UtcNow - started).TotalMilliseconds}");
    }

    private void NoteNowPlaying(string playlistId, string videoId, bool isIn)
    {
        if (videoId != nowPlayingId) return;
        if (isIn ? nowPlayingIn.Add(playlistId) : nowPlayingIn.Remove(playlistId)) NowPlayingChanged?.Invoke();
    }

    public void NoteAdded(string playlistId, string videoId)
    {
        if (cache.TryGetValue(playlistId, out var hit)) hit.Items.TryAdd(videoId, []);
        NoteNowPlaying(playlistId, videoId, true);
    }

    public void NoteRemoved(string playlistId, string videoId)
    {
        if (cache.TryGetValue(playlistId, out var hit)) hit.Items.Remove(videoId);
        NoteNowPlaying(playlistId, videoId, false);
    }

    public void Invalidate(string playlistId) => cache.Remove(playlistId);

    public void Clear()
    {
        cache.Clear();
        nowPlayingId = null;
        nowPlayingIn.Clear();
        NowPlayingChanged?.Invoke();
    }
}
