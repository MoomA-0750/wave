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

    private static readonly double[] DurationTolerances = [2.0, 5.0];

    public async Task<Result> SyncedAsync(string title, string artist, string? album, int? durationSeconds, CancellationToken cancel = default)
    {
        try
        {
            if (await ExactAsync(title, artist, album, durationSeconds, cancel) is { } exact) return new Result.Found(exact);
            foreach (var t in SearchTitles(title))
            {
                if (await SearchAsync(t, title, artist, durationSeconds, cancel) is { } found) return new Result.Found(found);
            }
            return new Result.NotFound();
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

    private async Task<List<LyricLine>?> SearchAsync(string searchTitle, string title, string artist, int? durationSeconds, CancellationToken cancel)
    {
        using var response = await SendAsync($"{baseUrl}/api/search?track_name={Uri.EscapeDataString(searchTitle)}&artist_name={Uri.EscapeDataString(artist)}", cancel);
        if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException($"lrclib search status={(int)response.StatusCode}");
        var tracks = JsonSerializer.Deserialize<List<Track>>(await response.Content.ReadAsStringAsync(cancel)) ?? [];
        return Pick(tracks, title, artist, durationSeconds) is { } picked ? LinesOf(picked) : null;
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
    internal static Track? Pick(List<Track> tracks, string title, string artist, int? durationSeconds)
    {
        var wantedTitles = TitleKeys(title);
        var wantedArtists = ArtistKeys(artist);
        var matching = tracks
            .Where(t => !t.Instrumental && !string.IsNullOrWhiteSpace(t.SyncedLyrics))
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
