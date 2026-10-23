using System.Text.Json;
using Wave.Core.Music;

namespace Wave.Core.Downloads;

/// <summary>この端末で曲を聴いた回数と、最後に聴いた時刻（ADR-0016 の 10「いつも聴いている」に使う）。</summary>
public sealed record PlayCount(Song Song, int Count, long LastPlayedMs);

/// <summary>この端末での曲ごとの再生回数（2000 曲まで。超えたら最後に聴いたのが古い曲から忘れる）。変える操作は新しい値を返す。</summary>
public sealed record PlayCounts(IReadOnlyList<PlayCount> Entries)
{
    public const int MaxSongs = 2000;

    public static PlayCounts Empty { get; } = new([]);

    /// <summary>1回聴いた（再生履歴を送るのと同じく、30秒か長さの半分を聴いたとき）。ライブ配信・RSS のエピソードは数えない。</summary>
    public PlayCounts Record(Song song, long nowMs)
    {
        if (song.IsLive || Feed.IsExternal(song.VideoId)) return this;
        var list = Entries.ToList();
        var at = list.FindIndex(e => e.Song.VideoId == song.VideoId);
        if (at >= 0) list[at] = new PlayCount(song, list[at].Count + 1, nowMs);
        else list.Add(new PlayCount(song, 1, nowMs));
        if (list.Count > MaxSongs) list = list.OrderByDescending(e => e.LastPlayedMs).Take(MaxSongs).ToList();
        return new PlayCounts(list);
    }

    public string Encode() => JsonSerializer.Serialize(this);

    public static PlayCounts Decode(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<PlayCounts>(json) is { Entries: not null } counts ? counts : Empty;
        }
        catch (JsonException)
        {
            return Empty;
        }
    }
}
