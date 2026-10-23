using Wave.Core.Music;

namespace Wave.Core.Downloads;

/// <summary>ダウンロードに使う通信（ADR-0016 の 8）。</summary>
public enum DownloadNetwork
{
    /// <summary>Wi-Fi など従量制でない通信だけ（既定）。</summary>
    Unmetered,
    /// <summary>どの通信でも。</summary>
    Any,
}

public static class DownloadPolicy
{
    /// <summary>今の通信で取ってよいか。</summary>
    public static bool Allows(DownloadNetwork setting, bool connected, bool metered) => connected && (setting == DownloadNetwork.Any || !metered);

    public static DownloadNetwork Parse(string? value) => value == "any" ? DownloadNetwork.Any : DownloadNetwork.Unmetered;

    public static string Format(DownloadNetwork value) => value == DownloadNetwork.Any ? "any" : "unmetered";
}

/// <summary>
/// スマートダウンロードの曲選び（ADR-0016 の 10・11・12）。3つの分類（いつも聴いている・好きそう・おすすめ）から1曲ずつ順番に取り、
/// 同じアーティストは全体で2曲まで、分類どうしで重なった曲は先に入った分類に入れる。足りない分類の分は、ほかの分類で埋める。
/// </summary>
public static class SmartDownloads
{
    /// <summary>選べる曲数と既定。</summary>
    public static readonly int[] Counts = [25, 50, 100, 200];
    public const int DefaultCount = 50;
    /// <summary>「好きそう」のラジオの種にする「いつも聴いている」の上位の曲数。</summary>
    public const int MaxSeeds = 5;
    public const int MaxPerArtist = 2;
    /// <summary>選び直す間隔（1日1回）。</summary>
    public const long RefreshIntervalMs = 24L * 60 * 60 * 1000;

    public static int NormalizeCount(int count) => Counts.Contains(count) ? count : DefaultCount;

    /// <summary>選び直す頃か。</summary>
    public static bool IsDue(long refreshedAtMs, long nowMs) => refreshedAtMs <= 0 || nowMs - refreshedAtMs >= RefreshIntervalMs || nowMs < refreshedAtMs;

    /// <summary>これより長い曲は選ばない（再生履歴に出る1時間の動画などを取らない）。長さが分からなければ選んでよい。</summary>
    public const int MaxDurationSeconds = 15 * 60;

    /// <summary>曲（音源・公式の MV）か。投稿者の動画（作業用のまとめなど）やエピソードは選ばない。種類が分からなければ選んでよい。</summary>
    private static bool IsMusic(Song song) => song.Kind is null or Song.KindSong or Song.KindMusicVideo;

    private static bool Usable(Song song) => !song.IsLive && !Feed.IsExternal(song.VideoId) && IsMusic(song) && (song.DurationSeconds is not { } d || d <= MaxDurationSeconds);

    /// <summary>
    /// 「いつも聴いている」の候補: この端末の再生回数と、YouTube の再生履歴（新しい順）に出る曲。回数（履歴に出た回も1回と数える）の多い順、
    /// 同じなら新しい順（この端末で最後に聴いた時刻。履歴にだけある曲は履歴の並び）。
    /// </summary>
    public static List<Song> Frequent(IEnumerable<PlayCount> counts, IEnumerable<Song> history)
    {
        var entries = new Dictionary<string, (Song Song, int Count, long Last, int HistoryRank)>();
        foreach (var c in counts.Where(c => Usable(c.Song))) entries[c.Song.VideoId] = (c.Song, c.Count, c.LastPlayedMs, int.MaxValue);
        var rank = 0;
        foreach (var song in history.Where(Usable))
        {
            rank++;
            entries[song.VideoId] = entries.TryGetValue(song.VideoId, out var e) ? e with { Count = e.Count + 1, HistoryRank = Math.Min(e.HistoryRank, rank) } : (song, 1, 0, rank);
        }
        return entries.Values.OrderByDescending(e => e.Count).ThenByDescending(e => e.Last).ThenBy(e => e.HistoryRank).Select(e => e.Song).ToList();
    }

    /// <summary>
    /// 「好きそう」の候補: 種ごとのラジオ（RDAMVM&lt;videoId&gt;）から、聴いたことのある曲（再生回数・再生履歴・高く評価した曲）を除き、種ごとに順番に1曲ずつ。
    /// </summary>
    public static List<Song> Likely(IReadOnlyList<IReadOnlyList<Song>> radios, IReadOnlySet<string> known)
    {
        var queues = radios.Select(r => new Queue<Song>(r.Where(s => Usable(s) && !known.Contains(s.VideoId)))).ToList();
        var result = new List<Song>();
        var seen = new HashSet<string>();
        while (queues.Any(q => q.Count > 0))
        {
            foreach (var queue in queues)
            {
                while (queue.Count > 0)
                {
                    var song = queue.Dequeue();
                    if (!seen.Add(song.VideoId)) continue;
                    result.Add(song);
                    break;
                }
            }
        }
        return result;
    }

    /// <summary>「おすすめ」の候補: ホームの棚のうち、曲が並んだ棚（半分より多くが曲）の曲。</summary>
    public static List<Song> Recommended(IEnumerable<HomeShelf> shelves) =>
        shelves.Where(s => s.Items.Count > 0 && s.Items.Count(i => i is HomeItem.SongItem) * 2 > s.Items.Count)
            .SelectMany(s => s.Items.OfType<HomeItem.SongItem>().Select(i => i.Song)).Where(Usable).DistinctBy(s => s.VideoId).ToList();

    /// <summary>ホームの最初のミックス（RD で始まるプレイリスト。無ければ最初のプレイリスト）。「おすすめ」が足りないときに使う。</summary>
    public static PlaylistSummary? FirstMix(IEnumerable<HomeShelf> shelves)
    {
        var playlists = shelves.SelectMany(s => s.Items.OfType<HomeItem.PlaylistItem>().Select(i => i.Playlist)).ToList();
        return playlists.FirstOrDefault(p => p.PlaylistId.StartsWith("RD", StringComparison.Ordinal)) ?? playlists.FirstOrDefault();
    }

    /// <summary>アーティストの数え方（最初のアーティストの ID、無ければ名前）。アーティストの無い曲は数えない。</summary>
    private static string? ArtistKey(Song song) =>
        song.Artists.FirstOrDefault() is { } a ? a.BrowseId ?? "name:" + a.Name.Trim().ToLowerInvariant() : null;

    /// <summary>3つの分類から1曲ずつ順番に、count 曲まで選ぶ。</summary>
    public static List<SmartTrack> Select(IReadOnlyList<Song> frequent, IReadOnlyList<Song> likely, IReadOnlyList<Song> recommended, int count)
    {
        var sources = new (SmartCategory Category, IReadOnlyList<Song> Songs)[] { (SmartCategory.Frequent, frequent), (SmartCategory.Likely, likely), (SmartCategory.Recommended, recommended) };
        var cursors = new int[sources.Length];
        var chosen = new List<SmartTrack>();
        var ids = new HashSet<string>();
        var perArtist = new Dictionary<string, int>();
        // 1周で1曲ずつ。取れる曲が残っていない分類は飛ばす（ほかの分類で埋まる）
        var progressed = true;
        while (chosen.Count < count && progressed)
        {
            progressed = false;
            for (var i = 0; i < sources.Length && chosen.Count < count; i++)
            {
                var songs = sources[i].Songs;
                while (cursors[i] < songs.Count)
                {
                    var song = songs[cursors[i]++];
                    if (!Usable(song) || ids.Contains(song.VideoId)) continue;
                    var artist = ArtistKey(song);
                    if (artist is not null && perArtist.GetValueOrDefault(artist) >= MaxPerArtist) continue;
                    ids.Add(song.VideoId);
                    if (artist is not null) perArtist[artist] = perArtist.GetValueOrDefault(artist) + 1;
                    chosen.Add(new SmartTrack(song, sources[i].Category));
                    progressed = true;
                    break;
                }
            }
        }
        return chosen;
    }
}
