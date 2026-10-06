using Wave.Core.Music;

namespace Wave.Core.Lyrics;

/// <summary>
/// 曲の歌詞を選ぶ（Android 版の LyricsRepository と同じ）。時刻付きは LRCLIB から、無ければ YouTube Music の時刻なしの歌詞。
/// 見つかった・無かった結果は曲ごとに覚える。失敗は覚えない。
/// </summary>
public sealed class LyricsService(MusicRepository music, LrclibClient lrclib)
{
    public const string LrclibSource = "LRCLIB";
    private const int CacheSize = 50;

    public abstract record Result
    {
        public sealed record Found(SongLyrics Lyrics) : Result;
        public sealed record None : Result;
        public sealed record Failed : Result;
    }

    private readonly Dictionary<string, Result> cache = new();
    private readonly LinkedList<string> order = new();
    private readonly object gate = new();

    public async Task<Result> LyricsAsync(Song song, CancellationToken cancel = default)
    {
        lock (gate)
        {
            if (cache.TryGetValue(song.VideoId, out var hit)) return hit;
        }
        var syncedTask = song.Artists.Count == 0
            ? Task.FromResult<LrclibClient.Result>(new LrclibClient.Result.NotFound())
            : lrclib.SyncedAsync(song.Title, song.Artists[0].Name, song.Album?.Title, song.DurationSeconds, cancel);
        var plainTask = music.PlainLyricsAsync(song.VideoId, cancel);
        var synced = await syncedTask;
        var plain = await plainTask;
        Result result;
        if (synced is LrclibClient.Result.Found found) result = new Result.Found(new SongLyrics.Synced(found.Lines, LrclibSource));
        else if (!plain.IsOk) result = new Result.Failed();
        else if (plain.Value is { } text) result = new Result.Found(text);
        else result = synced is LrclibClient.Result.Failed ? new Result.Failed() : new Result.None();
        if (result is not Result.Failed)
        {
            lock (gate)
            {
                cache[song.VideoId] = result;
                order.AddLast(song.VideoId);
                while (order.Count > CacheSize)
                {
                    cache.Remove(order.First!.Value);
                    order.RemoveFirst();
                }
            }
        }
        return result;
    }
}
