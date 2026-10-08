package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.mooma0750.wave.core.lyrics.Lyrics

/**
 * InnerTube の応答（JSON）からドメインモデルへの純粋な変換。
 * 形が変わっても例外で落とさず、取れない項目は飛ばす。取れた件数が0かどうかは呼び出し側が判断する。
 */
internal object MusicParsers {
    /** 検索（曲の棚）の最初のページと続きのページ。 */
    fun searchSongs(root: JsonObject): SongPage {
        val shelf =
            root.path("continuationContents", "musicShelfContinuation").asObject()
                ?: root
                    .path("contents", "tabbedSearchResultsRenderer", "tabs")
                    .firstArrayItem()
                    .path("tabRenderer", "content", "sectionListRenderer", "contents")
                    .arrayItems()
                    .firstNotNullOfOrNull { it.path("musicShelfRenderer").asObject() }
                ?: return SongPage(emptyList(), null)
        val songs = shelfSongs(shelf)
        return SongPage(songs, nextContinuation(shelf))
    }

    /** アルバムに絞った検索の最初のページと続きのページ。 */
    fun searchAlbums(root: JsonObject): SearchPage<AlbumSummary> = searchPage(root, ::albumRow)

    /** アーティストに絞った検索の最初のページと続きのページ。 */
    fun searchArtists(root: JsonObject): SearchPage<ArtistSummary> = searchPage(root, ::artistRow)

    private fun <T> searchPage(
        root: JsonObject,
        row: (JsonObject) -> T?,
    ): SearchPage<T> {
        val shelf = searchShelf(root) ?: return SearchPage(emptyList(), null)
        val items = shelf.path("contents").arrayItems().mapNotNull { it.path("musicResponsiveListItemRenderer").asObject()?.let(row) }
        return SearchPage(items, nextContinuation(shelf))
    }

    private fun searchShelf(root: JsonObject): JsonObject? =
        root.path("continuationContents", "musicShelfContinuation").asObject()
            ?: root
                .path("contents", "tabbedSearchResultsRenderer", "tabs")
                .firstArrayItem()
                .path("tabRenderer", "content", "sectionListRenderer", "contents")
                .arrayItems()
                .firstNotNullOfOrNull { it.path("musicShelfRenderer").asObject() }

    /**
     * 打ち込み中の候補（music/get_search_suggestions）。1つめの区切りに語の候補、2つめに曲・アルバム・アーティストの行が来る。
     * 行の種類はページ種別と videoId で分ける（見出しや「曲」などの文字は言語に依るので使わない）。
     */
    fun searchSuggestions(root: JsonObject): SearchSuggestions {
        val contents = root.path("contents").arrayItems().flatMap { it.path("searchSuggestionsSectionRenderer", "contents").arrayItems() }
        val queries =
            contents.mapNotNull { item ->
                val suggestion = item.path("searchSuggestionRenderer").asObject() ?: item.path("historySuggestionRenderer").asObject() ?: return@mapNotNull null
                suggestion.path("navigationEndpoint", "searchEndpoint", "query").string() ?: textOf(suggestion.path("suggestion"))
            }
        val rows = contents.mapNotNull { it.path("musicResponsiveListItemRenderer").asObject() }
        return SearchSuggestions(
            queries = queries.distinct(),
            songs = rows.filter { pageTypeOfBrowse(it.path("navigationEndpoint", "browseEndpoint")) == null }.mapNotNull(::responsiveListItemSong),
            albums = rows.mapNotNull(::albumRow),
            artists = rows.mapNotNull(::artistRow),
        )
    }

    private fun albumRow(item: JsonObject): AlbumSummary? {
        val browse = item.path("navigationEndpoint", "browseEndpoint")
        if (pageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_ALBUM") return null
        val columns = flexColumnRuns(item)
        return AlbumSummary(
            browseId = browse.path("browseId").string() ?: return null,
            title = runsText(columns.getOrNull(0).orEmpty()) ?: return null,
            subtitle = runsText(columns.drop(1).flatten()),
            thumbnailUrl = lastThumbnailUrl(item.path("thumbnail")),
        )
    }

    private fun artistRow(item: JsonObject): ArtistSummary? {
        val browse = item.path("navigationEndpoint", "browseEndpoint")
        val browseId = browse.path("browseId").string() ?: return null
        val artistId =
            when (pageTypeOfBrowse(browse)) {
                ARTIST_PAGE -> browseId
                // ライブラリのアーティスト（MPLA + チャンネルの ID）。アーティストのページはチャンネルの ID で開く
                LIBRARY_ARTIST_PAGE -> browseId.removePrefix(LIBRARY_ARTIST_PREFIX).takeIf { browseId.startsWith(LIBRARY_ARTIST_PREFIX) } ?: return null
                else -> return null
            }
        val columns = flexColumnRuns(item)
        return ArtistSummary(
            browseId = artistId,
            name = runsText(columns.getOrNull(0).orEmpty()) ?: return null,
            subtitle = runsText(columns.drop(1).flatten()),
            thumbnailUrl = lastThumbnailUrl(item.path("thumbnail")),
        )
    }

    private fun flexColumnRuns(item: JsonObject): List<List<JsonObject>> =
        item.path("flexColumns").arrayItems().map { it.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").arrayItems() }

    /**
     * ホーム（browse FEmusic_home）の最初のページと続きのページ。棚（musicCarouselShelfRenderer）ごとに、読める項目だけを取る。
     * 項目の種類はページ種別と watchEndpoint で分ける。
     */
    fun homePage(root: JsonObject): HomePage {
        val list =
            root.path("continuationContents", "sectionListContinuation").asObject()
                ?: root
                    .path("contents", "singleColumnBrowseResultsRenderer", "tabs")
                    .firstArrayItem()
                    .path("tabRenderer", "content", "sectionListRenderer")
                    .asObject()
                ?: return HomePage(emptyList(), null)
        val shelves =
            list.path("contents").arrayItems().mapNotNull { section ->
                val carousel = section.path("musicCarouselShelfRenderer").asObject() ?: return@mapNotNull null
                val header = carousel.path("header", "musicCarouselShelfBasicHeaderRenderer")
                val items = carousel.path("contents").arrayItems().mapNotNull(::homeItem)
                if (items.isEmpty()) return@mapNotNull null
                HomeShelf(textOf(header.path("title")).orEmpty(), textOf(header.path("strapline")), items)
            }
        val continuation = list.path("continuations").arrayItems().firstNotNullOfOrNull { it.path("nextContinuationData", "continuation").string() }
        val chips =
            findAll(list.path("header"), "chipCloudChipRenderer").mapNotNull { chip ->
                val browse = chip.path("navigationEndpoint", "browseEndpoint")
                if (browse.path("browseId").string() != "FEmusic_home") return@mapNotNull null
                HomeChip(textOf(chip.path("text")) ?: return@mapNotNull null, browse.path("params").string() ?: return@mapNotNull null)
            }
        return HomePage(shelves, continuation, chips)
    }

    /** 気分とジャンル（browse FEmusic_moods_and_genres）。格子ごとに見出しとタイル。 */
    fun moods(root: JsonObject): List<MoodSection> =
        findAll(root, "gridRenderer").mapNotNull { grid ->
            val categories =
                grid.path("items").arrayItems().mapNotNull { item ->
                    val button = item.path("musicNavigationButtonRenderer").asObject() ?: return@mapNotNull null
                    val browse = button.path("clickCommand", "browseEndpoint")
                    MoodCategory(
                        title = textOf(button.path("buttonText")) ?: return@mapNotNull null,
                        params = browse.path("params").string() ?: return@mapNotNull null,
                        color = (button.path("solid", "leftStripeColor") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull(),
                    )
                }
            if (categories.isEmpty()) return@mapNotNull null
            MoodSection(textOf(grid.path("header", "gridHeaderRenderer", "title")).orEmpty(), categories)
        }

    /** 新作のアルバム・シングル（browse FEmusic_new_releases_albums の格子）。 */
    fun newReleases(root: JsonObject): List<AlbumSummary> = gridItems(root).mapNotNull { (it as? HomeItem.AlbumItem)?.album }

    /** ライブラリのアーティスト・アルバムの一覧。格子（カード）と棚（行）のどちらの形でも読む。 */
    fun libraryCards(root: JsonObject): List<HomeItem> =
        gridItems(root) +
            findAll(root, "gridContinuation").flatMap { it.path("items").arrayItems() }.mapNotNull(::homeItem) +
            (findAll(root, "musicShelfRenderer") + findAll(root, "musicShelfContinuation")).flatMap { it.path("contents").arrayItems() }.mapNotNull(::homeItem)

    /** ライブラリの一覧の続きのトークン（格子・棚のどちらでも）。無ければ最後のページ。 */
    fun libraryContinuation(root: JsonObject): String? =
        findAll(root, "nextContinuationData").firstNotNullOfOrNull { it.path("continuation").string() }
            ?: findAll(root, "continuationItemRenderer").firstNotNullOfOrNull { it.path("continuationEndpoint", "continuationCommand", "token").string() }

    /** 格子（gridRenderer）に並ぶカード。ライブラリの「最近のアクティビティ」もこの形。 */
    fun gridItems(root: JsonObject): List<HomeItem> = findAll(root, "gridRenderer").flatMap { it.path("items").arrayItems() }.mapNotNull(::homeItem)

    private fun homeItem(card: JsonObject): HomeItem? {
        card.path("musicResponsiveListItemRenderer").asObject()?.let { row ->
            return albumRow(row)?.let(HomeItem::AlbumItem)
                ?: artistRow(row)?.let(HomeItem::ArtistItem)
                ?: responsiveListItemSong(row)?.let(HomeItem::SongItem)
        }
        val item = card.path("musicTwoRowItemRenderer").asObject() ?: return null
        val title = textOf(item.path("title")) ?: return null
        val subtitleRuns = item.path("subtitle", "runs").arrayItems()
        val subtitle = runsText(subtitleRuns)
        val thumbnail = lastThumbnailUrl(item.path("thumbnailRenderer"))
        val endpoint = item.path("navigationEndpoint")
        endpoint.path("watchEndpoint", "videoId").string()?.let { videoId ->
            return HomeItem.SongItem(Song(videoId, title, artistsOf(subtitleRuns), albumOf(subtitleRuns), null, thumbnail))
        }
        val browse = endpoint.path("browseEndpoint")
        val browseId = browse.path("browseId").string() ?: return null
        return when (pageTypeOfBrowse(browse)) {
            "MUSIC_PAGE_TYPE_ALBUM" -> HomeItem.AlbumItem(AlbumSummary(browseId, title, subtitle, thumbnail))
            ARTIST_PAGE -> HomeItem.ArtistItem(ArtistSummary(browseId, title, subtitle, thumbnail))
            "MUSIC_PAGE_TYPE_PLAYLIST" -> HomeItem.PlaylistItem(PlaylistSummary(browseId.removePrefix("VL"), title, subtitle, thumbnail))
            else -> null
        }
    }

    /**
     * 再生履歴（browse FEmusic_history）。「今日」「今週」のように区切られた棚が並ぶので、棚ごとに見出しと曲を取る。
     * 続きのページは continuationContents に来る。
     */
    fun historyPage(root: JsonObject): HistoryPage {
        val container =
            root.path("continuationContents", "sectionListContinuation").asObject()
                ?: root
                    .path("contents", "singleColumnBrowseResultsRenderer", "tabs")
                    .firstArrayItem()
                    .path("tabRenderer", "content", "sectionListRenderer")
                    .asObject()
                ?: return HistoryPage(emptyList(), null)
        val shelves = findAll(container, "musicShelfRenderer")
        val sections =
            shelves.map { shelf ->
                HistorySection(
                    title = textOf(shelf.path("title")),
                    songs = shelfSongs(shelf),
                )
            }
        val continuation =
            nextContinuation(container)
                ?: shelves.lastOrNull()?.let(::nextContinuation)
        return HistoryPage(sections.filter { it.songs.isNotEmpty() }, continuation)
    }

    /** next（ラジオ）の最初のページと続きのページ。続きの応答は contents も持つので、continuationContents を優先する。 */
    fun upNext(root: JsonObject): UpNextPage {
        val panel =
            root.path("continuationContents", "playlistPanelContinuation").asObject()
                ?: root
                    .path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
                    .firstArrayItem()
                    .path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
                    .asObject()
                ?: return UpNextPage(emptyList(), null, null)
        val songs = panel.path("contents").arrayItems().mapNotNull { it.path("playlistPanelVideoRenderer").asObject()?.let(::playlistPanelSong) }
        val continuation =
            panel.path("continuations").arrayItems().firstNotNullOfOrNull {
                it.path("nextRadioContinuationData", "continuation").string() ?: it.path("nextContinuationData", "continuation").string()
            }
        return UpNextPage(songs, continuation, panel.path("playlistId").string())
    }

    /** ライブラリのプレイリスト一覧などに並ぶ、プレイリストのカード（musicTwoRowItemRenderer）をすべて集める。 */
    fun playlists(root: JsonObject): List<PlaylistSummary> =
        findAll(root, "musicTwoRowItemRenderer").mapNotNull { item ->
            val browse = item.path("navigationEndpoint", "browseEndpoint")
            val pageType = pageTypeOfBrowse(browse)
            val browseId = browse.path("browseId").string() ?: return@mapNotNull null
            if (pageType != "MUSIC_PAGE_TYPE_PLAYLIST" && !browseId.startsWith("VL")) return@mapNotNull null
            val title = textOf(item.path("title")) ?: return@mapNotNull null
            PlaylistSummary(
                playlistId = browseId.removePrefix("VL"),
                title = title,
                subtitle = textOf(item.path("subtitle")),
                thumbnailUrl = lastThumbnailUrl(item.path("thumbnailRenderer")),
                // 実アカウントのライブラリで、自分のプレイリストのカードだけがメニューに EDIT を持つことを確かめた（2026-09-17、アイコン名だけ）
                editable = EDIT_ICON in menuIconsOf(item),
            )
        }.distinctBy { it.playlistId }

    private fun menuIconsOf(card: JsonObject): Set<String> {
        val icons = mutableSetOf<String>()
        fun walk(element: JsonElement?) {
            when (element) {
                is JsonObject -> element.forEach { (name, value) -> if (name == "iconType") value.string()?.let { icons += it } else walk(value) }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(card.path("menu"))
        return icons
    }

    /** プレイリストの最初のページ（browse VL<id>）と続きのページ（appendContinuationItemsAction）。 */
    fun playlistPage(root: JsonObject): PlaylistPage {
        val appended =
            root.path("onResponseReceivedActions").arrayItems().firstNotNullOfOrNull {
                it.path("appendContinuationItemsAction", "continuationItems") as? JsonArray
            }
        val shelf = findAll(root, "musicPlaylistShelfRenderer").firstOrNull()
        val items: List<JsonObject> =
            appended?.arrayItems()
                ?: shelf.path("contents").arrayItems()
        val rows =
            items.mapNotNull { item ->
                val row = item.path("musicResponsiveListItemRenderer").asObject() ?: return@mapNotNull null
                responsiveListItemSong(row)?.let { it to row.path("playlistItemData", "playlistSetVideoId").string() }
            }
        val continuation =
            items.lastOrNull().path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").string()
                ?: shelf.path("continuations").arrayItems().firstNotNullOfOrNull {
                    it.path("nextContinuationData", "continuation").string()
                }
        val title =
            textOf(findAll(root, "musicResponsiveHeaderRenderer").firstOrNull().path("title"))
                ?: textOf(findAll(root, "musicDetailHeaderRenderer").firstOrNull().path("title"))
        // 自分のプレイリストは編集用のヘッダを持つ（実アカウントの応答でレンダラ名だけを確かめる、決定 E7）
        val editable = findAll(root, EDITABLE_PLAYLIST_HEADER).isNotEmpty()
        // 最後のページと言えるのは、知っている形（続きの追加 or 曲の棚）が見つかり、その中に名前に continuation を含むキーが無いときだけ
        // （知らない形の続きがあるのに読み取れなかった場合を、最後のページと誤認しない）。
        // 自分のプレイリストでは、曲の棚の外（sectionListRenderer.continuations）に「おすすめ」の欄の続きがある。曲の続きではないので見ない
        // （実アカウントでキー名の場所だけ確認、空のプレイリストにも付いている、2026-09-17）
        val songArea: JsonElement? = appended ?: shelf
        val endConfirmed = continuation == null && songArea != null && !containsKeyLike(songArea, "continuation")
        val sortable = findAll(root, "sortFilterSubMenuRenderer").isNotEmpty()
        return PlaylistPage(title, rows.map { it.first }, continuation, rows.map { it.second }, editable, endConfirmed, sortable)
    }

    private fun containsKeyLike(
        root: JsonElement?,
        fragment: String,
    ): Boolean =
        when (root) {
            // 続きのページを包むだけのキー（続きがあることを意味しない）は数えない
            is JsonObject -> root.any { (name, value) -> (name !in CONTINUATION_CONTAINERS && name.contains(fragment, ignoreCase = true)) || containsKeyLike(value, fragment) }
            is JsonArray -> root.any { containsKeyLike(it, fragment) }
            else -> false
        }

    /** browse/edit_playlist の応答の status（`STATUS_SUCCEEDED` など）。 */
    fun editPlaylistStatus(root: JsonObject): String? = root.path("status").string()

    /**
     * next の最初の応答から、videoId の曲の種類と見せる映像。ログイン中の応答では、音源の行が
     * playlistPanelVideoWrapperRenderer（primaryRenderer と counterpart・segmentMap）に包まれて、対応する MV が付く。
     * 曲が一覧に無ければ null。
     */
    fun musicVideoInfo(
        root: JsonObject,
        videoId: String,
    ): MusicVideoInfo? {
        val panel =
            root
                .path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
                .firstArrayItem()
                .path("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
        for (item in panel.path("contents").arrayItems()) {
            val wrapper = item.path("playlistPanelVideoWrapperRenderer")
            val renderer = item.path("playlistPanelVideoRenderer") ?: wrapper.path("primaryRenderer", "playlistPanelVideoRenderer")
            if (renderer.path("videoId").string() != videoId) continue
            val type = musicVideoTypeOf(renderer)
            if (type != MusicVideoType.Atv) return MusicVideoInfo(type, MusicVideo(videoId, emptyList()))
            val counterpart = wrapper.path("counterpart").firstArrayItem()
            val counterpartId = counterpart.path("counterpartRenderer", "playlistPanelVideoRenderer", "videoId").string()
            val segments =
                counterpart.path("segmentMap", "segment").arrayItems().mapNotNull {
                    TimeSegment(
                        primaryStartMs = it.path("primaryVideoStartTimeMilliseconds").string()?.toLongOrNull() ?: return@mapNotNull null,
                        counterpartStartMs = it.path("counterpartVideoStartTimeMilliseconds").string()?.toLongOrNull() ?: return@mapNotNull null,
                        durationMs = it.path("durationMilliseconds").string()?.toLongOrNull() ?: return@mapNotNull null,
                    )
                }
            // 対応表が無い MV は時刻を合わせられないので使わない
            return MusicVideoInfo(type, counterpartId?.takeIf { segments.isNotEmpty() }?.let { MusicVideo(it, segments) })
        }
        return null
    }

    private fun musicVideoTypeOf(renderer: JsonElement?): MusicVideoType? =
        MusicVideoType.of(
            renderer.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").string(),
        )

    /** next の最初の応答の、歌詞のタブの browseId（`MPLY…`）。歌詞の無い曲はタブに行き先が無いので null。 */
    fun lyricsBrowseId(root: JsonObject): String? =
        root
            .path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
            .arrayItems()
            .map { it.path("tabRenderer", "endpoint", "browseEndpoint") }
            .firstOrNull { pageTypeOfBrowse(it) == "MUSIC_PAGE_TYPE_TRACK_LYRICS" }
            .path("browseId")
            .string()

    /** 歌詞のページ（時刻なし）の本文と出典（「Source: Musixmatch」など）。本文が無ければ null。 */
    fun plainLyrics(root: JsonObject): Lyrics.Plain? {
        val shelf =
            root
                .path("contents", "sectionListRenderer", "contents")
                .arrayItems()
                .firstNotNullOfOrNull { it.path("musicDescriptionShelfRenderer").asObject() }
                ?: return null
        val text = textOf(shelf.path("description")) ?: return null
        return Lyrics.Plain(text, textOf(shelf.path("footer")))
    }

    /** playlist/create の応答の playlistId。 */
    fun createdPlaylistId(root: JsonObject): String? = root.path("playlistId").string()

    /**
     * next の最初の応答にある、指定した曲の評価（`playerOverlays…likeButtonRenderer`）。続きの応答には無い。
     * 別の曲の評価や、知らない値なら null。
     */
    fun likeStatus(
        root: JsonObject,
        videoId: String,
    ): LikeStatus? {
        val button =
            root.path("playerOverlays", "playerOverlayRenderer", "actions").arrayItems().firstNotNullOfOrNull { it.path("likeButtonRenderer").asObject() }
                ?: return null
        if (button.path("target", "videoId").string() != videoId) return null
        return when (button.path("likeStatus").string()) {
            "LIKE" -> LikeStatus.Like
            "DISLIKE" -> LikeStatus.Dislike
            "INDIFFERENT" -> LikeStatus.Indifferent
            else -> null
        }
    }

    /**
     * アルバムのページ（browse `MPREb_…`）。曲の行にはアーティスト・アルバム・サムネイルが無いので、ヘッダの値で埋める。
     * 取れなければ null。
     */
    fun albumPage(
        root: JsonObject,
        browseId: String,
    ): AlbumPage? {
        val header = findAll(root, "musicResponsiveHeaderRenderer").firstOrNull() ?: return null
        val title = textOf(header.path("title")) ?: return null
        val artists = artistsOf(header.path("straplineTextOne", "runs").arrayItems())
        val thumbnail = lastThumbnailUrl(header.path("thumbnail"))
        // 匿名の応答はヘッダの再生ボタン。無ければ canonical URL の list=、それも無ければアルバムの playlistId（OLAK5uy_）を探す
        val playlistId =
            header.path("buttons").arrayItems().firstNotNullOfOrNull {
                it.path("musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId").string()
            } ?: root.path("microformat", "microformatDataRenderer", "urlCanonical").string()?.substringAfter("list=", "")?.substringBefore('&')?.takeIf { it.startsWith(ALBUM_PLAYLIST_PREFIX) }
                ?: findAll(root, "watchPlaylistEndpoint").firstNotNullOfOrNull { it.path("playlistId").string()?.takeIf { id -> id.startsWith(ALBUM_PLAYLIST_PREFIX) } }
        val album = AlbumRef(title, browseId)
        val shelf =
            root
                .path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents")
                .arrayItems()
                .firstNotNullOfOrNull { it.path("musicShelfRenderer").asObject() }
        val songs =
            shelf.path("contents").arrayItems().mapNotNull { row ->
                val item = row.path("musicResponsiveListItemRenderer").asObject() ?: return@mapNotNull null
                // 行の2列目以降は再生回数などだけのことが多い。ページ種別付きのアーティストが無ければヘッダのアーティストにする
                val rowRuns = item.path("flexColumns").arrayItems().drop(1).flatMap { it.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").arrayItems() }
                responsiveListItemSong(item)?.let { song ->
                    song.copy(
                        artists = typedArtistsOf(rowRuns).ifEmpty { artists },
                        album = song.album ?: album,
                        thumbnailUrl = song.thumbnailUrl ?: thumbnail,
                    )
                }
            }
        return AlbumPage(browseId, title, textOf(header.path("subtitle")), artists, thumbnail, playlistId, songs)
    }

    /** アーティストのページ（browse `UC…`）。取れなければ null。 */
    fun artistPage(root: JsonObject): ArtistPage? {
        val header = root.path("header", "musicImmersiveHeaderRenderer").asObject() ?: root.path("header", "musicVisualHeaderRenderer").asObject() ?: return null
        val name = textOf(header.path("title")) ?: return null
        val thumbnail = lastThumbnailUrl(header.path("thumbnail"))
        val sections =
            root
                .path("contents", "singleColumnBrowseResultsRenderer", "tabs")
                .firstArrayItem()
                .path("tabRenderer", "content", "sectionListRenderer", "contents")
                .arrayItems()
        val songShelf = sections.firstNotNullOfOrNull { it.path("musicShelfRenderer").asObject() }
        val topSongs = shelfSongs(songShelf)
        val allSongs =
            (
                songShelf.path("title", "runs").firstArrayItem().path("navigationEndpoint", "browseEndpoint", "browseId").string()
                    ?: songShelf.path("bottomEndpoint", "browseEndpoint", "browseId").string()
            )?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
        val albumShelves =
            sections.mapNotNull { section ->
                val carousel = section.path("musicCarouselShelfRenderer").asObject() ?: return@mapNotNull null
                val albums =
                    carousel.path("contents").arrayItems().mapNotNull { card ->
                        val item = card.path("musicTwoRowItemRenderer").asObject() ?: return@mapNotNull null
                        val browse = item.path("navigationEndpoint", "browseEndpoint")
                        if (pageTypeOfBrowse(browse) != "MUSIC_PAGE_TYPE_ALBUM") return@mapNotNull null
                        AlbumSummary(
                            browseId = browse.path("browseId").string() ?: return@mapNotNull null,
                            title = textOf(item.path("title")) ?: return@mapNotNull null,
                            subtitle = textOf(item.path("subtitle")),
                            thumbnailUrl = lastThumbnailUrl(item.path("thumbnailRenderer")),
                        )
                    }
                if (albums.isEmpty()) return@mapNotNull null
                AlbumShelf(textOf(carousel.path("header", "musicCarouselShelfBasicHeaderRenderer", "title")).orEmpty(), albums)
            }
        val subscribe = header.path("subscriptionButton", "subscribeButtonRenderer")
        // 動画の棚: カードが曲の再生（watchEndpoint）になっている棚
        val videos =
            sections.flatMap { section ->
                section.path("musicCarouselShelfRenderer", "contents").arrayItems().mapNotNull { card ->
                    val item = card.path("musicTwoRowItemRenderer").asObject() ?: return@mapNotNull null
                    val watch = item.path("navigationEndpoint", "watchEndpoint")
                    val videoId = watch.path("videoId").string() ?: return@mapNotNull null
                    Song(
                        videoId = videoId,
                        title = textOf(item.path("title")) ?: return@mapNotNull null,
                        artists = listOf(ArtistRef(name, null)),
                        album = null,
                        durationSeconds = null,
                        thumbnailUrl = lastThumbnailUrl(item.path("thumbnailRenderer")),
                        kind = MusicVideoType.of(watch.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").string()),
                    )
                }
            }
        return ArtistPage(
            name,
            thumbnail,
            topSongs,
            allSongs,
            albumShelves,
            channelId = subscribe.path("channelId").string(),
            subscribed = subscribe.path("subscribed").asBoolean(),
            videos = videos,
        )
    }

    private fun textOf(element: JsonElement?): String? = runsText(element.path("runs").arrayItems())

    /** runs の文字をつなぐ。空なら null。 */
    private fun runsText(runs: List<JsonObject>): String? = runs.joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() }

    /** musicThumbnailRenderer の、いちばん大きい（最後の）サムネイルの URL。 */
    private fun lastThumbnailUrl(element: JsonElement?): String? = element.path("musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string()

    /** 棚の中の曲の行。 */
    private fun shelfSongs(shelf: JsonElement?): List<Song> = shelf.path("contents").arrayItems().mapNotNull { it.path("musicResponsiveListItemRenderer").asObject()?.let(::responsiveListItemSong) }

    /** runs の中で最初に「分:秒」と読める文字の長さ（秒）。 */
    private fun durationOf(runs: List<JsonObject>): Int? = runs.firstNotNullOfOrNull { it.path("text").string()?.let(::parseDuration) }

    /** ログインしていないときに返る「ログインしてください」の案内（200 の messageRenderer）だけの応答か。 */
    fun isSignInPrompt(root: JsonObject): Boolean =
        findAll(root, "messageRenderer").isNotEmpty() &&
            // 空のプレイリストも「曲がありません」の messageRenderer を持つ。ヘッダや曲の棚があればログインの案内ではない
            CONTENT_RENDERERS.none { findAll(root, it).isNotEmpty() }

    /** key が現れる場所を、キー名の並び（配列は [] ）だけで集める。値は含めない（決定 E7）。 */
    fun keyPaths(
        root: JsonElement,
        key: String,
    ): Set<String> {
        val paths = mutableSetOf<String>()
        fun walk(
            element: JsonElement,
            path: String,
        ) {
            when (element) {
                is JsonObject ->
                    element.forEach { (name, value) ->
                        if (name == key) paths += path
                        walk(value, if (path.isEmpty()) name else "$path.$name")
                    }
                is JsonArray -> element.forEach { walk(it, "$path[]") }
                else -> Unit
            }
        }
        walk(root, "")
        return paths
    }

    /** 応答のレンダラ名だけを集める（値は含めない）。解析が0件だったときの原因調査用（決定 E7）。 */
    fun rendererNames(root: JsonElement): Set<String> {
        val names = mutableSetOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject ->
                    element.forEach { (key, value) ->
                        if (key.endsWith("Renderer") || key.endsWith("Action") || key.endsWith("Continuation")) names += key
                        walk(value)
                    }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return names
    }

    private fun findAll(
        root: JsonElement?,
        key: String,
    ): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        fun walk(element: JsonElement?) {
            when (element) {
                is JsonObject ->
                    element.forEach { (name, value) ->
                        if (name == key && value is JsonObject) found += value
                        walk(value)
                    }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return found
    }

    /** 検索・プレイリストの行（musicResponsiveListItemRenderer）。 */
    fun responsiveListItemSong(item: JsonObject): Song? {
        // 地域・権利の都合で再生できない行（グレーアウト）は、キューに入れても飛ばされるだけなので除く
        if (item.path("musicItemRendererDisplayPolicy").string() == GREY_OUT) return null
        val columns = item.path("flexColumns").arrayItems().map { it.path("musicResponsiveListItemFlexColumnRenderer", "text", "runs").arrayItems() }
        val titleRuns = columns.getOrNull(0).orEmpty()
        val videoId =
            item.path("playlistItemData", "videoId").string()
                ?: titleRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").string() }
                ?: item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").string()
                ?: return null
        val title = runsText(titleRuns) ?: return null
        val detailRuns = columns.drop(1).flatten()
        val fixedDuration =
            item
                .path("fixedColumns")
                .arrayItems()
                .flatMap { it.path("musicResponsiveListItemFixedColumnRenderer", "text", "runs").arrayItems() }
                .let(::durationOf)
        return Song(
            videoId = videoId,
            title = title,
            artists = artistsOf(detailRuns),
            album = albumOf(detailRuns),
            durationSeconds = fixedDuration ?: durationOf(detailRuns),
            thumbnailUrl = lastThumbnailUrl(item.path("thumbnail")),
            kind =
                MusicVideoType.of(
                    titleRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").string() }
                        ?: item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").string(),
                ),
        )
    }

    /** ラジオ・キューの行（playlistPanelVideoRenderer）。 */
    fun playlistPanelSong(item: JsonObject): Song? {
        val videoId = item.path("videoId").string() ?: item.path("navigationEndpoint", "watchEndpoint", "videoId").string() ?: return null
        val title = textOf(item.path("title")) ?: return null
        val byline = item.path("longBylineText", "runs").arrayItems().ifEmpty { item.path("shortBylineText", "runs").arrayItems() }
        return Song(
            videoId = videoId,
            title = title,
            artists = artistsOf(byline),
            album = albumOf(byline),
            durationSeconds = durationOf(item.path("lengthText", "runs").arrayItems()),
            thumbnailUrl = item.path("thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
            kind = musicVideoTypeOf(item),
        )
    }

    /**
     * アーティストの runs を取り出す。ページ種別が付いていればそれを使い、無ければ区切り（" • "）の前の文字列をアーティストとみなす。
     */
    private fun artistsOf(runs: List<JsonObject>): List<ArtistRef> {
        val typed = typedArtistsOf(runs)
        if (typed.isNotEmpty()) return typed
        val firstSegment = runs.takeWhile { it.path("text").string() != SEPARATOR }
        return firstSegment
            .mapNotNull { it.path("text").string() }
            .filter { it.isNotBlank() && it != ", " && it != " & " && parseDuration(it) == null }
            .map { ArtistRef(it, null) }
    }

    private fun typedArtistsOf(runs: List<JsonObject>): List<ArtistRef> =
        runs.filter { pageTypeOf(it) in ARTIST_PAGE_TYPES }.mapNotNull { run ->
            // チャンネル（USER_CHANNEL）はアーティストのページとして開けないので、名前だけにする
            val browseId = run.path("navigationEndpoint", "browseEndpoint", "browseId").string().takeIf { pageTypeOf(run) == ARTIST_PAGE }
            run.path("text").string()?.let { ArtistRef(it, browseId) }
        }

    private fun albumOf(runs: List<JsonObject>): AlbumRef? =
        runs.firstOrNull { pageTypeOf(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.let { run ->
            run.path("text").string()?.let { AlbumRef(it, run.path("navigationEndpoint", "browseEndpoint", "browseId").string()) }
        }

    private fun pageTypeOf(run: JsonObject): String? = pageTypeOfBrowse(run.path("navigationEndpoint", "browseEndpoint"))

    private fun pageTypeOfBrowse(browse: JsonElement?): String? =
        browse.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").string()

    private fun nextContinuation(shelf: JsonObject): String? =
        shelf.path("continuations").arrayItems().firstNotNullOfOrNull { it.path("nextContinuationData", "continuation").string() }
            ?: shelf
                .path("contents")
                .arrayItems()
                .lastOrNull()
                .path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")
                .string()

    /** "4:22" や "1:02:03" を秒に。それ以外は null。 */
    fun parseDuration(text: String): Int? {
        if (!DURATION.matches(text)) return null
        return text.split(':').fold(0) { total, part -> total * 60 + part.toInt() }
    }

    private const val SEPARATOR = " • "
    private const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
    private const val LIBRARY_ARTIST_PAGE = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    private const val LIBRARY_ARTIST_PREFIX = "MPLA"
    private const val ALBUM_PLAYLIST_PREFIX = "OLAK5uy_"
    private const val EDIT_ICON = "EDIT"
    private val CONTINUATION_CONTAINERS = setOf("appendContinuationItemsAction", "continuationItems")
    private val CONTENT_RENDERERS =
        listOf(
            "musicTwoRowItemRenderer",
            "musicResponsiveListItemRenderer",
            "musicResponsiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            EDITABLE_PLAYLIST_HEADER,
            "musicPlaylistShelfRenderer",
        )
    const val EDITABLE_PLAYLIST_HEADER = "musicEditablePlaylistDetailHeaderRenderer"
    private val ARTIST_PAGE_TYPES = setOf(ARTIST_PAGE, "MUSIC_PAGE_TYPE_USER_CHANNEL")
    private const val GREY_OUT = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
}

/** キーを順にたどる。途中が無い・オブジェクトでなければ null。 */
internal fun JsonElement?.path(vararg keys: String): JsonElement? {
    var current: JsonElement? = this
    for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayItems(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonElement?.firstArrayItem(): JsonObject? = arrayItems().firstOrNull()

internal fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
