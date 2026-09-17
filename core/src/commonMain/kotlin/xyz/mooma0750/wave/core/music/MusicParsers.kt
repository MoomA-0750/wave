package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        val songs = shelf.path("contents").arrayItems().mapNotNull { it.path("musicResponsiveListItemRenderer").asObject()?.let(::responsiveListItemSong) }
        return SongPage(songs, nextContinuation(shelf))
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
            val pageType = browse.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").string()
            val browseId = browse.path("browseId").string() ?: return@mapNotNull null
            if (pageType != "MUSIC_PAGE_TYPE_PLAYLIST" && !browseId.startsWith("VL")) return@mapNotNull null
            val title = item.path("title", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlaylistSummary(
                playlistId = browseId.removePrefix("VL"),
                title = title,
                subtitle = item.path("subtitle", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() },
                thumbnailUrl = item.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
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
        val items: List<JsonObject> =
            appended?.arrayItems()
                ?: findAll(root, "musicPlaylistShelfRenderer").firstOrNull().path("contents").arrayItems()
        val rows =
            items.mapNotNull { item ->
                val row = item.path("musicResponsiveListItemRenderer").asObject() ?: return@mapNotNull null
                responsiveListItemSong(row)?.let { it to row.path("playlistItemData", "playlistSetVideoId").string() }
            }
        val continuation =
            items.lastOrNull().path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").string()
                ?: findAll(root, "musicPlaylistShelfRenderer").firstOrNull().path("continuations").arrayItems().firstNotNullOfOrNull {
                    it.path("nextContinuationData", "continuation").string()
                }
        val title =
            findAll(root, "musicResponsiveHeaderRenderer").firstOrNull().path("title", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() }
                ?: findAll(root, "musicDetailHeaderRenderer").firstOrNull().path("title", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() }
        // 自分のプレイリストは編集用のヘッダを持つ（実アカウントの応答でレンダラ名だけを確かめる、決定 E7）
        val editable = findAll(root, EDITABLE_PLAYLIST_HEADER).isNotEmpty()
        return PlaylistPage(title, rows.map { it.first }, continuation, rows.map { it.second }, editable)
    }

    /** browse/edit_playlist の応答の status（`STATUS_SUCCEEDED` など）。 */
    fun editPlaylistStatus(root: JsonObject): String? = root.path("status").string()

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
        val thumbnail = header.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string()
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
        val thumbnail = header.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string()
        val sections =
            root
                .path("contents", "singleColumnBrowseResultsRenderer", "tabs")
                .firstArrayItem()
                .path("tabRenderer", "content", "sectionListRenderer", "contents")
                .arrayItems()
        val songShelf = sections.firstNotNullOfOrNull { it.path("musicShelfRenderer").asObject() }
        val topSongs = songShelf.path("contents").arrayItems().mapNotNull { it.path("musicResponsiveListItemRenderer").asObject()?.let(::responsiveListItemSong) }
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
                            thumbnailUrl = item.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
                        )
                    }
                if (albums.isEmpty()) return@mapNotNull null
                AlbumShelf(textOf(carousel.path("header", "musicCarouselShelfBasicHeaderRenderer", "title")).orEmpty(), albums)
            }
        return ArtistPage(name, thumbnail, topSongs, allSongs, albumShelves)
    }

    private fun textOf(element: JsonElement?): String? = element.path("runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() }

    /** ログインしていないときに返る「ログインしてください」の案内（200 の messageRenderer）だけの応答か。 */
    fun isSignInPrompt(root: JsonObject): Boolean =
        findAll(root, "messageRenderer").isNotEmpty() &&
            // 空のプレイリストも「曲がありません」の messageRenderer を持つ。ヘッダや曲の棚があればログインの案内ではない
            CONTENT_RENDERERS.none { findAll(root, it).isNotEmpty() }

    /** プレイリストのカードごとの、メニューの項目のアイコン名（値の無い列挙名だけ）。編集できるかの手がかりを調べる用（決定 E7）。 */
    fun playlistCardMenuIcons(root: JsonObject): List<Set<String>> = findAll(root, "musicTwoRowItemRenderer").map(::menuIconsOf)

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
        val title = titleRuns.joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() } ?: return null
        val detailRuns = columns.drop(1).flatten()
        val fixedDuration =
            item
                .path("fixedColumns")
                .arrayItems()
                .flatMap { it.path("musicResponsiveListItemFixedColumnRenderer", "text", "runs").arrayItems() }
                .firstNotNullOfOrNull { it.path("text").string()?.let(::parseDuration) }
        return Song(
            videoId = videoId,
            title = title,
            artists = artistsOf(detailRuns),
            album = albumOf(detailRuns),
            durationSeconds = fixedDuration ?: detailRuns.firstNotNullOfOrNull { it.path("text").string()?.let(::parseDuration) },
            thumbnailUrl = item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
        )
    }

    /** ラジオ・キューの行（playlistPanelVideoRenderer）。 */
    fun playlistPanelSong(item: JsonObject): Song? {
        val videoId = item.path("videoId").string() ?: item.path("navigationEndpoint", "watchEndpoint", "videoId").string() ?: return null
        val title = item.path("title", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() } ?: return null
        val byline = item.path("longBylineText", "runs").arrayItems().ifEmpty { item.path("shortBylineText", "runs").arrayItems() }
        return Song(
            videoId = videoId,
            title = title,
            artists = artistsOf(byline),
            album = albumOf(byline),
            durationSeconds = item.path("lengthText", "runs").arrayItems().firstNotNullOfOrNull { it.path("text").string()?.let(::parseDuration) },
            thumbnailUrl = item.path("thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
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
    private const val ALBUM_PLAYLIST_PREFIX = "OLAK5uy_"
    private const val EDIT_ICON = "EDIT"
    private val CONTENT_RENDERERS =
        listOf(
            "musicTwoRowItemRenderer",
            "musicResponsiveListItemRenderer",
            "musicResponsiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicEditablePlaylistDetailHeaderRenderer",
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

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
