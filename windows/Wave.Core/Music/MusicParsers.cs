using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Wave.Core.Lyrics;

namespace Wave.Core.Music;

/// <summary>
/// InnerTube の応答（JSON）からモデルへの純粋な変換（Android 版の MusicParsers と同じ規則）。
/// 形が変わっても例外で落とさず、取れない項目は飛ばす。
/// </summary>
public static partial class MusicParsers
{
    private const string Separator = " • ";
    private const string ArtistPageType = "MUSIC_PAGE_TYPE_ARTIST";
    private const string LibraryArtistPageType = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST";
    private const string LibraryArtistPrefix = "MPLA";
    private const string AlbumPlaylistPrefix = "OLAK5uy_";
    private const string EditIcon = "EDIT";
    private const string GreyOut = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT";
    public const string EditablePlaylistHeader = "musicEditablePlaylistDetailHeaderRenderer";
    private static readonly HashSet<string> ArtistPageTypes = [ArtistPageType, "MUSIC_PAGE_TYPE_USER_CHANNEL"];
    private static readonly HashSet<string> ContinuationContainers = ["appendContinuationItemsAction", "continuationItems"];
    private static readonly string[] ContentRenderers =
    [
        "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer", EditablePlaylistHeader, "musicPlaylistShelfRenderer",
    ];

    public static SongPage SearchSongs(JsonNode root)
    {
        var shelf = SearchShelf(root);
        return shelf is null ? new SongPage([], null) : new SongPage(ShelfSongs(shelf), NextContinuation(shelf));
    }

    public static SearchPage<AlbumSummary> SearchAlbums(JsonNode root) => SearchPageOf(root, AlbumRow);

    public static SearchPage<ArtistSummary> SearchArtists(JsonNode root) => SearchPageOf(root, ArtistRow);

    private static SearchPage<T> SearchPageOf<T>(JsonNode root, Func<JsonObject, T?> row) where T : class
    {
        var shelf = SearchShelf(root);
        if (shelf is null) return new SearchPage<T>([], null);
        var items = shelf.Path("contents").Items().Select(i => i.Path("musicResponsiveListItemRenderer").Obj()).OfType<JsonObject>().Select(row).OfType<T>().ToList();
        return new SearchPage<T>(items, NextContinuation(shelf));
    }

    private static JsonObject? SearchShelf(JsonNode root) =>
        root.Path("continuationContents", "musicShelfContinuation").Obj()
        ?? root.Path("contents", "tabbedSearchResultsRenderer", "tabs").FirstItem()
            .Path("tabRenderer", "content", "sectionListRenderer", "contents").Items()
            .Select(i => i.Path("musicShelfRenderer").Obj()).FirstOrDefault(s => s is not null);

    /// <summary>打ち込み中の候補。1つめの区切りに語の候補、2つめに曲・アルバム・アーティストの行。</summary>
    public static SearchSuggestions SearchSuggestions(JsonNode root)
    {
        var contents = root.Path("contents").Items().SelectMany(i => i.Path("searchSuggestionsSectionRenderer", "contents").Items()).ToList();
        var queries = contents.Select(item =>
        {
            var s = item.Path("searchSuggestionRenderer").Obj() ?? item.Path("historySuggestionRenderer").Obj();
            if (s is null) return null;
            return s.Path("navigationEndpoint", "searchEndpoint", "query").Str() ?? TextOf(s.Path("suggestion"));
        }).OfType<string>().Distinct().ToList();
        var rows = contents.Select(i => i.Path("musicResponsiveListItemRenderer").Obj()).OfType<JsonObject>().ToList();
        return new SearchSuggestions(
            queries,
            rows.Where(r => PageTypeOfBrowse(r.Path("navigationEndpoint", "browseEndpoint")) is null).Select(ResponsiveListItemSong).OfType<Song>().ToList(),
            rows.Select(AlbumRow).OfType<AlbumSummary>().ToList(),
            rows.Select(ArtistRow).OfType<ArtistSummary>().ToList());
    }

    private static AlbumSummary? AlbumRow(JsonObject item)
    {
        var browse = item.Path("navigationEndpoint", "browseEndpoint");
        if (PageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_ALBUM") return null;
        var columns = FlexColumnRuns(item);
        var id = browse.Path("browseId").Str();
        var title = RunsText(columns.ElementAtOrDefault(0) ?? []);
        if (id is null || title is null) return null;
        return new AlbumSummary(id, title, RunsText(columns.Skip(1).SelectMany(c => c).ToList()), LastThumbnailUrl(item.Path("thumbnail")));
    }

    private static ArtistSummary? ArtistRow(JsonObject item)
    {
        var browse = item.Path("navigationEndpoint", "browseEndpoint");
        var browseId = browse.Path("browseId").Str();
        if (browseId is null) return null;
        string? artistId = PageTypeOfBrowse(browse) switch
        {
            ArtistPageType => browseId,
            // ライブラリのアーティスト（MPLA + チャンネルの ID）。アーティストのページはチャンネルの ID で開く
            LibraryArtistPageType when browseId.StartsWith(LibraryArtistPrefix, StringComparison.Ordinal) => browseId[LibraryArtistPrefix.Length..],
            _ => null,
        };
        if (artistId is null) return null;
        var columns = FlexColumnRuns(item);
        var name = RunsText(columns.ElementAtOrDefault(0) ?? []);
        if (name is null) return null;
        return new ArtistSummary(artistId, name, RunsText(columns.Skip(1).SelectMany(c => c).ToList()), LastThumbnailUrl(item.Path("thumbnail")));
    }

    private static List<List<JsonObject>> FlexColumnRuns(JsonObject item) =>
        item.Path("flexColumns").Items().Select(c => c.Path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").Items()).ToList();

    /// <summary>ホーム（FEmusic_home）と気分・ジャンルのページ。棚ごとに読める項目だけを取る。</summary>
    public static HomePage HomePage(JsonNode root)
    {
        var list = root.Path("continuationContents", "sectionListContinuation").Obj()
            ?? root.Path("contents", "singleColumnBrowseResultsRenderer", "tabs").FirstItem().Path("tabRenderer", "content", "sectionListRenderer").Obj();
        if (list is null) return new HomePage([], null, []);
        var shelves = new List<HomeShelf>();
        foreach (var section in list.Path("contents").Items())
        {
            var carousel = section.Path("musicCarouselShelfRenderer").Obj();
            if (carousel is null) continue;
            var header = carousel.Path("header", "musicCarouselShelfBasicHeaderRenderer");
            var items = carousel.Path("contents").Items().Select(HomeItemOf).OfType<HomeItem>().ToList();
            if (items.Count == 0) continue;
            shelves.Add(new HomeShelf(TextOf(header.Path("title")) ?? "", TextOf(header.Path("strapline")), items));
        }
        var continuation = list.Path("continuations").Items().Select(c => c.Path("nextContinuationData", "continuation").Str()).FirstOrDefault(c => c is not null);
        var chips = list.Path("header").FindAll("chipCloudChipRenderer").Select(chip =>
        {
            var browse = chip.Path("navigationEndpoint", "browseEndpoint");
            if (browse.Path("browseId").Str() != "FEmusic_home") return null;
            var text = TextOf(chip.Path("text"));
            var p = browse.Path("params").Str();
            return text is null || p is null ? null : new HomeChip(text, p);
        }).OfType<HomeChip>().ToList();
        return new HomePage(shelves, continuation, chips);
    }

    /// <summary>気分とジャンル（FEmusic_moods_and_genres）。</summary>
    public static List<MoodSection> Moods(JsonNode root) =>
        root.FindAll("gridRenderer").Select(grid =>
        {
            var categories = grid.Path("items").Items().Select(item =>
            {
                var button = item.Path("musicNavigationButtonRenderer").Obj();
                if (button is null) return null;
                var title = TextOf(button.Path("buttonText"));
                var p = button.Path("clickCommand", "browseEndpoint", "params").Str();
                return title is null || p is null ? null : new MoodCategory(title, p, button.Path("solid", "leftStripeColor").Long());
            }).OfType<MoodCategory>().ToList();
            return categories.Count == 0 ? null : new MoodSection(TextOf(grid.Path("header", "gridHeaderRenderer", "title")) ?? "", categories);
        }).OfType<MoodSection>().ToList();

    public static List<AlbumSummary> NewReleases(JsonNode root) =>
        GridItems(root).OfType<HomeItem.AlbumItem>().Select(a => a.Album).ToList();

    /// <summary>ライブラリのアーティスト・アルバムの一覧。格子（カード）と棚（行）のどちらの形でも読む。</summary>
    public static List<HomeItem> LibraryCards(JsonNode root) =>
        GridItems(root)
            .Concat(root.FindAll("gridContinuation").SelectMany(g => g.Path("items").Items()).Select(HomeItemOf).OfType<HomeItem>())
            .Concat(root.FindAll("musicShelfRenderer").Concat(root.FindAll("musicShelfContinuation")).SelectMany(s => s.Path("contents").Items()).Select(HomeItemOf).OfType<HomeItem>())
            .ToList();

    public static string? LibraryContinuation(JsonNode root) =>
        root.FindAll("nextContinuationData").Select(c => c.Path("continuation").Str()).FirstOrDefault(c => c is not null)
        ?? root.FindAll("continuationItemRenderer").Select(c => c.Path("continuationEndpoint", "continuationCommand", "token").Str()).FirstOrDefault(c => c is not null);

    public static List<HomeItem> GridItems(JsonNode root) =>
        root.FindAll("gridRenderer").SelectMany(g => g.Path("items").Items()).Select(HomeItemOf).OfType<HomeItem>().ToList();

    private static HomeItem? HomeItemOf(JsonObject card)
    {
        if (card.Path("musicResponsiveListItemRenderer").Obj() is { } row)
        {
            if (AlbumRow(row) is { } album) return new HomeItem.AlbumItem(album);
            if (ArtistRow(row) is { } artist) return new HomeItem.ArtistItem(artist);
            return ResponsiveListItemSong(row) is { } song ? new HomeItem.SongItem(song) : null;
        }
        var item = card.Path("musicTwoRowItemRenderer").Obj();
        if (item is null) return null;
        var title = TextOf(item.Path("title"));
        if (title is null) return null;
        var subtitleRuns = item.Path("subtitle", "runs").Items();
        var subtitle = RunsText(subtitleRuns);
        var thumbnail = LastThumbnailUrl(item.Path("thumbnailRenderer"));
        var endpoint = item.Path("navigationEndpoint");
        if (endpoint.Path("watchEndpoint", "videoId").Str() is { } videoId)
            return new HomeItem.SongItem(new Song(videoId, title, ArtistsOf(subtitleRuns), AlbumOf(subtitleRuns), null, thumbnail));
        var browse = endpoint.Path("browseEndpoint");
        var browseId = browse.Path("browseId").Str();
        if (browseId is null) return null;
        return PageTypeOfBrowse(browse) switch
        {
            "MUSIC_PAGE_TYPE_ALBUM" => new HomeItem.AlbumItem(new AlbumSummary(browseId, title, subtitle, thumbnail)),
            ArtistPageType => new HomeItem.ArtistItem(new ArtistSummary(browseId, title, subtitle, thumbnail)),
            "MUSIC_PAGE_TYPE_PLAYLIST" => new HomeItem.PlaylistItem(new PlaylistSummary(RemoveVl(browseId), title, subtitle, thumbnail, EditIcon.In(MenuIcons(item)))),
            _ => null,
        };
    }

    private static bool In(this string value, ISet<string> set) => set.Contains(value);

    /// <summary>再生履歴（FEmusic_history）。「今日」「今週」のような区切りごとに曲。</summary>
    public static HistoryPage HistoryPage(JsonNode root)
    {
        var container = root.Path("continuationContents", "sectionListContinuation").Obj()
            ?? root.Path("contents", "singleColumnBrowseResultsRenderer", "tabs").FirstItem().Path("tabRenderer", "content", "sectionListRenderer").Obj();
        if (container is null) return new HistoryPage([], null);
        var shelves = container.FindAll("musicShelfRenderer");
        var sections = shelves.Select(s => new HistorySection(TextOf(s.Path("title")), ShelfSongs(s))).Where(s => s.Songs.Count > 0).ToList();
        var continuation = NextContinuation(container) ?? (shelves.Count > 0 ? NextContinuation(shelves[^1]) : null);
        return new HistoryPage(sections, continuation);
    }

    /// <summary>next（ラジオ）の最初のページと続きのページ。</summary>
    public static UpNextPage UpNext(JsonNode root)
    {
        var panel = root.Path("continuationContents", "playlistPanelContinuation").Obj() ?? QueuePanel(root).Obj();
        if (panel is null) return new UpNextPage([], null, null);
        var songs = panel.Path("contents").Items().Select(i => i.Path("playlistPanelVideoRenderer").Obj()).OfType<JsonObject>().Select(PlaylistPanelSong).OfType<Song>().ToList();
        var continuation = panel.Path("continuations").Items()
            .Select(c => c.Path("nextRadioContinuationData", "continuation").Str() ?? c.Path("nextContinuationData", "continuation").Str())
            .FirstOrDefault(c => c is not null);
        return new UpNextPage(songs, continuation, panel.Path("playlistId").Str());
    }

    private static JsonNode? QueuePanel(JsonNode root) =>
        WatchTabs(root).FirstItem().Path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer");

    private static JsonNode? WatchTabs(JsonNode root) =>
        root.Path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs");

    /// <summary>ライブラリのプレイリスト一覧などのカード。</summary>
    public static List<PlaylistSummary> Playlists(JsonNode root) =>
        root.FindAll("musicTwoRowItemRenderer").Select(item =>
        {
            var browse = item.Path("navigationEndpoint", "browseEndpoint");
            var browseId = browse.Path("browseId").Str();
            if (browseId is null) return null;
            if (PageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_PLAYLIST" && !browseId.StartsWith("VL", StringComparison.Ordinal)) return null;
            var title = TextOf(item.Path("title"));
            if (title is null) return null;
            return new PlaylistSummary(RemoveVl(browseId), title, TextOf(item.Path("subtitle")), LastThumbnailUrl(item.Path("thumbnailRenderer")), MenuIcons(item).Contains(EditIcon));
        }).OfType<PlaylistSummary>().DistinctBy(p => p.PlaylistId).ToList();

    private static HashSet<string> MenuIcons(JsonObject card)
    {
        var icons = new HashSet<string>();
        void Walk(JsonNode? node)
        {
            switch (node)
            {
                case JsonObject obj:
                    foreach (var (name, value) in obj)
                    {
                        if (name == "iconType" && value.Str() is { } icon) icons.Add(icon);
                        else Walk(value);
                    }
                    break;
                case JsonArray array:
                    foreach (var i in array) Walk(i);
                    break;
            }
        }
        Walk(card.Path("menu"));
        return icons;
    }

    /// <summary>プレイリストの最初のページ（browse VL&lt;id&gt;）と続きのページ。</summary>
    public static PlaylistPage PlaylistPage(JsonNode root)
    {
        var appended = root.Path("onResponseReceivedActions").Items()
            .Select(a => a.Path("appendContinuationItemsAction", "continuationItems") as JsonArray).FirstOrDefault(a => a is not null);
        var shelf = root.FindAll("musicPlaylistShelfRenderer").FirstOrDefault();
        var items = appended is not null ? appended.Items() : shelf.Path("contents").Items();
        var rows = items.Select(i => i.Path("musicResponsiveListItemRenderer").Obj()).OfType<JsonObject>()
            .Select(row => (Song: ResponsiveListItemSong(row), SetId: row.Path("playlistItemData", "playlistSetVideoId").Str()))
            .Where(r => r.Song is not null).ToList();
        var continuation = items.LastOrDefault().Path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").Str()
            ?? shelf.Path("continuations").Items().Select(c => c.Path("nextContinuationData", "continuation").Str()).FirstOrDefault(c => c is not null);
        var header = root.FindAll("musicResponsiveHeaderRenderer").FirstOrDefault() ?? root.FindAll("musicDetailHeaderRenderer").FirstOrDefault();
        var title = TextOf(header.Path("title"));
        var subtitle = header is null ? null : string.Join(" • ", new[] { TextOf(header.Path("subtitle")), TextOf(header.Path("secondSubtitle")) }.Where(s => !string.IsNullOrEmpty(s)));
        var thumbnail = header is null ? null : LastThumbnailUrl(header.Path("thumbnail")) ?? header.FindAll("musicThumbnailRenderer").Select(t => t.Path("thumbnail", "thumbnails").Items().LastOrDefault().Path("url").Str()).FirstOrDefault(u => u is not null);
        var editable = root.FindAll(EditablePlaylistHeader).Count > 0;
        JsonNode? songArea = appended ?? (JsonNode?)shelf;
        var endConfirmed = continuation is null && songArea is not null && !ContainsKeyLike(songArea, "continuation");
        var sortable = root.FindAll("sortFilterSubMenuRenderer").Count > 0;
        return new PlaylistPage(title, rows.Select(r => r.Song!).ToList(), continuation, rows.Select(r => r.SetId).ToList(), editable, endConfirmed,
            string.IsNullOrEmpty(subtitle) ? null : subtitle, thumbnail, sortable);
    }

    private static bool ContainsKeyLike(JsonNode? root, string fragment) => root switch
    {
        JsonObject obj => obj.Any(p => (!ContinuationContainers.Contains(p.Key) && p.Key.Contains(fragment, StringComparison.OrdinalIgnoreCase)) || ContainsKeyLike(p.Value, fragment)),
        JsonArray array => array.Any(i => ContainsKeyLike(i, fragment)),
        _ => false,
    };

    public static string? EditPlaylistStatus(JsonNode root) => root.Path("status").Str();

    public static string? CreatedPlaylistId(JsonNode root) => root.Path("playlistId").Str();

    /// <summary>next の最初の応答から、その曲の評価と歌詞のタブの browseId。</summary>
    public static SongInfo SongInfo(JsonNode root, string videoId)
    {
        LikeStatus? like = null;
        var button = root.Path("playerOverlays", "playerOverlayRenderer", "actions").Items().Select(a => a.Path("likeButtonRenderer").Obj()).FirstOrDefault(b => b is not null);
        if (button is not null && button.Path("target", "videoId").Str() == videoId)
        {
            like = button.Path("likeStatus").Str() switch
            {
                "LIKE" => LikeStatus.Like,
                "DISLIKE" => LikeStatus.Dislike,
                "INDIFFERENT" => LikeStatus.Indifferent,
                _ => null,
            };
        }
        var lyrics = WatchTabs(root).Items().Select(t => t.Path("tabRenderer", "endpoint", "browseEndpoint"))
            .FirstOrDefault(b => PageTypeOfBrowse(b) == "MUSIC_PAGE_TYPE_TRACK_LYRICS").Path("browseId").Str();
        return new SongInfo(like, lyrics, MusicVideoOf(root, videoId));
    }

    /// <summary>
    /// 曲に重ねる映像（Android の musicVideoInfo と同じ）。公式の MV・投稿の動画はその曲自身、音源（ATV）は対応する MV（時刻の対応表があるときだけ）。
    /// </summary>
    public static MusicVideo? MusicVideoOf(JsonNode root, string videoId)
    {
        var panel = WatchTabs(root).FirstItem().Path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer");
        foreach (var item in panel.Path("contents").Items())
        {
            var wrapper = item.Path("playlistPanelVideoWrapperRenderer");
            var renderer = item.Path("playlistPanelVideoRenderer") ?? wrapper.Path("primaryRenderer", "playlistPanelVideoRenderer");
            if (renderer.Path("videoId").Str() != videoId) continue;
            var type = renderer.Path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str();
            if (type is null) return null;
            if (type != "MUSIC_VIDEO_TYPE_ATV") return new MusicVideo(videoId, []);
            var counterpart = wrapper.Path("counterpart").FirstItem();
            var counterpartId = counterpart.Path("counterpartRenderer", "playlistPanelVideoRenderer", "videoId").Str();
            var segments = counterpart.Path("segmentMap", "segment").Items()
                .Select(s => long.TryParse(s.Path("primaryVideoStartTimeMilliseconds").Str(), out var p) && long.TryParse(s.Path("counterpartVideoStartTimeMilliseconds").Str(), out var c) &&
                             long.TryParse(s.Path("durationMilliseconds").Str(), out var d) ? new TimeSegment(p, c, d) : null)
                .OfType<TimeSegment>().ToList();
            // 対応表が無い MV は時刻を合わせられないので使わない
            return counterpartId is not null && segments.Count > 0 ? new MusicVideo(counterpartId, segments) : null;
        }
        return null;
    }

    /// <summary>歌詞のページ（時刻なし）の本文と出典。</summary>
    public static SongLyrics.Plain? PlainLyrics(JsonNode root)
    {
        var shelf = root.Path("contents", "sectionListRenderer", "contents").Items().Select(i => i.Path("musicDescriptionShelfRenderer").Obj()).FirstOrDefault(s => s is not null);
        var text = TextOf(shelf.Path("description"));
        return text is null ? null : new SongLyrics.Plain(text, TextOf(shelf.Path("footer")));
    }

    /// <summary>アルバムのページ（browse MPREb_…）。曲の行に無いアーティスト・アルバム・画像はヘッダの値で埋める。</summary>
    public static AlbumPage? AlbumPage(JsonNode root, string browseId)
    {
        var header = root.FindAll("musicResponsiveHeaderRenderer").FirstOrDefault();
        var title = TextOf(header.Path("title"));
        if (header is null || title is null) return null;
        var artists = ArtistsOf(header.Path("straplineTextOne", "runs").Items());
        var thumbnail = LastThumbnailUrl(header.Path("thumbnail"));
        var playlistId = header.Path("buttons").Items().Select(b => b.Path("musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId").Str()).FirstOrDefault(p => p is not null)
            ?? CanonicalList(root.Path("microformat", "microformatDataRenderer", "urlCanonical").Str())
            ?? root.FindAll("watchPlaylistEndpoint").Select(w => w.Path("playlistId").Str()).FirstOrDefault(p => p?.StartsWith(AlbumPlaylistPrefix, StringComparison.Ordinal) == true);
        var album = new AlbumRef(title, browseId);
        var shelf = root.Path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").Items()
            .Select(i => i.Path("musicShelfRenderer").Obj()).FirstOrDefault(s => s is not null);
        var songs = shelf.Path("contents").Items().Select(row =>
        {
            var item = row.Path("musicResponsiveListItemRenderer").Obj();
            if (item is null || ResponsiveListItemSong(item) is not { } song) return null;
            var rowRuns = item.Path("flexColumns").Items().Skip(1).SelectMany(c => c.Path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").Items()).ToList();
            var typed = TypedArtistsOf(rowRuns);
            return song with { Artists = typed.Count > 0 ? typed : artists, Album = song.Album ?? album, ThumbnailUrl = song.ThumbnailUrl ?? thumbnail };
        }).OfType<Song>().ToList();
        return new AlbumPage(browseId, title, TextOf(header.Path("subtitle")), artists, thumbnail, playlistId, songs);
    }

    private static string? CanonicalList(string? url)
    {
        if (url is null) return null;
        var at = url.IndexOf("list=", StringComparison.Ordinal);
        if (at < 0) return null;
        var id = url[(at + 5)..].Split('&')[0];
        return id.StartsWith(AlbumPlaylistPrefix, StringComparison.Ordinal) ? id : null;
    }

    /// <summary>アーティストのページ（browse UC…）。</summary>
    public static ArtistPage? ArtistPage(JsonNode root)
    {
        var header = root.Path("header", "musicImmersiveHeaderRenderer").Obj() ?? root.Path("header", "musicVisualHeaderRenderer").Obj();
        var name = TextOf(header.Path("title"));
        if (header is null || name is null) return null;
        var thumbnail = LastThumbnailUrl(header.Path("thumbnail"));
        var sections = root.Path("contents", "singleColumnBrowseResultsRenderer", "tabs").FirstItem().Path("tabRenderer", "content", "sectionListRenderer", "contents").Items();
        var songShelf = sections.Select(s => s.Path("musicShelfRenderer").Obj()).FirstOrDefault(s => s is not null);
        var allSongs = songShelf.Path("title", "runs").FirstItem().Path("navigationEndpoint", "browseEndpoint", "browseId").Str()
            ?? songShelf.Path("bottomEndpoint", "browseEndpoint", "browseId").Str();
        var albumShelves = sections.Select(section =>
        {
            var carousel = section.Path("musicCarouselShelfRenderer").Obj();
            if (carousel is null) return null;
            var albums = carousel.Path("contents").Items().Select(card =>
            {
                var item = card.Path("musicTwoRowItemRenderer").Obj();
                var browse = item.Path("navigationEndpoint", "browseEndpoint");
                if (item is null || PageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_ALBUM") return null;
                var id = browse.Path("browseId").Str();
                var t = TextOf(item.Path("title"));
                return id is null || t is null ? null : new AlbumSummary(id, t, TextOf(item.Path("subtitle")), LastThumbnailUrl(item.Path("thumbnailRenderer")));
            }).OfType<AlbumSummary>().ToList();
            return albums.Count == 0 ? null : new AlbumShelf(TextOf(carousel.Path("header", "musicCarouselShelfBasicHeaderRenderer", "title")) ?? "", albums);
        }).OfType<AlbumShelf>().ToList();
        var subscribe = header.Path("subscriptionButton", "subscribeButtonRenderer");
        bool? subscribed = subscribe.Path("subscribed") is System.Text.Json.Nodes.JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;
        // 動画の棚: カードが曲の再生（watchEndpoint）になっている棚
        var videos = sections.SelectMany(section => section.Path("musicCarouselShelfRenderer", "contents").Items()).Select(card =>
        {
            var item = card.Path("musicTwoRowItemRenderer");
            var watch = item.Path("navigationEndpoint", "watchEndpoint");
            var videoId = watch.Path("videoId").Str();
            var title = TextOf(item.Path("title"));
            return videoId is null || title is null ? null
                : new Song(videoId, title, [new ArtistRef(name, null)], null, null, LastThumbnailUrl(item.Path("thumbnailRenderer")),
                    watch.Path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str());
        }).OfType<Song>().ToList();
        return new ArtistPage(name, thumbnail, ShelfSongs(songShelf), allSongs?.StartsWith("VL", StringComparison.Ordinal) == true ? allSongs[2..] : null, albumShelves,
            subscribe.Path("channelId").Str(), subscribed, videos);
    }

    /// <summary>ログインしていないときに返る「ログインしてください」の案内だけの応答か。</summary>
    public static bool IsSignInPrompt(JsonNode root) =>
        root.FindAll("messageRenderer").Count > 0 && ContentRenderers.All(r => root.FindAll(r).Count == 0);

    private static string? TextOf(JsonNode? node) => RunsText(node.Path("runs").Items());

    private static string? RunsText(IEnumerable<JsonObject> runs)
    {
        var text = string.Concat(runs.Select(r => r.Path("text").Str() ?? ""));
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }

    private static string? LastThumbnailUrl(JsonNode? node) =>
        node.Path("musicThumbnailRenderer", "thumbnail", "thumbnails").Items().LastOrDefault().Path("url").Str();

    private static List<Song> ShelfSongs(JsonNode? shelf) =>
        shelf.Path("contents").Items().Select(i => i.Path("musicResponsiveListItemRenderer").Obj()).OfType<JsonObject>().Select(ResponsiveListItemSong).OfType<Song>().ToList();

    private static int? DurationOf(IEnumerable<JsonObject> runs) =>
        runs.Select(r => r.Path("text").Str() is { } t ? ParseDuration(t) : null).FirstOrDefault(d => d is not null);

    /// <summary>検索・プレイリストの行（musicResponsiveListItemRenderer）。</summary>
    public static Song? ResponsiveListItemSong(JsonObject item)
    {
        // 地域・権利の都合で再生できない行（グレーアウト）は除く
        if (item.Path("musicItemRendererDisplayPolicy").Str() == GreyOut) return null;
        var columns = FlexColumnRuns(item);
        var titleRuns = columns.ElementAtOrDefault(0) ?? [];
        var videoId = item.Path("playlistItemData", "videoId").Str()
            ?? titleRuns.Select(r => r.Path("navigationEndpoint", "watchEndpoint", "videoId").Str()).FirstOrDefault(v => v is not null)
            ?? item.Path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").Str();
        var title = RunsText(titleRuns);
        if (videoId is null || title is null) return null;
        var detailRuns = columns.Skip(1).SelectMany(c => c).ToList();
        var fixedDuration = DurationOf(item.Path("fixedColumns").Items().SelectMany(c => c.Path("musicResponsiveListItemFixedColumnRenderer", "text", "runs").Items()));
        var kind = titleRuns.Select(r => r.Path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str()).FirstOrDefault(v => v is not null)
            ?? item.Path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str();
        return new Song(videoId, title, ArtistsOf(detailRuns), AlbumOf(detailRuns), fixedDuration ?? DurationOf(detailRuns), LastThumbnailUrl(item.Path("thumbnail")), kind);
    }

    /// <summary>ラジオ・キューの行（playlistPanelVideoRenderer）。</summary>
    public static Song? PlaylistPanelSong(JsonObject item)
    {
        var videoId = item.Path("videoId").Str() ?? item.Path("navigationEndpoint", "watchEndpoint", "videoId").Str();
        var title = TextOf(item.Path("title"));
        if (videoId is null || title is null) return null;
        var byline = item.Path("longBylineText", "runs").Items();
        if (byline.Count == 0) byline = item.Path("shortBylineText", "runs").Items();
        return new Song(videoId, title, ArtistsOf(byline), AlbumOf(byline), DurationOf(item.Path("lengthText", "runs").Items()),
            item.Path("thumbnail", "thumbnails").Items().LastOrDefault().Path("url").Str(),
            item.Path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").Str());
    }

    /// <summary>アーティストの runs。ページ種別が付いていればそれを、無ければ区切り（" • "）の前をアーティストとみなす。</summary>
    private static List<ArtistRef> ArtistsOf(List<JsonObject> runs)
    {
        var typed = TypedArtistsOf(runs);
        if (typed.Count > 0) return typed;
        return runs.TakeWhile(r => r.Path("text").Str() != Separator)
            .Select(r => r.Path("text").Str()).OfType<string>()
            .Where(t => !string.IsNullOrWhiteSpace(t) && t != ", " && t != " & " && ParseDuration(t) is null)
            .Select(t => new ArtistRef(t, null)).ToList();
    }

    private static List<ArtistRef> TypedArtistsOf(List<JsonObject> runs) =>
        runs.Where(r => PageTypeOf(r) is { } p && ArtistPageTypes.Contains(p)).Select(run =>
        {
            // チャンネル（USER_CHANNEL）はアーティストのページとして開けないので、名前だけにする
            var browseId = PageTypeOf(run) == ArtistPageType ? run.Path("navigationEndpoint", "browseEndpoint", "browseId").Str() : null;
            return run.Path("text").Str() is { } text ? new ArtistRef(text, browseId) : null;
        }).OfType<ArtistRef>().ToList();

    private static AlbumRef? AlbumOf(List<JsonObject> runs)
    {
        var run = runs.FirstOrDefault(r => PageTypeOf(r) == "MUSIC_PAGE_TYPE_ALBUM");
        return run?.Path("text").Str() is { } text ? new AlbumRef(text, run.Path("navigationEndpoint", "browseEndpoint", "browseId").Str()) : null;
    }

    private static string? PageTypeOf(JsonObject run) => PageTypeOfBrowse(run.Path("navigationEndpoint", "browseEndpoint"));

    private static string? PageTypeOfBrowse(JsonNode? browse) =>
        browse.Path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").Str();

    private static string? NextContinuation(JsonObject shelf) =>
        shelf.Path("continuations").Items().Select(c => c.Path("nextContinuationData", "continuation").Str()).FirstOrDefault(c => c is not null)
        ?? shelf.Path("contents").Items().LastOrDefault().Path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").Str();

    private static string RemoveVl(string id) => id.StartsWith("VL", StringComparison.Ordinal) ? id[2..] : id;

    /// <summary>"4:22" や "1:02:03" を秒に。それ以外は null。</summary>
    public static int? ParseDuration(string text)
    {
        if (!DurationPattern().IsMatch(text)) return null;
        return text.Split(':').Aggregate(0, (total, part) => total * 60 + int.Parse(part));
    }

    [GeneratedRegex(@"^\d{1,2}(:\d{2}){1,2}$")]
    private static partial Regex DurationPattern();
}
