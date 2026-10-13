using System.Globalization;
using System.Text.RegularExpressions;

namespace Wave.Core.Music;

/// <summary>
/// ライブラリの「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012。Android の NewEpisodes と同じ規則）。
/// YouTube の回は「6 日前」「2025/09/08」のような表示しか持たないので、だいたいの日時に直して新しい順に並べる。
/// </summary>
public static partial class NewEpisodes
{
    /// <summary>RSS の番組ごとに混ぜる回の数。</summary>
    public const int PerFeed = 10;

    /// <summary>これより古い RSS の回は混ぜない（YouTube の新着も数か月分まで）。</summary>
    public const int MaxAgeDays = 90;

    private const long Day = 86_400L;

    public static List<PodcastEpisode> Merge(IReadOnlyList<PodcastEpisode> youtube, IReadOnlyList<FeedShow> feeds, long nowEpochSeconds, Func<string, EpisodePosition?>? positions = null)
    {
        // 読めない表示の YouTube の回は、一つ前の回と同じ日時にする（並びを崩さない）
        var previous = nowEpochSeconds;
        var dated = new List<(long At, PodcastEpisode Episode)>();
        foreach (var episode in youtube)
        {
            var at = (episode.PublishedText is { } text ? ApproximateEpochSeconds(text, nowEpochSeconds) : null) ?? previous;
            previous = at;
            dated.Add((at, episode));
        }
        var rss = feeds.SelectMany(show => show.Episodes(0, PerFeed)
            .Where(e => e.PublishedEpochSeconds is { } at && nowEpochSeconds - at <= MaxAgeDays * Day)
            .Select(e => (At: e.PublishedEpochSeconds!.Value, Episode: e.ToEpisode(show, positions?.Invoke(e.AudioUrl)))));
        // 同じ時刻なら YouTube を先に（安定な並べ替え）
        return dated.Concat(rss).OrderByDescending(x => x.At).Select(x => x.Episode).ToList();
    }

    [GeneratedRegex("([0-9]+)[ \\t\\n\\x0B\\f\\r]*(分|時間|日|週間|か月|ヶ月|年)前")]
    private static partial Regex Relative();

    [GeneratedRegex("([0-9]{4})[/年.-]([0-9]{1,2})[/月.-]([0-9]{1,2})")]
    private static partial Regex Absolute();

    private static readonly string[] MonthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

    /// <summary>「6 日前」「2 時間前」「2025/09/08」「2025年9月8日」をだいたいの日時（UTC のエポック秒）にする。読めなければ null。</summary>
    public static long? ApproximateEpochSeconds(string text, long nowEpochSeconds)
    {
        var relative = Relative().Match(text);
        if (relative.Success)
        {
            if (!long.TryParse(relative.Groups[1].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var n)) return null;
            var unit = relative.Groups[2].Value switch
            {
                "分" => 60L,
                "時間" => 3_600L,
                "日" => Day,
                "週間" => 7 * Day,
                "か月" or "ヶ月" => 30 * Day,
                _ => 365 * Day,
            };
            return nowEpochSeconds - n * unit;
        }
        var absolute = Absolute().Match(text);
        if (absolute.Success)
        {
            var month = int.Parse(absolute.Groups[2].Value, CultureInfo.InvariantCulture);
            if (month is < 1 or > 12) return null;
            return FeedParser.ParseRfc822($"{absolute.Groups[3].Value} {MonthNames[month - 1]} {absolute.Groups[1].Value} 00:00:00 GMT");
        }
        return null;
    }
}
