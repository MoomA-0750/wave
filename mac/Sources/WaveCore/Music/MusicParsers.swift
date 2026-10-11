import Foundation

/// InnerTube の応答（JSON）からモデルへの純粋な変換（Android 版の MusicParsers と同じ規則。Windows の MusicParsers を移したもの）。
/// 形が変わっても例外で落とさず、取れない項目は飛ばす。
public enum MusicParsers {
    private static let separator = " • "
    private static let artistPageType = "MUSIC_PAGE_TYPE_ARTIST"
    private static let libraryArtistPageType = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    private static let libraryArtistPrefix = "MPLA"
    private static let albumPlaylistPrefix = "OLAK5uy_"
    private static let editIcon = "EDIT"
    private static let greyOut = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
    public static let editablePlaylistHeader = "musicEditablePlaylistDetailHeaderRenderer"
    private static let artistPageTypes: Set<String> = [artistPageType, "MUSIC_PAGE_TYPE_USER_CHANNEL"]
    private static let continuationContainers: Set<String> = ["appendContinuationItemsAction", "continuationItems"]
    private static let contentRenderers = [
        "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer", "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer", editablePlaylistHeader, "musicPlaylistShelfRenderer",
    ]

    public static func searchSongs(_ root: JSON) -> SongPage {
        guard let shelf = searchShelf(root) else { return SongPage(songs: [], continuation: nil) }
        return SongPage(songs: shelfSongs(shelf), continuation: nextContinuation(shelf))
    }

    public static func searchAlbums(_ root: JSON) -> SearchPage<AlbumSummary> { searchPageOf(root, albumRow) }

    public static func searchArtists(_ root: JSON) -> SearchPage<ArtistSummary> { searchPageOf(root, artistRow) }

    private static func searchPageOf<T>(_ root: JSON, _ row: (JSON) -> T?) -> SearchPage<T> {
        guard let shelf = searchShelf(root) else { return SearchPage(items: [], continuation: nil) }
        let items = shelf.path("contents").items.compactMap { $0.path("musicResponsiveListItemRenderer").asObject }.compactMap(row)
        return SearchPage(items: items, continuation: nextContinuation(shelf))
    }

    private static func searchShelf(_ root: JSON) -> JSON? {
        root.path("continuationContents", "musicShelfContinuation").asObject
            ?? root.path("contents", "tabbedSearchResultsRenderer", "tabs").firstItem
            .path("tabRenderer", "content", "sectionListRenderer", "contents").items
            .lazy.compactMap { $0.path("musicShelfRenderer").asObject }.first
    }

    /// 打ち込み中の候補。1つめの区切りに語の候補、2つめに曲・アルバム・アーティストの行。
    public static func searchSuggestions(_ root: JSON) -> SearchSuggestions {
        let contents = root.path("contents").items.flatMap { $0.path("searchSuggestionsSectionRenderer", "contents").items }
        let queries = contents.compactMap { item -> String? in
            guard let s = item.path("searchSuggestionRenderer").asObject ?? item.path("historySuggestionRenderer").asObject else { return nil }
            return s.path("navigationEndpoint", "searchEndpoint", "query").str ?? textOf(s.path("suggestion"))
        }.distinct { $0 }
        let rows = contents.compactMap { $0.path("musicResponsiveListItemRenderer").asObject }
        return SearchSuggestions(
            queries: queries,
            songs: rows.filter { pageTypeOfBrowse($0.path("navigationEndpoint", "browseEndpoint")) == nil }.compactMap(responsiveListItemSong),
            albums: rows.compactMap(albumRow),
            artists: rows.compactMap(artistRow))
    }

    private static func albumRow(_ item: JSON) -> AlbumSummary? {
        let browse = item.path("navigationEndpoint", "browseEndpoint")
        guard pageTypeOfBrowse(browse) == "MUSIC_PAGE_TYPE_ALBUM" else { return nil }
        let columns = flexColumnRuns(item)
        guard let id = browse.path("browseId").str, let title = runsText(columns.first ?? []) else { return nil }
        return AlbumSummary(browseId: id, title: title, subtitle: runsText(columns.dropFirst().flatMap { $0 }), thumbnailUrl: lastThumbnailUrl(item.path("thumbnail")))
    }

    private static func artistRow(_ item: JSON) -> ArtistSummary? {
        let browse = item.path("navigationEndpoint", "browseEndpoint")
        guard let browseId = browse.path("browseId").str else { return nil }
        let artistId: String?
        switch pageTypeOfBrowse(browse) {
        case artistPageType:
            artistId = browseId
        // ライブラリのアーティスト（MPLA + チャンネルの ID）。アーティストのページはチャンネルの ID で開く
        case libraryArtistPageType where browseId.hasPrefix(libraryArtistPrefix):
            artistId = String(browseId.dropFirst(libraryArtistPrefix.count))
        default:
            artistId = nil
        }
        guard let artistId else { return nil }
        let columns = flexColumnRuns(item)
        guard let name = runsText(columns.first ?? []) else { return nil }
        return ArtistSummary(browseId: artistId, name: name, subtitle: runsText(columns.dropFirst().flatMap { $0 }), thumbnailUrl: lastThumbnailUrl(item.path("thumbnail")))
    }

    private static func flexColumnRuns(_ item: JSON) -> [[JSON]] {
        item.path("flexColumns").items.map { $0.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").items }
    }

    /// ホーム（FEmusic_home）と気分・ジャンルのページ。棚ごとに読める項目だけを取る。
    public static func homePage(_ root: JSON) -> HomePage {
        guard let list = root.path("continuationContents", "sectionListContinuation").asObject
            ?? root.path("contents", "singleColumnBrowseResultsRenderer", "tabs").firstItem.path("tabRenderer", "content", "sectionListRenderer").asObject
        else { return HomePage(shelves: [], continuation: nil, chips: []) }
        var shelves: [HomeShelf] = []
        for section in list.path("contents").items {
            guard let carousel = section.path("musicCarouselShelfRenderer").asObject else { continue }
            let header = carousel.path("header", "musicCarouselShelfBasicHeaderRenderer")
            let items = carousel.path("contents").items.compactMap(homeItemOf)
            if items.isEmpty { continue }
            shelves.append(HomeShelf(title: textOf(header.path("title")) ?? "", strapline: textOf(header.path("strapline")), items: items))
        }
        let continuation = list.path("continuations").items.lazy.compactMap { $0.path("nextContinuationData", "continuation").str }.first
        let chips = list.path("header").findAll("chipCloudChipRenderer").compactMap { chip -> HomeChip? in
            let browse = chip.path("navigationEndpoint", "browseEndpoint")
            guard browse.path("browseId").str == "FEmusic_home" else { return nil }
            guard let text = textOf(chip.path("text")), let p = browse.path("params").str else { return nil }
            return HomeChip(title: text, params: p)
        }
        return HomePage(shelves: shelves, continuation: continuation, chips: chips)
    }

    /// 気分とジャンル（FEmusic_moods_and_genres）。
    public static func moods(_ root: JSON) -> [MoodSection] {
        root.findAll("gridRenderer").compactMap { grid -> MoodSection? in
            let categories = grid.path("items").items.compactMap { item -> MoodCategory? in
                guard let button = item.path("musicNavigationButtonRenderer").asObject else { return nil }
                guard let title = textOf(button.path("buttonText")), let p = button.path("clickCommand", "browseEndpoint", "params").str else { return nil }
                return MoodCategory(title: title, params: p, color: button.path("solid", "leftStripeColor").long)
            }
            return categories.isEmpty ? nil : MoodSection(title: textOf(grid.path("header", "gridHeaderRenderer", "title")) ?? "", categories: categories)
        }
    }

    public static func newReleases(_ root: JSON) -> [AlbumSummary] {
        gridItems(root).compactMap { if case .album(let a) = $0 { return a } else { return nil } }
    }

    /// ライブラリのアーティスト・アルバムの一覧。格子（カード）と棚（行）のどちらの形でも読む。
    public static func libraryCards(_ root: JSON) -> [HomeItem] {
        gridItems(root)
            + root.findAll("gridContinuation").flatMap { $0.path("items").items }.compactMap(homeItemOf)
            + (root.findAll("musicShelfRenderer") + root.findAll("musicShelfContinuation")).flatMap { $0.path("contents").items }.compactMap(homeItemOf)
    }

    public static func libraryContinuation(_ root: JSON) -> String? {
        root.findAll("nextContinuationData").lazy.compactMap { $0.path("continuation").str }.first
            ?? root.findAll("continuationItemRenderer").lazy.compactMap { $0.path("continuationEndpoint", "continuationCommand", "token").str }.first
    }

    public static func gridItems(_ root: JSON) -> [HomeItem] {
        root.findAll("gridRenderer").flatMap { $0.path("items").items }.compactMap(homeItemOf)
    }

    private static func homeItemOf(_ card: JSON) -> HomeItem? {
        if let row = card.path("musicResponsiveListItemRenderer").asObject {
            if let album = albumRow(row) { return .album(album) }
            if let artist = artistRow(row) { return .artist(artist) }
            return responsiveListItemSong(row).map { .song($0) }
        }
        guard let item = card.path("musicTwoRowItemRenderer").asObject, let title = textOf(item.path("title")) else { return nil }
        let subtitleRuns = item.path("subtitle", "runs").items
        let subtitle = runsText(subtitleRuns)
        let thumbnail = lastThumbnailUrl(item.path("thumbnailRenderer"))
        let endpoint = item.path("navigationEndpoint")
        if let videoId = endpoint.path("watchEndpoint", "videoId").str {
            return .song(Song(videoId: videoId, title: title, artists: artistsOf(subtitleRuns), album: albumOf(subtitleRuns), durationSeconds: nil, thumbnailUrl: thumbnail))
        }
        let browse = endpoint.path("browseEndpoint")
        guard let browseId = browse.path("browseId").str else { return nil }
        switch pageTypeOfBrowse(browse) {
        case "MUSIC_PAGE_TYPE_ALBUM":
            return .album(AlbumSummary(browseId: browseId, title: title, subtitle: subtitle, thumbnailUrl: thumbnail))
        case artistPageType:
            return .artist(ArtistSummary(browseId: browseId, name: title, subtitle: subtitle, thumbnailUrl: thumbnail))
        case "MUSIC_PAGE_TYPE_PLAYLIST":
            return .playlist(PlaylistSummary(playlistId: removeVl(browseId), title: title, subtitle: subtitle, thumbnailUrl: thumbnail,
                                             editable: menuIcons(item).contains(editIcon)))
        default:
            return nil
        }
    }

    /// 再生履歴（FEmusic_history）。「今日」「今週」のような区切りごとに曲。
    public static func historyPage(_ root: JSON) -> HistoryPage {
        guard let container = root.path("continuationContents", "sectionListContinuation").asObject
            ?? root.path("contents", "singleColumnBrowseResultsRenderer", "tabs").firstItem.path("tabRenderer", "content", "sectionListRenderer").asObject
        else { return HistoryPage(sections: [], continuation: nil) }
        let shelves = container.findAll("musicShelfRenderer")
        let sections = shelves.map { HistorySection(title: textOf($0.path("title")), songs: shelfSongs($0)) }.filter { !$0.songs.isEmpty }
        let continuation = nextContinuation(container) ?? shelves.last.flatMap(nextContinuation)
        return HistoryPage(sections: sections, continuation: continuation)
    }

    /// next（ラジオ）の最初のページと続きのページ。
    public static func upNext(_ root: JSON) -> UpNextPage {
        guard let panel = root.path("continuationContents", "playlistPanelContinuation").asObject ?? queuePanel(root).asObject else {
            return UpNextPage(songs: [], continuation: nil, playlistId: nil)
        }
        let songs = panel.path("contents").items.compactMap { $0.path("playlistPanelVideoRenderer").asObject }.compactMap(playlistPanelSong)
        let continuation = panel.path("continuations").items.lazy
            .compactMap { $0.path("nextRadioContinuationData", "continuation").str ?? $0.path("nextContinuationData", "continuation").str }.first
        return UpNextPage(songs: songs, continuation: continuation, playlistId: panel.path("playlistId").str)
    }

    private static func queuePanel(_ root: JSON) -> JSON? {
        watchTabs(root).firstItem.path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
    }

    private static func watchTabs(_ root: JSON) -> JSON? {
        root.path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
    }

    /// ライブラリのプレイリスト一覧などのカード。
    public static func playlists(_ root: JSON) -> [PlaylistSummary] {
        root.findAll("musicTwoRowItemRenderer").compactMap { item -> PlaylistSummary? in
            let browse = item.path("navigationEndpoint", "browseEndpoint")
            guard let browseId = browse.path("browseId").str else { return nil }
            if pageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_PLAYLIST" && !browseId.hasPrefix("VL") { return nil }
            guard let title = textOf(item.path("title")) else { return nil }
            return PlaylistSummary(playlistId: removeVl(browseId), title: title, subtitle: textOf(item.path("subtitle")),
                                   thumbnailUrl: lastThumbnailUrl(item.path("thumbnailRenderer")), editable: menuIcons(item).contains(editIcon))
        }.distinct { $0.playlistId }
    }

    private static func menuIcons(_ card: JSON) -> Set<String> {
        var icons = Set<String>()
        func walk(_ node: JSON?) {
            switch node {
            case .object(let o)?:
                for (name, value) in o.pairs {
                    if name == "iconType", let icon = value.str { icons.insert(icon) } else { walk(value) }
                }
            case .array(let a)?:
                for i in a { walk(i) }
            default:
                break
            }
        }
        walk(card.path("menu"))
        return icons
    }

    /// プレイリストの最初のページ（browse VL<id>）と続きのページ。
    public static func playlistPage(_ root: JSON) -> PlaylistPage {
        let appended = root.path("onResponseReceivedActions").items.lazy
            .compactMap { a -> JSON? in
                let v = a.path("appendContinuationItemsAction", "continuationItems")
                return v?.arr != nil ? v : nil
            }.first
        let shelf = root.findAll("musicPlaylistShelfRenderer").first
        let items = appended != nil ? appended.items : shelf.path("contents").items
        let rows = items.compactMap { $0.path("musicResponsiveListItemRenderer").asObject }
            .compactMap { row -> (song: Song, setId: String?)? in
                guard let song = responsiveListItemSong(row) else { return nil }
                return (song, row.path("playlistItemData", "playlistSetVideoId").str)
            }
        let continuation = items.last.path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
            ?? shelf.path("continuations").items.lazy.compactMap { $0.path("nextContinuationData", "continuation").str }.first
        let header = root.findAll("musicResponsiveHeaderRenderer").first ?? root.findAll("musicDetailHeaderRenderer").first
        let title = textOf(header.path("title"))
        let subtitle = header == nil ? nil
            : [textOf(header.path("subtitle")), textOf(header.path("secondSubtitle"))].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
        let thumbnail = header == nil ? nil
            : lastThumbnailUrl(header.path("thumbnail"))
            ?? header.findAll("musicThumbnailRenderer").lazy.compactMap { $0.path("thumbnail", "thumbnails").items.last.path("url").str }.first
        let editable = !root.findAll(editablePlaylistHeader).isEmpty
        let songArea: JSON? = appended ?? shelf
        let endConfirmed = continuation == nil && songArea != nil && !containsKeyLike(songArea, "continuation")
        let sortable = !root.findAll("sortFilterSubMenuRenderer").isEmpty
        return PlaylistPage(title: title, songs: rows.map(\.song), continuation: continuation, setVideoIds: rows.map(\.setId), editable: editable,
                            endConfirmed: endConfirmed, subtitle: (subtitle?.isEmpty ?? true) ? nil : subtitle, thumbnailUrl: thumbnail, sortable: sortable)
    }

    private static func containsKeyLike(_ root: JSON?, _ fragment: String) -> Bool {
        switch root {
        case .object(let o)?:
            return o.pairs.contains { key, value in
                (!continuationContainers.contains(key) && key.range(of: fragment, options: .caseInsensitive) != nil) || containsKeyLike(value, fragment)
            }
        case .array(let a)?:
            return a.contains { containsKeyLike($0, fragment) }
        default:
            return false
        }
    }

    /// チャンネルの「投稿」タブの投稿（新しい順。Android と同じ）。
    public static func artistPosts(_ root: JSON) -> [ArtistPost] {
        root.findAll("backstagePostRenderer").compactMap { post -> ArtistPost? in
            guard let id = post.path("postId").str, let text = runsText(post.path("contentText", "runs").items) else { return nil }
            var image = (post.path("backstageAttachment", "backstageImageRenderer", "image", "thumbnails").items.last
                ?? post.path("backstageAttachment", "postMultiImageRenderer", "images").items.first
                .path("backstageImageRenderer", "image", "thumbnails").items.last)
                .path("url").str
            if let i = image, i.hasPrefix("//") { image = "https:" + i }
            return ArtistPost(postId: id, text: text, publishedText: textOf(post.path("publishedTimeText")), imageUrl: image)
        }.distinct { $0.postId }
    }

    public static func editPlaylistStatus(_ root: JSON) -> String? { root.path("status").str }

    public static func createdPlaylistId(_ root: JSON) -> String? { root.path("playlistId").str }

    /// next の最初の応答から、その曲の評価と歌詞のタブの browseId。
    public static func songInfo(_ root: JSON, videoId: String) -> SongInfo {
        var like: LikeStatus?
        let button = root.path("playerOverlays", "playerOverlayRenderer", "actions").items.lazy.compactMap { $0.path("likeButtonRenderer").asObject }.first
        if let button, button.path("target", "videoId").str == videoId {
            switch button.path("likeStatus").str {
            case "LIKE": like = .like
            case "DISLIKE": like = .dislike
            case "INDIFFERENT": like = .indifferent
            default: like = nil
            }
        }
        let lyricsTab: JSON? = watchTabs(root).items.lazy.compactMap { $0.path("tabRenderer", "endpoint", "browseEndpoint") }
            .first { pageTypeOfBrowse($0) == "MUSIC_PAGE_TYPE_TRACK_LYRICS" }
        let lyrics = lyricsTab.path("browseId").str
        return SongInfo(like: like, lyricsBrowseId: lyrics, video: musicVideoOf(root, videoId: videoId))
    }

    /// 曲に重ねる映像（Android の musicVideoInfo と同じ）。公式の MV・投稿の動画はその曲自身、音源（ATV）は対応する MV（時刻の対応表があるときだけ）。
    public static func musicVideoOf(_ root: JSON, videoId: String) -> MusicVideo? {
        let panel = watchTabs(root).firstItem.path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
        for item in panel.path("contents").items {
            let wrapper = item.path("playlistPanelVideoWrapperRenderer")
            let renderer = item.path("playlistPanelVideoRenderer") ?? wrapper.path("primaryRenderer", "playlistPanelVideoRenderer")
            if renderer.path("videoId").str != videoId { continue }
            guard let type = renderer.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
            else { return nil }
            if type != "MUSIC_VIDEO_TYPE_ATV" { return MusicVideo(videoId: videoId, segments: []) }
            let counterpart = wrapper.path("counterpart").firstItem
            let counterpartId = counterpart.path("counterpartRenderer", "playlistPanelVideoRenderer", "videoId").str
            let segments = counterpart.path("segmentMap", "segment").items.compactMap { s -> TimeSegment? in
                guard let p = parseLong(s.path("primaryVideoStartTimeMilliseconds").str),
                    let c = parseLong(s.path("counterpartVideoStartTimeMilliseconds").str),
                    let d = parseLong(s.path("durationMilliseconds").str)
                else { return nil }
                return TimeSegment(primaryStartMs: p, counterpartStartMs: c, durationMs: d)
            }
            // 対応表が無い MV は時刻を合わせられないので使わない
            guard let counterpartId, !segments.isEmpty else { return nil }
            return MusicVideo(videoId: counterpartId, segments: segments)
        }
        return nil
    }

    /// 歌詞のページ（時刻なし）の本文と出典。
    public static func plainLyrics(_ root: JSON) -> SongLyrics.Plain? {
        let shelf = root.path("contents", "sectionListRenderer", "contents").items.lazy.compactMap { $0.path("musicDescriptionShelfRenderer").asObject }.first
        guard let text = textOf(shelf.path("description")) else { return nil }
        return SongLyrics.Plain(text: text, source: textOf(shelf.path("footer")))
    }

    /// アルバムのページ（browse MPREb_…）。曲の行に無いアーティスト・アルバム・画像はヘッダの値で埋める。
    public static func albumPage(_ root: JSON, browseId: String) -> AlbumPage? {
        guard let header = root.findAll("musicResponsiveHeaderRenderer").first, let title = textOf(header.path("title")) else { return nil }
        let artists = artistsOf(header.path("straplineTextOne", "runs").items)
        let thumbnail = lastThumbnailUrl(header.path("thumbnail"))
        let playlistId = header.path("buttons").items.lazy
            .compactMap { $0.path("musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId").str }.first
            ?? canonicalList(root.path("microformat", "microformatDataRenderer", "urlCanonical").str)
            ?? root.findAll("watchPlaylistEndpoint").lazy.compactMap { $0.path("playlistId").str }.first { $0.hasPrefix(albumPlaylistPrefix) }
        let album = AlbumRef(title: title, browseId: browseId)
        let shelf = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").items.lazy
            .compactMap { $0.path("musicShelfRenderer").asObject }.first
        let songs = shelf.path("contents").items.compactMap { row -> Song? in
            guard let item = row.path("musicResponsiveListItemRenderer").asObject, var song = responsiveListItemSong(item) else { return nil }
            let rowRuns = item.path("flexColumns").items.dropFirst().flatMap { $0.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").items }
            let typed = typedArtistsOf(rowRuns)
            song.artists = typed.isEmpty ? artists : typed
            song.album = song.album ?? album
            song.thumbnailUrl = song.thumbnailUrl ?? thumbnail
            return song
        }
        return AlbumPage(browseId: browseId, title: title, subtitle: textOf(header.path("subtitle")), artists: artists, thumbnailUrl: thumbnail,
                         playlistId: playlistId, songs: songs)
    }

    private static func canonicalList(_ url: String?) -> String? {
        guard let url, let at = url.range(of: "list=") else { return nil }
        let id = String(url[at.upperBound...].split(separator: "&", omittingEmptySubsequences: false).first ?? "")
        return id.hasPrefix(albumPlaylistPrefix) ? id : nil
    }

    /// アーティストのページ（browse UC…）。
    public static func artistPage(_ root: JSON) -> ArtistPage? {
        guard let header = root.path("header", "musicImmersiveHeaderRenderer").asObject ?? root.path("header", "musicVisualHeaderRenderer").asObject,
            let name = textOf(header.path("title"))
        else { return nil }
        let thumbnail = lastThumbnailUrl(header.path("thumbnail"))
        let sections = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs").firstItem
            .path("tabRenderer", "content", "sectionListRenderer", "contents").items
        let songShelf = sections.lazy.compactMap { $0.path("musicShelfRenderer").asObject }.first
        let allSongs = songShelf.path("title", "runs").firstItem.path("navigationEndpoint", "browseEndpoint", "browseId").str
            ?? songShelf.path("bottomEndpoint", "browseEndpoint", "browseId").str
        let albumShelves = sections.compactMap { section -> AlbumShelf? in
            guard let carousel = section.path("musicCarouselShelfRenderer").asObject else { return nil }
            let albums = carousel.path("contents").items.compactMap { card -> AlbumSummary? in
                let item = card.path("musicTwoRowItemRenderer").asObject
                let browse = item.path("navigationEndpoint", "browseEndpoint")
                guard let item, pageTypeOfBrowse(browse) == "MUSIC_PAGE_TYPE_ALBUM" else { return nil }
                guard let id = browse.path("browseId").str, let t = textOf(item.path("title")) else { return nil }
                return AlbumSummary(browseId: id, title: t, subtitle: textOf(item.path("subtitle")), thumbnailUrl: lastThumbnailUrl(item.path("thumbnailRenderer")))
            }
            return albums.isEmpty ? nil
                : AlbumShelf(title: textOf(carousel.path("header", "musicCarouselShelfBasicHeaderRenderer", "title")) ?? "", albums: albums)
        }
        let subscribe = header.path("subscriptionButton", "subscribeButtonRenderer")
        let subscribed = subscribe.path("subscribed").bool
        // 動画の棚: カードが曲の再生（watchEndpoint）になっている棚
        let videos = sections.flatMap { $0.path("musicCarouselShelfRenderer", "contents").items }.compactMap { card -> Song? in
            let item = card.path("musicTwoRowItemRenderer")
            let watch = item.path("navigationEndpoint", "watchEndpoint")
            guard let videoId = watch.path("videoId").str, let title = textOf(item.path("title")) else { return nil }
            return Song(videoId: videoId, title: title, artists: [ArtistRef(name: name, browseId: nil)], album: nil, durationSeconds: nil,
                        thumbnailUrl: lastThumbnailUrl(item.path("thumbnailRenderer")),
                        kind: watch.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str)
        }
        return ArtistPage(name: name, thumbnailUrl: thumbnail, topSongs: shelfSongs(songShelf),
                          allSongsPlaylistId: allSongs.flatMap { $0.hasPrefix("VL") ? String($0.dropFirst(2)) : nil }, albumShelves: albumShelves,
                          channelId: subscribe.path("channelId").str, subscribed: subscribed, videos: videos)
    }

    /// ログインしていないときに返る「ログインしてください」の案内だけの応答か。
    public static func isSignInPrompt(_ root: JSON) -> Bool {
        !root.findAll("messageRenderer").isEmpty && contentRenderers.allSatisfy { root.findAll($0).isEmpty }
    }

    private static func textOf(_ node: JSON?) -> String? { runsText(node.path("runs").items) }

    private static func runsText<S: Sequence>(_ runs: S) -> String? where S.Element == JSON {
        let text = runs.map { $0.path("text").str ?? "" }.joined()
        return text.allSatisfy(\.isWhitespace) ? nil : text
    }

    private static func lastThumbnailUrl(_ node: JSON?) -> String? {
        node.path("musicThumbnailRenderer", "thumbnail", "thumbnails").items.last.path("url").str
    }

    private static func shelfSongs(_ shelf: JSON?) -> [Song] {
        shelf.path("contents").items.compactMap { $0.path("musicResponsiveListItemRenderer").asObject }.compactMap(responsiveListItemSong)
    }

    private static func durationOf<S: Sequence>(_ runs: S) -> Int? where S.Element == JSON {
        runs.lazy.compactMap { r in r.path("text").str.flatMap(parseDuration) }.first
    }

    /// 検索・プレイリストの行（musicResponsiveListItemRenderer）。
    public static func responsiveListItemSong(_ item: JSON) -> Song? {
        // 地域・権利の都合で再生できない行（グレーアウト）は除く
        if item.path("musicItemRendererDisplayPolicy").str == greyOut { return nil }
        let columns = flexColumnRuns(item)
        let titleRuns = columns.first ?? []
        let videoId = item.path("playlistItemData", "videoId").str
            ?? titleRuns.lazy.compactMap { $0.path("navigationEndpoint", "watchEndpoint", "videoId").str }.first
            ?? item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").str
        guard let videoId, let title = runsText(titleRuns) else { return nil }
        let detailRuns = Array(columns.dropFirst().joined())
        let fixedDuration = durationOf(item.path("fixedColumns").items.flatMap { $0.path("musicResponsiveListItemFixedColumnRenderer", "text", "runs").items })
        let kind = titleRuns.lazy.compactMap {
            $0.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
        }.first
            ?? item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint",
                         "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str
        return Song(videoId: videoId, title: title, artists: artistsOf(detailRuns), album: albumOf(detailRuns), durationSeconds: fixedDuration ?? durationOf(detailRuns),
                    thumbnailUrl: lastThumbnailUrl(item.path("thumbnail")), kind: kind)
    }

    /// ラジオ・キューの行（playlistPanelVideoRenderer）。
    public static func playlistPanelSong(_ item: JSON) -> Song? {
        guard let videoId = item.path("videoId").str ?? item.path("navigationEndpoint", "watchEndpoint", "videoId").str,
            let title = textOf(item.path("title"))
        else { return nil }
        var byline = item.path("longBylineText", "runs").items
        if byline.isEmpty { byline = item.path("shortBylineText", "runs").items }
        return Song(videoId: videoId, title: title, artists: artistsOf(byline), album: albumOf(byline), durationSeconds: durationOf(item.path("lengthText", "runs").items),
                    thumbnailUrl: item.path("thumbnail", "thumbnails").items.last.path("url").str,
                    kind: item.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str)
    }

    /// アーティストの runs。ページ種別が付いていればそれを、無ければ区切り（" • "）の前をアーティストとみなす。
    private static func artistsOf(_ runs: [JSON]) -> [ArtistRef] {
        let typed = typedArtistsOf(runs)
        if !typed.isEmpty { return typed }
        return runs.prefix { $0.path("text").str != separator }
            .compactMap { $0.path("text").str }
            .filter { t in !t.allSatisfy(\.isWhitespace) && t != ", " && t != " & " && parseDuration(t) == nil }
            .map { ArtistRef(name: $0, browseId: nil) }
    }

    private static func typedArtistsOf(_ runs: [JSON]) -> [ArtistRef] {
        runs.filter { pageTypeOf($0).map(artistPageTypes.contains) ?? false }.compactMap { run -> ArtistRef? in
            // チャンネル（USER_CHANNEL）はアーティストのページとして開けないので、名前だけにする
            let browseId = pageTypeOf(run) == artistPageType ? run.path("navigationEndpoint", "browseEndpoint", "browseId").str : nil
            return run.path("text").str.map { ArtistRef(name: $0, browseId: browseId) }
        }
    }

    private static func albumOf(_ runs: [JSON]) -> AlbumRef? {
        guard let run = runs.first(where: { pageTypeOf($0) == "MUSIC_PAGE_TYPE_ALBUM" }), let text = run.path("text").str else { return nil }
        return AlbumRef(title: text, browseId: run.path("navigationEndpoint", "browseEndpoint", "browseId").str)
    }

    private static func pageTypeOf(_ run: JSON) -> String? { pageTypeOfBrowse(run.path("navigationEndpoint", "browseEndpoint")) }

    private static func pageTypeOfBrowse(_ browse: JSON?) -> String? {
        browse.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
    }

    private static func nextContinuation(_ shelf: JSON) -> String? {
        shelf.path("continuations").items.lazy.compactMap { $0.path("nextContinuationData", "continuation").str }.first
            ?? shelf.path("contents").items.last.path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
    }

    private static func removeVl(_ id: String) -> String { id.hasPrefix("VL") ? String(id.dropFirst(2)) : id }

    private static let durationPattern = try! NSRegularExpression(pattern: #"^\d{1,2}(:\d{2}){1,2}$"#)

    /// "4:22" や "1:02:03" を秒に。それ以外は nil。
    public static func parseDuration(_ text: String) -> Int? {
        guard durationPattern.matches(text) else { return nil }
        var total = 0
        for part in text.split(separator: ":", omittingEmptySubsequences: false) {
            // .NET の int.Parse と同じく前後の空白は許す（$ が末尾の改行の前にも当たるため）。数字に読めなければ nil
            guard let n = Int(part.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            total = total * 60 + n
        }
        return total
    }

    /// long.TryParse と同じく、前後の空白を許して整数に読む。
    private static func parseLong(_ s: String?) -> Int64? {
        s.flatMap { Int64($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
}

extension JSON {
    /// オブジェクトのときだけ自分（Windows の Obj()）。
    fileprivate var asObject: JSON? { if case .object = self { return self } else { return nil } }
}

extension Optional where Wrapped == JSON {
    fileprivate var asObject: JSON? { self?.asObject }
}

extension Array {
    /// 最初に出てきたものだけを残す（DistinctBy）。
    fileprivate func distinct<K: Hashable>(_ key: (Element) -> K) -> [Element] {
        var seen = Set<K>()
        return filter { seen.insert(key($0)).inserted }
    }
}
