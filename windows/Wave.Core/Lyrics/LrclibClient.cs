using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Wave.Core.Lyrics;

/// <summary>
/// LRCLIB（lrclib.net）から時刻付きの歌詞を探す。送るのは曲名・アーティスト名・アルバム名・長さだけ。
/// http には YouTube の cookie を持たないクライアントを渡す。
/// </summary>
public sealed partial class LrclibClient(HttpClient http, string userAgent, string baseUrl = "https://lrclib.net")
{
    public abstract record Result
    {
        public sealed record Found(IReadOnlyList<LyricLine> Lines) : Result;
        public sealed record NotFound : Result;
        public sealed record Failed : Result;
    }

    internal sealed record Track(
        [property: JsonPropertyName("trackName")] string? TrackName,
        [property: JsonPropertyName("artistName")] string? ArtistName,
        [property: JsonPropertyName("albumName")] string? AlbumName,
        [property: JsonPropertyName("duration")] double? Duration,
        [property: JsonPropertyName("instrumental")] bool Instrumental,
        [property: JsonPropertyName("syncedLyrics")] string? SyncedLyrics);

    /// <summary>
    /// 曲の、もう1つの言語（画面が日本語なら英語、それ以外なら日本語）での曲名とアーティスト名。
    /// YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」のように、言語で表記の違う曲を一致させるために使う。
    /// </summary>
    public sealed record OtherNames(string Title, string Artist);

    private static readonly double[] DurationTolerances = [2.0, 5.0];

    /// <summary>
    /// 時刻付きの歌詞を探す。まず曲名・アーティスト・アルバム・長さで1件を引き、無ければ検索して曲名とアーティストの合うもののうち長さの近いものを選ぶ。
    /// 検索の結果に時刻付きの候補があるのに1つも一致しなければ、otherNames で曲名・アーティスト名の別名を取り、足してもう一度選ぶ（otherNames は例外を投げない）。
    /// </summary>
    public async Task<Result> SyncedAsync(
        string title,
        string artist,
        string? album,
        int? durationSeconds,
        CancellationToken cancel = default,
        Func<CancellationToken, Task<OtherNames?>>? otherNames = null)
    {
        try
        {
            if (await ExactAsync(title, artist, album, durationSeconds, cancel) is { } exact) return new Result.Found(exact);
            var titles = SearchTitles(title);
            var seen = new List<Track>();
            foreach (var t in titles)
            {
                var tracks = await SearchAsync(t, artist, cancel);
                if (Pick(tracks, title, artist, durationSeconds) is { } picked && LinesOf(picked) is { } found) return new Result.Found(found);
                seen.AddRange(tracks);
            }
            // アーティスト名を付けて探すと、LRCLIB で別の表記（ローマ字など）で登録された曲は返らない。曲名だけでも探して候補に足す
            foreach (var t in titles) seen.AddRange(await SearchAsync(t, null, cancel));
            var other = await PickWithOtherNamesAsync(seen, title, artist, durationSeconds, () => otherNames?.Invoke(cancel) ?? Task.FromResult<OtherNames?>(null));
            return other is not null && LinesOf(other) is { } lines ? new Result.Found(lines) : new Result.NotFound();
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return new Result.Failed();
        }
    }

    private async Task<List<LyricLine>?> ExactAsync(string title, string artist, string? album, int? durationSeconds, CancellationToken cancel)
    {
        var query = $"track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}";
        if (album is not null) query += $"&album_name={Uri.EscapeDataString(album)}";
        if (durationSeconds is not null) query += $"&duration={durationSeconds}";
        using var response = await SendAsync($"{baseUrl}/api/get?{query}", cancel);
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException($"lrclib get status={(int)response.StatusCode}");
        var track = JsonSerializer.Deserialize<Track>(await response.Content.ReadAsStringAsync(cancel));
        return track is null ? null : LinesOf(track);
    }

    private async Task<List<Track>> SearchAsync(string searchTitle, string? artist, CancellationToken cancel)
    {
        var url = $"{baseUrl}/api/search?track_name={Uri.EscapeDataString(searchTitle)}";
        if (artist is not null) url += $"&artist_name={Uri.EscapeDataString(artist)}";
        using var response = await SendAsync(url, cancel);
        if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException($"lrclib search status={(int)response.StatusCode}");
        return JsonSerializer.Deserialize<List<Track>>(await response.Content.ReadAsStringAsync(cancel)) ?? [];
    }

    private Task<HttpResponseMessage> SendAsync(string url, CancellationToken cancel)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        return http.SendAsync(request, cancel);
    }

    private static List<LyricLine>? LinesOf(Track track)
    {
        if (track.Instrumental || string.IsNullOrEmpty(track.SyncedLyrics)) return null;
        var lines = Lrc.Parse(track.SyncedLyrics);
        return lines.Any(l => !string.IsNullOrWhiteSpace(l.Text)) ? lines : null;
    }

    /// <summary>
    /// 検索の結果から、曲名とアーティストが一致する時刻付きのうち長さの近いものを選ぶ。長さが分からなければ最初の一致。
    /// 一致するものが無ければ選ばない（長さだけが近い別の曲を選ばないため）。
    /// 曲名が一致しなくても、アーティストが一致して長さが <see cref="SameLengthWithoutTitle"/> 秒以内なら残す。
    /// 照らし合わせは Android・Apple・Linux の版と同じ規則。
    /// </summary>
    internal static Track? Pick(List<Track> tracks, string title, string artist, int? durationSeconds) =>
        PickAny(tracks, [title], [artist], durationSeconds);

    /// <summary>
    /// 元の名前で選び、時刻付きの候補があるのに1つも一致しなければ、別名（otherNames）を足してもう一度選ぶ。
    /// 一致したとき・時刻付きの候補が無いときは otherNames を呼ばない（別名を取りに行かない）。
    /// </summary>
    internal static async Task<Track?> PickWithOtherNamesAsync(List<Track> tracks, string title, string artist, int? durationSeconds, Func<Task<OtherNames?>> otherNames)
    {
        if (Pick(tracks, title, artist, durationSeconds) is { } picked) return picked;
        if (!tracks.Any(IsSynced) || await otherNames() is not { } other) return null;
        return PickAny(tracks, [title, other.Title], [artist, other.Artist], durationSeconds);
    }

    private static bool IsSynced(Track t) => !t.Instrumental && !string.IsNullOrWhiteSpace(t.SyncedLyrics);

    /// <summary><see cref="Pick"/> と同じだが、曲名・アーティストはそれぞれ titles・artists のどれかに一致すれば良い（元の名前と別名）。</summary>
    private static Track? PickAny(List<Track> tracks, List<string> titles, List<string> artists, int? durationSeconds)
    {
        var wantedTitles = titles.SelectMany(TitleKeys).ToList();
        var wantedArtists = artists.SelectMany(ArtistKeys).ToList();
        var matching = tracks
            .Where(IsSynced)
            .Where(t => Overlap(wantedArtists, ArtistKeys(t.ArtistName ?? ""))
                && (Overlap(wantedTitles, TitleKeys(t.TrackName ?? ""))
                    || (durationSeconds is { } want && t.Duration is { } d && Math.Abs(d - want) <= SameLengthWithoutTitle)))
            .ToList();
        if (durationSeconds is null) return matching.FirstOrDefault();
        foreach (var tolerance in DurationTolerances)
        {
            var hit = matching.Where(t => t.Duration is { } d && Math.Abs(d - durationSeconds.Value) <= tolerance)
                .OrderBy(t => Math.Abs(t.Duration!.Value - durationSeconds.Value)).FirstOrDefault();
            if (hit is not null) return hit;
        }
        return null;
    }

    /// <summary>曲名が一致しなくても、アーティストが一致して長さがこの秒数以内なら同じ曲とみなす（日本語の曲名がローマ字・英語で登録されていることがあるため）。</summary>
    internal const double SameLengthWithoutTitle = 2.0;

    /// <summary>比べる形。NFKC にして小文字にし、文字（L）と数字（N）だけを残す。</summary>
    internal static string MatchKey(string text)
    {
        var sb = new StringBuilder();
        foreach (var rune in text.Normalize(NormalizationForm.FormKC).ToLowerInvariant().EnumerateRunes())
        {
            switch (Rune.GetUnicodeCategory(rune))
            {
                case UnicodeCategory.UppercaseLetter or UnicodeCategory.LowercaseLetter or UnicodeCategory.TitlecaseLetter
                    or UnicodeCategory.ModifierLetter or UnicodeCategory.OtherLetter
                    or UnicodeCategory.DecimalDigitNumber or UnicodeCategory.LetterNumber or UnicodeCategory.OtherNumber:
                    sb.Append(rune.ToString());
                    break;
            }
        }
        return sb.ToString();
    }

    /// <summary>曲名を比べる形。そのまま、括弧の中の補足を除いた形、「 - 」の後ろを除いた形、「feat.」の後ろを除いた形。</summary>
    internal static List<string> TitleKeys(string title)
    {
        var full = title.Normalize(NormalizationForm.FormKC);
        var withoutBrackets = Brackets().Replace(full, "");
        var beforeDash = Dash().Split(withoutBrackets)[0];
        var beforeFeat = Feat().Split(beforeDash)[0];
        return DistinctKeys([full, withoutBrackets, beforeDash, beforeFeat]);
    }

    /// <summary>アーティストを比べる形。全体と、「,」「&amp;」「feat.」「×」などで分けたそれぞれの名前。</summary>
    internal static List<string> ArtistKeys(string artist)
    {
        var full = artist.Normalize(NormalizationForm.FormKC);
        return DistinctKeys([full, .. ArtistSeparators().Split(full)]);
    }

    private static List<string> DistinctKeys(IEnumerable<string> texts) => texts.Select(MatchKey).Where(k => k.Length > 0).Distinct().ToList();

    /// <summary>どれかの形が等しいか、片方がもう片方を含めば一致。</summary>
    private static bool Overlap(List<string> a, List<string> b) =>
        a.Any(x => b.Any(y => x.Contains(y, StringComparison.Ordinal) || y.Contains(x, StringComparison.Ordinal)));

    /// <summary>検索に使う曲名。「(From "THE FIRST TAKE")」「[Remastered]」「 - Orion」のような添え書きを外したものも試す。</summary>
    internal static List<string> SearchTitles(string title)
    {
        var withoutBrackets = Brackets().Replace(title, "").Trim();
        var dash = withoutBrackets.IndexOf(" - ", StringComparison.Ordinal);
        var beforeDash = (dash >= 0 ? withoutBrackets[..dash] : withoutBrackets).Trim();
        return new[] { title, withoutBrackets, beforeDash }.Where(s => !string.IsNullOrWhiteSpace(s)).Distinct().ToList();
    }

    [GeneratedRegex(@"\s*[(\[（【][^)\]）】]*[)\]）】]")]
    private static partial Regex Brackets();

    [GeneratedRegex(@"\s[-–—]\s")]
    private static partial Regex Dash();

    [GeneratedRegex(@"\s(?:feat\.?|ft\.|featuring)\s", RegexOptions.IgnoreCase)]
    private static partial Regex Feat();

    [GeneratedRegex(@"\s*[,&、/;×]\s*|\s+(?:x|feat\.?|ft\.|featuring|vs\.?)\s+", RegexOptions.IgnoreCase)]
    private static partial Regex ArtistSeparators();
}
