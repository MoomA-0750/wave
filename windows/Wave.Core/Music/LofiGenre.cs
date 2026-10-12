using System.Text.RegularExpressions;

namespace Wave.Core.Music;

/// <summary>
/// 気分とジャンルに、アプリで足す Lo-Fi のタイル（日本の YouTube Music には無い。Android の LofiGenre と同じ値・同じ並び）。
/// 押すと YouTube のジャンルのページの代わりに、ライブ配信と lofi のプレイリストを並べたページを組む。
/// </summary>
public static class LofiGenre
{
    /// <summary>タイルの params の代わりの印（YouTube の params とぶつからない形）。</summary>
    public const string Params = "wave:lofi";
    public const string Title = "Lo-Fi";

    /// <summary>タイルの色（ARGB）。落ち着いた紫。</summary>
    public const long Color = 0xFF8E7CC3;

    public const string LiveQuery = "lofi hip hop radio";
    public const string PlaylistQuery = "lofi";
    public const string LiveTitle = "ライブ配信";
    public const string FeaturedTitle = "YouTube Music のプレイリスト";
    public const string CommunityTitle = "みんなのプレイリスト";
    public const int MaxItems = 12;

    public static MoodCategory Category => new(Title, Params, Color);

    private static readonly Regex GenreTitle = new("ジャンル|genre", RegexOptions.IgnoreCase);
    private static readonly Regex LofiTitle = new("lo-?fi|ローファイ|jazzhop|chill|beats", RegexOptions.IgnoreCase);

    /// <summary>
    /// ジャンルの格子の先頭に足す。ジャンルの格子は見出しで探し（「ジャンル」「Genres」）、見つからなければ最後の格子にする。
    /// 格子が無ければそのまま返す。二度は足さない。
    /// </summary>
    public static List<MoodSection> AddTo(IReadOnlyList<MoodSection> sections)
    {
        if (sections.Count == 0 || sections.Any(s => s.Categories.Any(c => c.Params == Params))) return sections.ToList();
        var index = sections.ToList().FindIndex(s => GenreTitle.IsMatch(s.Title));
        if (index < 0) index = sections.Count - 1;
        return sections.Select((s, i) => i == index ? s with { Categories = [Category, .. s.Categories] } : s).ToList();
    }

    /// <summary>YouTube Music のおすすめのプレイリストのうち、lofi らしいもの。1つも無ければ上から順に。</summary>
    public static List<PlaylistSummary> Featured(IReadOnlyList<PlaylistSummary> playlists)
    {
        var matched = playlists.Where(p => LofiTitle.IsMatch(p.Title)).Take(MaxItems).ToList();
        return matched.Count > 0 ? matched : playlists.Take(MaxItems).ToList();
    }

    internal static HomeShelf? Shelf(string title, IReadOnlyList<HomeItem> items) => items.Count > 0 ? new HomeShelf(title, null, items) : null;
}
