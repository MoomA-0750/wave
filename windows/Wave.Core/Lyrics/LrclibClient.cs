using System.Net;
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
                if (await SearchAsync(t, artist, durationSeconds, cancel) is { } found) return new Result.Found(found);
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

    private async Task<List<LyricLine>?> SearchAsync(string title, string artist, int? durationSeconds, CancellationToken cancel)
    {
        using var response = await SendAsync($"{baseUrl}/api/search?track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}", cancel);
        if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException($"lrclib search status={(int)response.StatusCode}");
        var tracks = JsonSerializer.Deserialize<List<Track>>(await response.Content.ReadAsStringAsync(cancel)) ?? [];
        return Pick(tracks, durationSeconds) is { } picked ? LinesOf(picked) : null;
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

    /// <summary>検索の結果から、時刻付きで長さの近いものを選ぶ。長さが分からなければ最初の時刻付き。</summary>
    internal static Track? Pick(List<Track> tracks, int? durationSeconds)
    {
        var synced = tracks.Where(t => !t.Instrumental && !string.IsNullOrWhiteSpace(t.SyncedLyrics)).ToList();
        if (durationSeconds is null) return synced.FirstOrDefault();
        foreach (var tolerance in DurationTolerances)
        {
            var hit = synced.Where(t => t.Duration is { } d && Math.Abs(d - durationSeconds.Value) <= tolerance)
                .OrderBy(t => Math.Abs(t.Duration!.Value - durationSeconds.Value)).FirstOrDefault();
            if (hit is not null) return hit;
        }
        return null;
    }

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
}
