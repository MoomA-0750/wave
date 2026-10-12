using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wave.Core.Music;

/// <summary>ポッドキャスト（Android の MusicParsers のポッドキャストの部分と同じ規則）。</summary>
public static partial class MusicParsers
{
    private const string PodcastPageType = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE";

    /// <summary>ポッドキャスト（番組）に絞った検索の最初のページと続きのページ。</summary>
    public static SearchPage<PodcastSummary> SearchPodcasts(JsonNode root) => SearchPageOf(root, PodcastRow);

    /// <summary>エピソードに絞った検索。行の2列目は「日付 • 番組」。</summary>
    public static SearchPage<PodcastEpisode> SearchEpisodes(JsonNode root) => SearchPageOf(root, EpisodeRow);

    /// <summary>ライブラリのポッドキャスト（FEmusic_library_non_music_audio_list）。格子（カード）と棚（行）のどちらの形でも読む。</summary>
    public static List<PodcastSummary> LibraryPodcasts(JsonNode root) =>
        root.FindAll("musicTwoRowItemRenderer").Select(PodcastCard)
            .Concat(root.FindAll("musicResponsiveListItemRenderer").Select(PodcastRow))
            .OfType<PodcastSummary>().DistinctBy(p => p.BrowseId).ToList();

    /// <summary>番組のページ（browse MPSP…）の最初のページ。取れなければ null。</summary>
    public static PodcastPage? PodcastPage(JsonNode root, string browseId)
    {
        var header = root.FindAll("musicResponsiveHeaderRenderer").FirstOrDefault();
        var title = TextOf(header.Path("title"));
        if (header is null || title is null) return null;
        var shelf = root.Path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").Items()
            .Select(i => i.Path("musicShelfRenderer").Obj()).FirstOrDefault(s => s is not null);
        var show = new ArtistRef(title, null);
        // 保存のボタン。ログインしていなければ押すとログインの案内（likeEndpoint を持たない）なので、状態は分からないとする
        var toggle = header.Path("buttons").Items().Select(b => b.Path("toggleButtonRenderer").Obj()).FirstOrDefault(t => t is not null);
        // 「新しいエピソード」（VLRDPN）も同じ形で返るが、保存するものではない
        bool? saved = toggle is not null && browseId.StartsWith(PodcastSummary.PodcastPrefix, StringComparison.Ordinal) && toggle.FindAll("likeEndpoint").Count > 0 && toggle.Path("isToggled") is JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;
        var episodes = shelf.Path("contents").Items().Select(i => i.Path("musicMultiRowListItemRenderer").Obj()).OfType<JsonObject>()
            .Select(row => MultiRowEpisode(row, show, browseId)).OfType<PodcastEpisode>().ToList();
        return new PodcastPage(browseId, title, TextOf(header.Path("straplineTextOne")),
            TextOf(header.Path("description", "musicDescriptionShelfRenderer", "description")),
            LastThumbnailUrl(header.Path("thumbnail")), episodes, shelf is null ? null : NextContinuation(shelf), saved);
    }

    /// <summary>番組のページのエピソードの続き（continuationContents.musicShelfContinuation）。番組名は行に無いので、呼ぶ側の show を入れる。</summary>
    public static SearchPage<PodcastEpisode> PodcastEpisodes(JsonNode root, ArtistRef show, string? showBrowseId)
    {
        var shelf = root.Path("continuationContents", "musicShelfContinuation").Obj();
        if (shelf is null) return new SearchPage<PodcastEpisode>([], null);
        var episodes = shelf.Path("contents").Items().Select(i => i.Path("musicMultiRowListItemRenderer").Obj()).OfType<JsonObject>()
            .Select(row => MultiRowEpisode(row, show, showBrowseId)).OfType<PodcastEpisode>().ToList();
        return new SearchPage<PodcastEpisode>(episodes, NextContinuation(shelf));
    }

    /// <summary>番組のページのエピソードの行。題・説明・「再生回数 • 日付」・長さ・聴いたところ。</summary>
    private static PodcastEpisode? MultiRowEpisode(JsonObject row, ArtistRef show, string? showBrowseId)
    {
        var watch = row.Path("onTap", "watchEndpoint").Obj() ?? row.Path("overlay").FindAll("watchEndpoint").FirstOrDefault();
        var videoId = watch.Path("videoId").Str();
        var title = TextOf(row.Path("title"));
        if (videoId is null || title is null) return null;
        var progress = row.Path("playbackProgress", "musicPlaybackProgressRenderer");
        // 「新しいエピソード」のように番組がまざる一覧では、行の補足に番組が付く（あればそれを使う）
        var showRun = row.Path("subtitle", "runs").Items().Concat(row.Path("secondTitle", "runs").Items()).FirstOrDefault(r => PageTypeOf(r) == PodcastPageType);
        if (showRun?.Path("text").Str() is { } rowShow)
        {
            show = new ArtistRef(rowShow, null);
            showBrowseId = showRun.Path("navigationEndpoint", "browseEndpoint", "browseId").Str() ?? showBrowseId;
        }
        // 「2294回視聴 • 6 日前」の最後が日付（再生回数が無ければ日付だけ）
        var published = LastText(row.Path("subtitle", "runs").Items().Where(r => !ReferenceEquals(r, showRun)));
        var duration = LastText(progress.Path("durationText", "runs").Items());
        int? percent = progress.Path("playbackProgressPercentage") is JsonValue p && p.GetValueKind() == JsonValueKind.Number && p.TryGetValue<double>(out var d) ? (int)d : null;
        var song = new Song(videoId, title, [show], null, null, LastThumbnailUrl(row.Path("thumbnail")),
            watch.Path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str());
        return new PodcastEpisode(song, published, duration, TextOf(row.Path("description")), percent is >= 1 and <= 100 ? percent : null, showBrowseId);
    }

    private static string? LastText(IEnumerable<JsonObject> runs) =>
        runs.Select(r => r.Path("text").Str()).LastOrDefault(t => !string.IsNullOrWhiteSpace(t) && t != Separator);

    /// <summary>検索・ライブラリの番組の行（ページ種別が番組のもの）。</summary>
    private static PodcastSummary? PodcastRow(JsonObject item)
    {
        var browse = item.Path("navigationEndpoint", "browseEndpoint");
        if (PageTypeOfBrowse(browse) != PodcastPageType) return null;
        var columns = FlexColumnRuns(item);
        var id = browse.Path("browseId").Str();
        var title = RunsText(columns.ElementAtOrDefault(0) ?? []);
        if (id is null || title is null) return null;
        return new PodcastSummary(id, title, RunsText(columns.Skip(1).SelectMany(c => c).ToList()), LastThumbnailUrl(item.Path("thumbnail")));
    }

    /// <summary>ライブラリ・ホームの番組のカード（musicTwoRowItemRenderer）。</summary>
    private static PodcastSummary? PodcastCard(JsonObject item)
    {
        var browse = item.Path("navigationEndpoint", "browseEndpoint");
        var id = browse.Path("browseId").Str();
        if (id is null || (PageTypeOfBrowse(browse) != PodcastPageType && !id.StartsWith(PodcastSummary.PodcastPrefix, StringComparison.Ordinal))) return null;
        var title = TextOf(item.Path("title"));
        return title is null ? null : new PodcastSummary(id, title, TextOf(item.Path("subtitle")), LastThumbnailUrl(item.Path("thumbnailRenderer")));
    }

    /// <summary>エピソードの検索の行。2列目の「日付 • 番組」から日付と番組を取る（番組名は曲のアーティストとして入る）。</summary>
    private static PodcastEpisode? EpisodeRow(JsonObject item)
    {
        if (ResponsiveListItemSong(item) is not { } song) return null;
        var detail = FlexColumnRuns(item).Skip(1).SelectMany(c => c).ToList();
        var showRun = detail.FirstOrDefault(r => PageTypeOf(r) == PodcastPageType);
        var published = detail.Where(r => !ReferenceEquals(r, showRun)).Select(r => r.Path("text").Str())
            .FirstOrDefault(t => !string.IsNullOrWhiteSpace(t) && t != Separator);
        return new PodcastEpisode(song, published, ShowBrowseId: showRun?.Path("navigationEndpoint", "browseEndpoint", "browseId").Str());
    }
}
