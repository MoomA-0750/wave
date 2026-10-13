using System.Text;

namespace Wave.Core.Music;

/// <summary>
/// RSS で配られるポッドキャスト（ADR-0012。Android の Feed と同じ）。番組の目印は rss: + フィードの URL、
/// エピソードの曲の ID は ext: + 音声の URL。
/// </summary>
public static class Feed
{
    public const string ShowPrefix = "rss:";
    public const string AudioPrefix = "ext:";

    public static string ShowBrowseId(string feedUrl) => ShowPrefix + feedUrl;

    /// <summary>rss: の番組ならフィードの URL。</summary>
    public static string? FeedUrlOf(string browseId) => IsFeedShow(browseId) ? browseId[ShowPrefix.Length..] : null;

    public static bool IsFeedShow(string? browseId) => browseId?.StartsWith(ShowPrefix, StringComparison.Ordinal) == true;

    public static string AudioVideoId(string audioUrl) => AudioPrefix + audioUrl;

    /// <summary>ext: の曲なら音声の URL（https のものだけ）。</summary>
    public static string? AudioUrlOf(string? videoId) =>
        IsExternal(videoId) && videoId![AudioPrefix.Length..] is var url && IsAllowedFeedUrl(url) ? url : null;

    /// <summary>YouTube の曲ではない（ストリームの解決・履歴・評価・歌詞・ラジオをしない）。</summary>
    public static bool IsExternal(string? videoId) => videoId?.StartsWith(AudioPrefix, StringComparison.Ordinal) == true;

    /// <summary>取りに行ってよい URL（https だけ。ADR-0012）。</summary>
    public static bool IsAllowedFeedUrl(string url) =>
        url.StartsWith("https://", StringComparison.OrdinalIgnoreCase) && url.Length is >= 12 and <= 4096 && !url.Any(char.IsWhiteSpace);

    /// <summary>打ち込まれた URL を整える（feed://・http://・スキーム無しを https にする）。使えなければ null。</summary>
    public static string? NormalizeFeedUrl(string input)
    {
        var trimmed = input.Trim();
        var lower = trimmed.ToLowerInvariant();
        string url;
        if (lower.StartsWith("https://", StringComparison.Ordinal)) url = trimmed;
        else if (lower.StartsWith("http://", StringComparison.Ordinal)) url = "https://" + trimmed[7..];
        else if (lower.StartsWith("feed://", StringComparison.Ordinal)) url = "https://" + trimmed[7..];
        else if (lower.StartsWith("feed:", StringComparison.Ordinal))
        {
            if (NormalizeFeedUrl(trimmed[5..]) is not { } inner) return null;
            url = inner;
        }
        else if (trimmed.Contains("://", StringComparison.Ordinal)) return null;
        else if (trimmed.Contains('.')) url = "https://" + trimmed;
        else return null;
        return IsAllowedFeedUrl(url) ? url : null;
    }
}

/// <summary>RSS で登録した番組（端末ごとに覚える）。</summary>
public sealed record FeedSubscription(string FeedUrl, string Title, string? Author = null, string? ThumbnailUrl = null, long AddedAtMs = 0)
{
    public PodcastSummary Summary => new(Feed.ShowBrowseId(FeedUrl), Title, Author, ThumbnailUrl);
}

/// <summary>エピソードの聴いたところ（音声の URL ごと）。Finished は最後まで（残り30秒未満まで）聴いた。</summary>
public sealed record EpisodePosition(int PositionSeconds, int? DurationSeconds = null, bool Finished = false, long UpdatedAtMs = 0)
{
    public const int FinishedMarginSeconds = 30;

    /// <summary>0〜100。分からなければ null。</summary>
    public int? Percent =>
        Finished ? 100
        : DurationSeconds is not { } d || d <= 0 ? null
        : Math.Clamp((int)((long)PositionSeconds * 100 / d), 0, 100) is var p && p > 0 ? p : null;

    /// <summary>続きから流す位置（聴き終えていれば頭から）。</summary>
    public int ResumeSeconds => Finished ? 0 : PositionSeconds;

    public static EpisodePosition Of(int positionSeconds, int? durationSeconds, long nowMs)
    {
        var finished = durationSeconds is > 0 && durationSeconds.Value - positionSeconds < FinishedMarginSeconds;
        return new EpisodePosition(Math.Max(0, positionSeconds), durationSeconds, finished, nowMs);
    }
}

/// <summary>
/// 読んだ RSS の番組。エピソードは新しい順。エピソードは要るところだけ読む（数百回分の番組を、開くたびに全部ほどかない）。
/// </summary>
public sealed class FeedShow
{
    public const int PageSize = 30;
    public const string ContinuationPrefix = "feed:";

    private readonly IReadOnlyList<XmlRange> items;
    private readonly Dictionary<int, FeedEpisode?> parsed = [];

    public FeedShow(string feedUrl, string title, string? author, string? description, string? thumbnailUrl, IReadOnlyList<XmlRange> items)
    {
        FeedUrl = feedUrl;
        Title = title;
        Author = author;
        Description = description;
        ThumbnailUrl = thumbnailUrl;
        this.items = items;
    }

    public string FeedUrl { get; }
    public string Title { get; }
    public string? Author { get; }
    public string? Description { get; }
    public string? ThumbnailUrl { get; }

    /// <summary>エピソードの項目の数（音声の無いものも数える）。</summary>
    public int ItemCount => items.Count;

    public FeedSubscription Subscription => new(FeedUrl, Title, Author, ThumbnailUrl);

    /// <summary>from 番目から count 件の項目のうち、エピソードとして読めたもの。</summary>
    public List<FeedEpisode> Episodes(int from, int count)
    {
        var output = new List<FeedEpisode>();
        lock (parsed)
        {
            for (var index = from; index < Math.Min(items.Count, from + count); index++)
            {
                if (!parsed.TryGetValue(index, out var episode)) parsed[index] = episode = FeedParser.Episode(items[index]);
                if (episode is not null) output.Add(episode);
            }
        }
        return output;
    }

    /// <summary>全部のエピソード（確かめ用）。</summary>
    public List<FeedEpisode> AllEpisodes => Episodes(0, items.Count);

    /// <summary>番組のページの形にする。エピソードは count 件の項目まで（saved は登録しているか）。</summary>
    public PodcastPage ToPage(bool? saved, int count = PageSize, Func<string, EpisodePosition?>? positions = null) =>
        new(
            Feed.ShowBrowseId(FeedUrl),
            Title,
            Author,
            Description,
            ThumbnailUrl,
            Episodes(0, count).Select(e => e.ToEpisode(this, positions?.Invoke(e.AudioUrl))).ToList(),
            ContinuationAfter(count),
            saved);

    /// <summary>続き（token は ToPage・前の続きが返したもの）。</summary>
    public SearchPage<PodcastEpisode> More(string token, Func<string, EpisodePosition?>? positions = null)
    {
        if (!token.StartsWith(ContinuationPrefix, StringComparison.Ordinal) || !int.TryParse(token.AsSpan(ContinuationPrefix.Length), out var from) || from < 0 || from > items.Count)
            return new SearchPage<PodcastEpisode>([], null);
        return new SearchPage<PodcastEpisode>(Episodes(from, PageSize).Select(e => e.ToEpisode(this, positions?.Invoke(e.AudioUrl))).ToList(), ContinuationAfter(from + PageSize));
    }

    /// <summary>続きのトークンから、そこまでに読んだ項目の数（続きを読んだ後に作り直すため）。</summary>
    public static int? LoadedAfter(string token) =>
        token.StartsWith(ContinuationPrefix, StringComparison.Ordinal) && int.TryParse(token.AsSpan(ContinuationPrefix.Length), out var from) ? from + PageSize : null;

    private string? ContinuationAfter(int count) => count < items.Count ? ContinuationPrefix + count : null;
}

public sealed record FeedEpisode(string Title, string AudioUrl, long? PublishedEpochSeconds, int? DurationSeconds, string? Description, string? ThumbnailUrl)
{
    public PodcastEpisode ToEpisode(FeedShow show, EpisodePosition? position) =>
        new(
            new Song(Feed.AudioVideoId(AudioUrl), Title, [new ArtistRef(show.Title, null)], null, DurationSeconds, ThumbnailUrl ?? show.ThumbnailUrl),
            PublishedEpochSeconds is { } published ? FeedParser.DateText(published) : null,
            DurationSeconds is { } duration ? FeedParser.DurationText(duration) : null,
            Description,
            position?.Percent,
            Feed.ShowBrowseId(show.FeedUrl));
}

public static class FeedParser
{
    /// <summary>RSS 2.0（iTunes の拡張つき）を読む。番組として読めなければ null。音声の無い項目は落とす。</summary>
    public static FeedShow? Parse(string feedUrl, string xml)
    {
        if (FeedXml.Root(xml, "channel") is not { } channel) return null;
        var items = channel.Children("item");
        // 番組の欄は最初のエピソードより前だけを見る（エピソードの itunes:author などを拾わない）
        var head = new XmlRange(xml, channel.Start, items.Count > 0 ? Math.Max(channel.Start, items[0].Start - 1) : channel.End).Detached();
        if (head.ChildText("title", skip: "image") is not { } title) return null;
        var image = head.Child("itunes:image")?.Attributes.GetValueOrDefault("href") is { } href && Feed.IsAllowedFeedUrl(href) ? href
            : head.Child("image")?.ChildText("url") is { } url && Feed.IsAllowedFeedUrl(url) ? url : null;
        // ほとんどのフィードは新しい順。古い順（連続ものの番組）なら逆にする（端の5件ずつで、最初に日付が読めたものを比べる）
        static long? DateOf(XmlRange range) => range.Detached().ChildText("pubDate") is { } text ? ParseRfc822(text) : null;
        var firstDate = items.Take(5).Select(DateOf).FirstOrDefault(d => d is not null);
        var lastDate = Enumerable.Reverse(items).Take(5).Select(DateOf).FirstOrDefault(d => d is not null);
        IReadOnlyList<XmlRange> ordered = firstDate is { } f && lastDate is { } l && f < l ? Enumerable.Reverse(items).ToList() : items;
        var description = head.ChildText("itunes:summary") ?? head.ChildText("description");
        var plain = description is null ? null : PlainText(description);
        return new FeedShow(
            feedUrl,
            title,
            head.ChildText("itunes:author") ?? head.ChildText("author") ?? head.ChildText("managingEditor"),
            string.IsNullOrEmpty(plain) ? null : plain,
            image,
            ordered);
    }

    /// <summary>エピソード1回分。音声・題が無ければ null。</summary>
    internal static FeedEpisode? Episode(XmlRange range)
    {
        var item = range.Detached();
        var enclosure = item.Child("enclosure")?.Attributes.GetValueOrDefault("url")?.Trim();
        if (enclosure is null) return null;
        var audio = UpgradeToHttps(enclosure);
        if (!Feed.IsAllowedFeedUrl(audio)) return null;
        var title = item.ChildText("title") ?? item.ChildText("itunes:title");
        if (title is null) return null;
        var description = item.ChildText("itunes:summary") ?? item.ChildText("description") ?? item.ChildText("content:encoded");
        var plain = description is null ? null : PlainText(description);
        var thumbnail = item.Child("itunes:image")?.Attributes.GetValueOrDefault("href");
        return new FeedEpisode(
            title,
            audio,
            item.ChildText("pubDate") is { } date ? ParseRfc822(date) : null,
            item.ChildText("itunes:duration") is { } duration ? ParseDuration(duration) : null,
            string.IsNullOrEmpty(plain) ? null : plain,
            thumbnail is not null && Feed.IsAllowedFeedUrl(thumbnail) ? thumbnail : null);
    }

    /// <summary>音声は https でだけ取る。http の URL は同じ場所の https を試す（多くの配信元は両方で返す）。</summary>
    private static string UpgradeToHttps(string url) => url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ? "https://" + url[7..] : url;

    /// <summary>一覧で使う説明の長さ（数百回分の番組で、長い HTML を全部ほどかない）。</summary>
    private const int DescriptionSourceLimit = 600;

    /// <summary>改行にするタグ（閉じの p・li・見出しと br）。</summary>
    private static readonly HashSet<string> BreakTags = ["br", "br/", "/p", "/li", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/div"];

    /// <summary>HTML の説明を、段落を残した素の文にする（最初の600字だけ）。</summary>
    public static string PlainText(string html)
    {
        var source = html.Length > DescriptionSourceLimit ? html[..DescriptionSourceLimit] : html;
        var stripped = new StringBuilder(source.Length);
        var from = 0;
        while (true)
        {
            var open = source.IndexOf('<', from);
            if (open < 0)
            {
                stripped.Append(source, from, source.Length - from);
                break;
            }
            stripped.Append(source, from, open - from);
            var close = source.IndexOf('>', open);
            if (close < 0) break;
            var name = source[(open + 1)..close].Trim();
            var space = name.IndexOf(' ');
            if (space >= 0) name = name[..space];
            if (BreakTags.Contains(name.ToLowerInvariant())) stripped.Append('\n');
            from = close + 1;
        }
        // 行ごとに前後の空白を落とし、空行は2つまで
        var output = new StringBuilder(stripped.Length);
        var blank = 0;
        foreach (var line in FeedXml.DecodeEntities(stripped.ToString()).Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length == 0)
            {
                blank++;
                continue;
            }
            if (output.Length > 0) output.Append(blank > 0 ? "\n\n" : "\n");
            output.Append(trimmed);
            blank = 0;
        }
        return output.ToString();
    }

    /// <summary>HH:MM:SS・MM:SS・秒。</summary>
    public static int? ParseDuration(string text)
    {
        var parts = text.Trim().Split(':');
        if (parts.Length is 0 or > 3) return null;
        long total = 0;
        foreach (var part in parts)
        {
            var whole = part.Trim();
            var dot = whole.IndexOf('.');
            if (dot >= 0) whole = whole[..dot];
            if (!int.TryParse(whole, System.Globalization.NumberStyles.AllowLeadingSign, System.Globalization.CultureInfo.InvariantCulture, out var v)) return null;
            total = total * 60 + v;
        }
        return total > 0 && total <= int.MaxValue ? (int)total : null;
    }

    /// <summary>「1:02:03」「42:05」。言語に依らない形にする。</summary>
    public static string DurationText(int seconds)
    {
        var h = seconds / 3600;
        var m = seconds % 3600 / 60;
        var s = seconds % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }

    /// <summary>「2026/10/13」（UTC の日付）。</summary>
    public static string DateText(long epochSeconds)
    {
        var date = DateTimeOffset.FromUnixTimeSeconds(Math.Clamp(epochSeconds, -62135596800L, 253402300799L)).UtcDateTime;
        return $"{date.Year}/{date.Month:00}/{date.Day:00}";
    }

    private static readonly string[] Months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

    /// <summary>RFC 822 の日時（Mon, 13 Oct 2026 10:00:00 +0900）。曜日・秒・時差は省けてもよい。UTC のエポック秒。</summary>
    public static long? ParseRfc822(string text)
    {
        var tokens = text.Replace(',', ' ').Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        var start = tokens.Length > 0 && char.IsLetter(tokens[0][0]) ? 1 : 0;
        if (tokens.Length <= start + 2) return null;
        if (!int.TryParse(tokens[start], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var day)) return null;
        var monthToken = tokens[start + 1].ToLowerInvariant();
        var month = Array.IndexOf(Months, monthToken.Length > 3 ? monthToken[..3] : monthToken) + 1;
        if (month <= 0) return null;
        if (!int.TryParse(tokens[start + 2], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var year)) return null;
        if (year < 100) year += 2000;
        var time = tokens.Length > start + 3 ? tokens[start + 3].Split(':').Select(t => int.TryParse(t, out var n) ? n : 0).ToArray() : [];
        var offsetMinutes = tokens.Length > start + 4 ? ZoneOffsetMinutes(tokens[start + 4]) ?? 0 : 0;
        var days = DaysFromCivil(year, month, day);
        var seconds = (time.ElementAtOrDefault(0)) * 3600L + time.ElementAtOrDefault(1) * 60L + time.ElementAtOrDefault(2);
        return days * 86_400L + seconds - offsetMinutes * 60L;
    }

    private static int? ZoneOffsetMinutes(string zone)
    {
        if (zone.Length == 5 && zone[0] is '+' or '-')
        {
            if (!int.TryParse(zone.AsSpan(1, 2), out var h) || !int.TryParse(zone.AsSpan(3, 2), out var m)) return null;
            return (h * 60 + m) * (zone[0] == '-' ? -1 : 1);
        }
        return zone.ToUpperInvariant() switch
        {
            "GMT" or "UT" or "UTC" or "Z" => 0,
            "EST" => -300,
            "EDT" => -240,
            "CST" => -360,
            "CDT" => -300,
            "MST" => -420,
            "MDT" => -360,
            "PST" => -480,
            "PDT" => -420,
            "JST" => 540,
            _ => null,
        };
    }

    // Howard Hinnant の days_from_civil（Android と同じ。月日のはみ出しも同じに数える）
    private static long DaysFromCivil(int year, int month, int day)
    {
        var y = month <= 2 ? year - 1 : year;
        var era = (y >= 0 ? y : y - 399) / 400;
        var yoe = y - era * 400;
        var doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        var doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097L + doe - 719_468L;
    }
}
