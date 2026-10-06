using System.Text.RegularExpressions;

namespace Wave.Core.Lyrics;

/// <summary>歌詞の1行。StartMs は曲の頭からの時刻。</summary>
public sealed record LyricLine(long StartMs, string Text);

/// <summary>曲の歌詞。Source は出典の表示（「LRCLIB」「Source: Musixmatch」など）。</summary>
public abstract record SongLyrics(string? Source)
{
    /// <summary>行ごとに時刻がある（再生位置に合わせて表示する）。</summary>
    public sealed record Synced(IReadOnlyList<LyricLine> Lines, string? Source) : SongLyrics(Source);

    /// <summary>時刻の無い本文。</summary>
    public sealed record Plain(string Text, string? Source) : SongLyrics(Source);
}

/// <summary>LRC 形式（[mm:ss.xx]本文）の読み取り。</summary>
public static partial class Lrc
{
    /// <summary>時刻の付いた行だけを時刻の順に返す。[offset:+500] は全体を 500ms 早める。</summary>
    public static List<LyricLine> Parse(string text)
    {
        long offset = 0;
        var lines = new List<LyricLine>();
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            var offsetMatch = OffsetPattern().Match(line);
            if (offsetMatch.Success)
            {
                offset = long.Parse(offsetMatch.Groups[1].Value);
                continue;
            }
            var times = new List<long>();
            var rest = line;
            while (true)
            {
                var match = TimePattern().Match(rest);
                if (!match.Success || match.Index != 0) break;
                times.Add(MillisOf(match));
                rest = rest[match.Length..];
            }
            var body = rest.Trim();
            foreach (var t in times) lines.Add(new LyricLine(Math.Max(0, t - offset), body));
        }
        return lines.OrderBy(l => l.StartMs).ToList();
    }

    private static long MillisOf(Match match)
    {
        var fraction = match.Groups[3].Value;
        // 小数部は桁数で意味が変わる（.9 = 900ms、.90 = 900ms、.905 = 905ms）
        var fractionMs = fraction.Length == 0 ? 0 : long.Parse(fraction.PadRight(3, '0'));
        return long.Parse(match.Groups[1].Value) * 60_000 + long.Parse(match.Groups[2].Value) * 1_000 + fractionMs;
    }

    [GeneratedRegex(@"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]")]
    private static partial Regex TimePattern();

    [GeneratedRegex(@"^\[offset:\s*([+-]?\d+)\s*]$", RegexOptions.IgnoreCase)]
    private static partial Regex OffsetPattern();
}

/// <summary>再生位置と歌詞の行の対応。</summary>
public static class LyricsTiming
{
    /// <summary>行の開始より少し早く切り替える（読み始めが歌に遅れないように）。</summary>
    public const long LeadMs = 150;

    /// <summary>positionMs で歌っている行の位置。最初の行より前なら -1。</summary>
    public static int CurrentIndex(IReadOnlyList<LyricLine> lines, long positionMs)
    {
        var at = positionMs + LeadMs;
        int low = 0, high = lines.Count - 1, found = -1;
        while (low <= high)
        {
            var mid = (low + high) >>> 1;
            if (lines[mid].StartMs <= at)
            {
                found = mid;
                low = mid + 1;
            }
            else high = mid - 1;
        }
        return found;
    }
}
