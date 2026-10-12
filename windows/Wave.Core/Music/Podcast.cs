namespace Wave.Core.Music;

/// <summary>ポッドキャストの番組のカード（検索・ライブラリ。Android の PodcastSummary と同じ）。BrowseId は MPSP…。</summary>
public sealed record PodcastSummary(string BrowseId, string Title, string? Author, string? ThumbnailUrl)
{
    public const string PodcastPrefix = "MPSP";

    /// <summary>番組のエピソードの一覧のプレイリスト（PL…）。保存と、エピソードを続けて流すのに使う。</summary>
    public string PlaylistId => BrowseId.StartsWith(PodcastPrefix, StringComparison.Ordinal) ? BrowseId[PodcastPrefix.Length..] : BrowseId;
}

/// <summary>
/// ポッドキャストのエピソード。再生は曲と同じ（Song の videoId で流す。番組名を Song.Artists に入れてある）。
/// PublishedText・DurationText は応答の表示のまま。ProgressPercent は聴いたところ（1〜100、無ければ null）。
/// </summary>
public sealed record PodcastEpisode(
    Song Song,
    string? PublishedText,
    string? DurationText = null,
    string? Description = null,
    int? ProgressPercent = null,
    string? ShowBrowseId = null)
{
    /// <summary>検索の行の「番組 • 日付」。</summary>
    public string Subtitle => string.Join(" • ", new[] { Song.ArtistsText, PublishedText }.Where(s => !string.IsNullOrEmpty(s)));

    /// <summary>番組のページの行の「日付 • 長さ」。</summary>
    public string DateAndLength => string.Join(" • ", new[] { PublishedText, DurationText }.Where(s => !string.IsNullOrEmpty(s)));
}

/// <summary>ポッドキャストの番組のページ（MPSP…）。Saved はライブラリに保存しているか（ログインしていない・分からなければ null）。</summary>
public sealed record PodcastPage(
    string BrowseId,
    string Title,
    string? Author,
    string? Description,
    string? ThumbnailUrl,
    IReadOnlyList<PodcastEpisode> Episodes,
    string? Continuation,
    bool? Saved = null)
{
    public string PlaylistId => Summary.PlaylistId;

    public PodcastSummary Summary => new(BrowseId, Title, Author, ThumbnailUrl);
}
