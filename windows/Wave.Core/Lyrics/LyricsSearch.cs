using System.Text;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Lyrics;

/// <summary>歌詞の中で、打った言葉が見つかった行。StartMs は時刻付きの歌詞のときだけ。Exact は言葉がそのまま入っていたか（false は大体合っている）。</summary>
public sealed record LyricsMatch(string Line, long? StartMs, bool Exact);

/// <summary>歌詞から探した結果の1曲。Match が null なら、歌詞が取れなかったか、言葉が見つからなかった（検索の順で後ろに並べる）。</summary>
public sealed record LyricsHit(Song Song, LyricsMatch? Match);

/// <summary>
/// 歌詞の一節から曲を探す（Android 版の LyricsSearch と同じ）。外部の歌詞検索サービスは使わず、
/// YouTube Music の絞り込まない検索（歌詞の一節でも曲が上に来る）の上位の曲の歌詞を取り、打った言葉を含む曲を前に出す。
/// 歌詞は再生画面と同じ <see cref="LyricsService"/>（LRCLIB の時刻付き、無ければ YouTube Music）から取る。
/// </summary>
public sealed class LyricsSearch(
    Func<string, CancellationToken, Task<ApiResult<List<Song>>>> candidates,
    Func<Song, CancellationToken, Task<LyricsService.Result>> lyrics)
{
    /// <summary>歌詞を照らし合わせる、検索の上位の曲の数。</summary>
    public const int Candidates = 6;

    public LyricsSearch(MusicRepository music, LyricsService service) : this(music.SearchTopSongsAsync, service.LyricsAsync) { }

    public async Task<ApiResult<List<LyricsHit>>> SearchAsync(string query, CancellationToken cancel = default)
    {
        if (LyricsMatcher.Normalize(query).Length == 0) return ApiResult<List<LyricsHit>>.Ok([]);
        var found = await candidates(query, cancel);
        if (!found.IsOk) return ApiResult<List<LyricsHit>>.Failed(found.Failure!.Value, found.Status);
        var picked = DistinctSongs(found.Value!).Take(Candidates);
        var hits = await Task.WhenAll(picked.Select(async song =>
        {
            var match = await lyrics(song, cancel) is LyricsService.Result.Found f ? LyricsMatcher.Find(f.Lyrics, query) : null;
            return new LyricsHit(song, match);
        }));
        return ApiResult<List<LyricsHit>>.Ok(Rank(hits));
    }

    /// <summary>
    /// そのまま入っていた曲、大体合っている曲、見つからなかった曲、見つからなかった投稿者の動画（歌ってみた・歌詞動画など）の順。
    /// 同じ組の中は検索の順のまま。
    /// </summary>
    public static List<LyricsHit> Rank(IEnumerable<LyricsHit> hits) =>
        hits.OrderBy(h => h.Match?.Exact == true ? 0 : h.Match is not null ? 1 : h.Song.Kind != Song.KindUserVideo ? 2 : 3).ToList();

    /// <summary>
    /// 同じ曲の音源と MV が両方出たら、先に出た位置に音源を残す（曲として流すため）。ライブ配信は除く。
    /// 同じ曲かは、曲名と最初のアーティストを正規化して比べる。
    /// </summary>
    public static List<Song> DistinctSongs(IEnumerable<Song> songs)
    {
        var result = new List<Song>();
        var index = new Dictionary<string, int>();
        foreach (var song in songs)
        {
            if (song.IsLive || result.Any(s => s.VideoId == song.VideoId)) continue;
            var key = LyricsMatcher.Normalize(song.Title) + "\0" + LyricsMatcher.Normalize(song.Artists.FirstOrDefault()?.Name ?? "");
            if (!index.TryGetValue(key, out var at))
            {
                index[key] = result.Count;
                result.Add(song);
            }
            else if (song.Kind == Song.KindSong && result[at].Kind != Song.KindSong) result[at] = song;
        }
        return result;
    }
}

/// <summary>歌詞の行と打った言葉の照らし合わせ。画面にも通信にも依らない。</summary>
public static class LyricsMatcher
{
    /// <summary>大体合っているとみなす、言葉の2文字の組が行に入っている割合。</summary>
    public const double ApproximateRatio = 0.7;

    /// <summary>
    /// 比べやすい形にする。小文字にし、全角の英数字を半角に、カタカナをひらがなにし、文字と数字以外（空白・句読点・記号）を除く。
    /// 「i'm」と「im」、「夜に駆ける」と「夜にかける」の打ち方の違いを吸収する（漢字とかなの違いは吸収しない）。
    /// </summary>
    public static string Normalize(string text)
    {
        var builder = new StringBuilder(text.Length);
        foreach (var raw in text)
        {
            var c = raw;
            if (c is >= '！' and <= '～') c = (char)(c - 0xFEE0);
            if (c is >= 'ァ' and <= 'ヶ') c = (char)(c - 0x60);
            if (char.IsLetterOrDigit(c)) builder.Append(char.ToLowerInvariant(c));
        }
        return builder.ToString();
    }

    /// <summary>打った言葉を含む最初の行。行をまたいだ言葉も、その行と次の行をつないで探す。無ければ大体合っている行（一番近いもの）。</summary>
    public static LyricsMatch? Find(SongLyrics lyrics, string query)
    {
        var lines = (lyrics switch
        {
            SongLyrics.Synced s => s.Lines.Select(l => (Text: l.Text, StartMs: (long?)l.StartMs)),
            SongLyrics.Plain p => p.Text.Split(["\r\n", "\n", "\r"], StringSplitOptions.None).Select(t => (Text: t, StartMs: (long?)null)),
            _ => [],
        }).Where(l => !string.IsNullOrWhiteSpace(l.Text)).ToList();
        var q = Normalize(query);
        if (q.Length == 0 || lines.Count == 0) return null;
        var normalized = lines.Select(l => Normalize(l.Text)).ToList();
        var spans = normalized.Select((n, i) => n + (i + 1 < normalized.Count ? normalized[i + 1] : "")).ToList();
        // 1行に収まる一致を先に探す（つないだ方だけで探すと、次の行だけに入っている言葉を前の行の一致にしてしまう）
        var exact = normalized.FindIndex(n => n.Contains(q, StringComparison.Ordinal));
        if (exact < 0) exact = spans.FindIndex(s => s.Contains(q, StringComparison.Ordinal));
        if (exact >= 0) return new LyricsMatch(lines[exact].Text.Trim(), lines[exact].StartMs, Exact: true);
        if (q.Length < 4) return null;
        var wanted = Bigrams(q);
        var best = -1;
        var bestRatio = 0.0;
        for (var i = 0; i < spans.Count; i++)
        {
            var have = Bigrams(spans[i]);
            var ratio = (double)wanted.Count(have.Contains) / wanted.Count;
            if (ratio > bestRatio)
            {
                best = i;
                bestRatio = ratio;
            }
        }
        return best >= 0 && bestRatio >= ApproximateRatio ? new LyricsMatch(lines[best].Text.Trim(), lines[best].StartMs, Exact: false) : null;
    }

    private static HashSet<string> Bigrams(string text)
    {
        var set = new HashSet<string>(StringComparer.Ordinal);
        for (var i = 0; i + 2 <= text.Length; i++) set.Add(text.Substring(i, 2));
        return set;
    }
}
