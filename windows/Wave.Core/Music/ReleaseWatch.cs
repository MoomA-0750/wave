using System.Text.Json;

namespace Wave.Core.Music;

/// <summary>登録したアーティストの新作（アルバム・シングル・動画）。Android の NewRelease と同じ。</summary>
public sealed record NewRelease(
    string ArtistId,
    string ArtistName,
    string Title,
    string? AlbumBrowseId,
    string? VideoId,
    string? ThumbnailUrl,
    long FoundAtEpochMs = 0,
    // 初めて見たときに、今年の作品として覚えたもの（知らせていない）
    bool Seeded = false)
{
    public string Key => AlbumBrowseId ?? VideoId ?? Title;
}

/// <summary>
/// 新作の見張り（Android の ReleaseWatchState と同じ）。アーティストごとに前に見たアルバム・動画の ID を覚え、増えたアルバム・シングルのうち今年のものを新作とする。
/// 初めて見るアーティストは知らせず、今年の作品だけを「最近の新作」として覚える。
/// </summary>
public sealed record ReleaseWatchState(Dictionary<string, HashSet<string>> Seen, List<NewRelease> Recent)
{
    private const int MaxIdsPerArtist = 300;
    private const int MaxRecent = 60;
    public const long RecentMaxAgeMs = 45L * 24 * 60 * 60 * 1000;
    private static readonly JsonSerializerOptions Options = new();

    public static ReleaseWatchState Empty => new([], []);

    public (List<NewRelease> Fresh, ReleaseWatchState Next) Observe(string artistId, ArtistPage page, long nowEpochMs, int? year)
    {
        var albums = page.AlbumShelves.SelectMany(s => s.Albums).DistinctBy(a => a.BrowseId).ToList();
        var ids = albums.Select(a => "a:" + a.BrowseId).Concat(page.Videos.Select(v => "v:" + v.VideoId)).ToList();
        // 棚が読めなかった（空）ときは覚えている分を消さない
        if (ids.Count == 0) return ([], this);
        Seen.TryGetValue(artistId, out var before);
        var merged = new HashSet<string>(ids.Concat((before ?? []).Where(i => !ids.Contains(i))).Take(MaxIdsPerArtist));
        var next = this with { Seen = new Dictionary<string, HashSet<string>>(Seen) { [artistId] = merged }, Recent = Listed(Recent, artistId, page, year) };
        if (before is null)
        {
            if (year is not { } y) return ([], next);
            var seeds = albums.Where(a => a.Subtitle?.Contains(y.ToString()) == true)
                .Select(a => new NewRelease(artistId, page.Name, a.Title, a.BrowseId, null, a.ThumbnailUrl, nowEpochMs, Seeded: true)).ToList();
            return ([], next.WithRecent(seeds));
        }
        // 新作は「初めて見た」だけでなく、今年の作品（補足に今年の年がある）に限る（Android と同じ）。棚の中身は入れ替わるので、
        // 何年も前の作品が棚に入り直しただけでも初めて見る扱いになり、通知が大量に来ていた。動画の棚は新作として扱わない（覚えるだけ）
        var thisYear = year?.ToString();
        var fresh = albums.Where(a => !before.Contains("a:" + a.BrowseId) && (thisYear is null || a.Subtitle?.Contains(thisYear) == true))
            .Select(a => new NewRelease(artistId, page.Name, a.Title, a.BrowseId, null, a.ThumbnailUrl, nowEpochMs))
            .ToList();
        return (fresh, next.WithRecent(fresh));
    }

    /// <summary>
    /// アーティストのページから消えた新作を外す（ほかのアーティストの分はそのまま。Android の listedReleases と同じ）。
    /// 同じ名前の別人の作品が YouTube 側で同じアーティストにまとめられ、後で外されることがある（その間に新作として覚えてしまう）。
    /// 棚がどれも今年より前の作品まで並べているのに無いものは、外されたものとみなす。
    /// 今年の作品だけの棚がある（新しい作品に押し出されただけかもしれない）・棚が読めない・年が分からないときは何も外さない。
    /// </summary>
    public static List<NewRelease> Listed(List<NewRelease> releases, string artistId, ArtistPage page, int? year)
    {
        var shelves = page.AlbumShelves.Where(s => s.Albums.Count > 0).ToList();
        if (year is not { } y || shelves.Count == 0) return releases;
        if (!shelves.All(s => s.Albums.Any(a => YearOf(a.Subtitle) < y))) return releases;
        var listed = shelves.SelectMany(s => s.Albums).Select(a => a.BrowseId).ToHashSet();
        return releases.Where(r => r.ArtistId != artistId || r.AlbumBrowseId is null || listed.Contains(r.AlbumBrowseId)).ToList();
    }

    /// <summary>補足（「Single • 2023」など）の年。</summary>
    private static int? YearOf(string? subtitle) =>
        subtitle is null ? null : System.Text.RegularExpressions.Regex.Matches(subtitle, @"\b(19|20)\d\d\b").LastOrDefault() is { } m ? int.Parse(m.Value) : null;

    private ReleaseWatchState WithRecent(List<NewRelease> added)
    {
        if (added.Count == 0) return this;
        var all = added.Concat(Recent).DistinctBy(r => r.Key).ToList();
        return this with { Recent = all.Where(r => !r.Seeded).Concat(all.Where(r => r.Seeded)).Take(MaxRecent).ToList() };
    }

    public ReleaseWatchState Retain(ISet<string> artistIds) =>
        new(Seen.Where(kv => artistIds.Contains(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value), Recent.Where(r => artistIds.Contains(r.ArtistId)).ToList());

    public ReleaseWatchState Expire(long nowEpochMs, long maxAgeMs = RecentMaxAgeMs) => this with { Recent = Recent.Where(r => nowEpochMs - r.FoundAtEpochMs <= maxAgeMs && r.VideoId is null).ToList() }; // 動画は新作として扱わなくなった（前に覚えた分も外す）

    public static ReleaseWatchState? Decode(string text)
    {
        try
        {
            return JsonSerializer.Deserialize<ReleaseWatchState>(text, Options);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public string Encode() => JsonSerializer.Serialize(this, Options);

    /// <summary>棚に出す並び: アーティストごとに perArtist 件まで、アーティストが交互になるように（見つけた新作を先に）。</summary>
    public static List<NewRelease> Diversified(IEnumerable<NewRelease> releases, int perArtist = 2)
    {
        static List<NewRelease> RoundRobin(IEnumerable<NewRelease> items, int per)
        {
            var groups = items.GroupBy(r => r.ArtistId).Select(g => g.Take(per).ToList()).ToList();
            return Enumerable.Range(0, per).SelectMany(round => groups.Where(g => round < g.Count).Select(g => g[round])).ToList();
        }
        var list = releases.ToList();
        return [.. RoundRobin(list.Where(r => !r.Seeded), perArtist), .. RoundRobin(list.Where(r => r.Seeded), perArtist)];
    }
}
