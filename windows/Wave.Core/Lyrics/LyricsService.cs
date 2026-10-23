using Wave.Core.Net;
using Wave.Core.Music;

namespace Wave.Core.Lyrics;

/// <summary>
/// 曲の歌詞を選ぶ（Android 版の LyricsRepository と同じ）。YouTube Music の時刻付き（ANDROID_MUSIC として匿名で読む。曲の照合が要らず、
/// 出典は Musixmatch・LyricFind）、無ければ LRCLIB の時刻付き（曲名・アーティスト・長さで照らし合わせる）、それも無ければ YouTube Music の時刻なし。
/// 見つかった・無かった結果は曲ごとに覚える。失敗は覚えない。時刻付きを探すのに失敗して時刻なしを出したときも覚えない
/// （一時の失敗で、時刻なしが残り続けないように）。
/// </summary>
public sealed class LyricsService(MusicRepository music, LrclibClient lrclib, YouTubeTimedLyricsClient? timed = null)
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
        var tab = await music.LyricsBrowseIdAsync(song.VideoId, cancel);
        var browseId = tab.IsOk ? tab.Value : null;
        // 時刻付きと時刻なしを同時に尋ねる（どちらも歌詞のタブの ID だけを送る）
        var timedTask = browseId is not null && timed is not null ? timed.TimedAsync(browseId, cancel) : null;
        var plainTask = browseId is not null
            ? music.PlainLyricsOfTabAsync(browseId, cancel)
            : Task.FromResult(tab.IsOk ? ApiResult<SongLyrics.Plain?>.Ok(null) : ApiResult<SongLyrics.Plain?>.Failed(tab.Failure!.Value, tab.Status));
        var timedFailed = false;
        LrclibClient.Result synced = new LrclibClient.Result.NotFound();
        Result result;
        var fromYouTube = timedTask is null ? null : await timedTask;
        if (fromYouTube is YouTubeTimedLyricsClient.Result.Found found)
        {
            // YouTube Music で取れたら LRCLIB には聞かない
            result = new Result.Found(found.Lyrics);
        }
        else
        {
            timedFailed = fromYouTube is YouTubeTimedLyricsClient.Result.Failed;
            if (song.Artists.Count > 0) synced = await lrclib.SyncedAsync(song.Title, song.Artists[0].Name, song.Album?.Title, song.DurationSeconds, cancel);
            result = Choose(synced, await plainTask);
        }
        var provisional = result is Result.Found { Lyrics: SongLyrics.Plain } && (timedFailed || synced is LrclibClient.Result.Failed);
        if (result is not Result.Failed && !provisional)
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

    /// <summary>時刻付きを優先する。片方が失敗していて、もう片方に無ければ「分からない」（無いとは決めない）。</summary>
    internal static Result Choose(LrclibClient.Result synced, ApiResult<SongLyrics.Plain?> plain)
    {
        if (synced is LrclibClient.Result.Found found) return new Result.Found(new SongLyrics.Synced(found.Lines, LrclibSource));
        if (!plain.IsOk) return new Result.Failed();
        if (plain.Value is { } text) return new Result.Found(text);
        return synced is LrclibClient.Result.Failed ? new Result.Failed() : new Result.None();
    }
}
