using System.Text.Json;
using System.Text.Json.Serialization;
using Wave.Core.Music;

namespace Wave.Core.Downloads;

/// <summary>丸ごと取るものの種類（ADR-0016）。</summary>
public static class DownloadKind
{
    public const string Playlist = "playlist";
    public const string Album = "album";
    /// <summary>高く評価した曲（LM）。</summary>
    public const string Liked = "liked";
}

/// <summary>丸ごと取っているプレイリスト・アルバム・高く評価した曲。Songs は最後に読んだ曲の並び（通信なしでもページを出す）。</summary>
public sealed record DownloadCollection(string Kind, string Id, string Title, string? ThumbnailUrl, long AddedAtMs, IReadOnlyList<Song> Songs);

/// <summary>1曲ずつ取った曲・エピソード。</summary>
public sealed record DownloadSingle(Song Song, long AddedAtMs, bool Episode = false);

/// <summary>スマートダウンロードの分類（ADR-0016 の 10）。</summary>
public enum SmartCategory
{
    /// <summary>いつも聴いている。</summary>
    Frequent,
    /// <summary>好きそう（聴いたことのない曲）。</summary>
    Likely,
    /// <summary>おすすめ。</summary>
    Recommended,
}

public sealed record SmartTrack(Song Song, SmartCategory Category);

/// <summary>プレイリストを読み直したときに、増えた曲と外れた曲。</summary>
public sealed record CollectionDiff(IReadOnlyList<string> Added, IReadOnlyList<string> Removed)
{
    public static CollectionDiff Of(IEnumerable<Song> before, IEnumerable<Song> after)
    {
        var old = before.Where(DownloadManifest.Downloadable).Select(s => s.VideoId).Distinct().ToList();
        var now = after.Where(DownloadManifest.Downloadable).Select(s => s.VideoId).Distinct().ToList();
        var oldSet = old.ToHashSet();
        var nowSet = now.ToHashSet();
        return new CollectionDiff(now.Where(id => !oldSet.Contains(id)).ToList(), old.Where(id => !nowSet.Contains(id)).ToList());
    }
}

/// <summary>
/// 何を取っているかの目録（downloads.json。ADR-0016 の 4）。丸ごとのもの・1曲ずつのもの・スマートダウンロードの曲を持ち、
/// 曲のファイルは、どれか1つでも持ち主がいれば残す（<see cref="WantedIds"/> に入る）。変える操作は新しい目録を返す。
/// </summary>
public sealed record DownloadManifest(
    IReadOnlyList<DownloadCollection> Collections,
    IReadOnlyList<DownloadSingle> Singles,
    IReadOnlyList<SmartTrack> Smart,
    long SmartRefreshedAtMs = 0)
{
    public static DownloadManifest Empty { get; } = new([], [], []);

    /// <summary>取ってよい曲か（ライブ配信は取らない。ADR-0016 の 3）。</summary>
    public static bool Downloadable(Song song) => !song.IsLive;

    /// <summary>持ち主のいる曲の ID（丸ごとのもの・1曲ずつ・スマートダウンロードの順。取る順にも使う）。</summary>
    public IReadOnlyList<string> WantedIds() => WantedSongs().Select(s => s.VideoId).ToList();

    /// <summary>持ち主のいる曲（同じ曲は最初の1つ）。</summary>
    public List<Song> WantedSongs()
    {
        var seen = new HashSet<string>();
        return Collections.SelectMany(c => c.Songs).Concat(Singles.Select(s => s.Song)).Concat(Smart.Select(s => s.Song))
            .Where(s => Downloadable(s) && seen.Add(s.VideoId)).ToList();
    }

    public bool IsWanted(string videoId) => Collections.Any(c => c.Songs.Any(s => s.VideoId == videoId && Downloadable(s))) || IsSingle(videoId) || IsSmart(videoId);

    public bool IsSingle(string videoId) => Singles.Any(s => s.Song.VideoId == videoId);

    public bool IsSmart(string videoId) => Smart.Any(s => s.Song.VideoId == videoId);

    public DownloadCollection? Collection(string id) => Collections.FirstOrDefault(c => c.Id == id);

    /// <summary>丸ごと取るものを足す（同じ ID があれば置き換える。足した時刻は前のまま）。</summary>
    public DownloadManifest WithCollection(DownloadCollection collection)
    {
        var list = Collections.ToList();
        var at = list.FindIndex(c => c.Id == collection.Id);
        if (at >= 0) list[at] = collection with { AddedAtMs = list[at].AddedAtMs };
        else list.Add(collection);
        return this with { Collections = list };
    }

    public DownloadManifest WithoutCollection(string id) => this with { Collections = Collections.Where(c => c.Id != id).ToList() };

    /// <summary>読み直した曲の並びに替える（覚えていないものなら何もしない）。増えた曲・外れた曲も返す。</summary>
    public (DownloadManifest Manifest, CollectionDiff Diff) WithCollectionSongs(string id, IReadOnlyList<Song> songs)
    {
        if (Collection(id) is not { } old) return (this, new CollectionDiff([], []));
        return (WithCollection(old with { Songs = songs.ToList() }), CollectionDiff.Of(old.Songs, songs));
    }

    public DownloadManifest WithSingle(Song song, long nowMs, bool episode = false) =>
        IsSingle(song.VideoId) || !Downloadable(song) ? this : this with { Singles = [.. Singles, new DownloadSingle(song, nowMs, episode)] };

    /// <summary>1曲ずつ取ったもの・スマートダウンロードから外す（丸ごと取っているものに入っていれば、ファイルは残る）。</summary>
    public DownloadManifest WithoutSingle(string videoId) =>
        this with { Singles = Singles.Where(s => s.Song.VideoId != videoId).ToList(), Smart = Smart.Where(s => s.Song.VideoId != videoId).ToList() };

    public DownloadManifest WithSmart(IReadOnlyList<SmartTrack> tracks, long nowMs) => this with { Smart = tracks.ToList(), SmartRefreshedAtMs = nowMs };

    /// <summary>曲の情報（丸ごと・1曲ずつ・スマートのどれかから）。</summary>
    public Song? SongOf(string videoId) => WantedSongs().FirstOrDefault(s => s.VideoId == videoId);

    private static readonly JsonSerializerOptions Options = new() { Converters = { new JsonStringEnumConverter() } };

    public string Encode() => JsonSerializer.Serialize(this, Options);

    public static DownloadManifest? Decode(string json)
    {
        try
        {
            var decoded = JsonSerializer.Deserialize<DownloadManifest>(json, Options);
            if (decoded is null) return null;
            // 前の版・壊れたファイルで欠けた所は空にする
            return new DownloadManifest(decoded.Collections ?? [], decoded.Singles ?? [], decoded.Smart ?? [], decoded.SmartRefreshedAtMs);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
