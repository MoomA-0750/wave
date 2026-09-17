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

    /** 検索・プレイリストの行（musicResponsiveListItemRenderer）。 */
    fun responsiveListItemSong(item: JsonObject): Song? {
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
        val typed =
            runs.filter { pageTypeOf(it) in ARTIST_PAGE_TYPES }.mapNotNull { run ->
                run.path("text").string()?.let { ArtistRef(it, run.path("navigationEndpoint", "browseEndpoint", "browseId").string()) }
            }
        if (typed.isNotEmpty()) return typed
        val firstSegment = runs.takeWhile { it.path("text").string() != SEPARATOR }
        return firstSegment
            .mapNotNull { it.path("text").string() }
            .filter { it.isNotBlank() && it != ", " && it != " & " && parseDuration(it) == null }
            .map { ArtistRef(it, null) }
    }

    private fun albumOf(runs: List<JsonObject>): AlbumRef? =
        runs.firstOrNull { pageTypeOf(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.let { run ->
            run.path("text").string()?.let { AlbumRef(it, run.path("navigationEndpoint", "browseEndpoint", "browseId").string()) }
        }

    private fun pageTypeOf(run: JsonObject): String? =
        run
            .path(
                "navigationEndpoint",
                "browseEndpoint",
                "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig",
                "pageType",
            ).string()

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
    private val ARTIST_PAGE_TYPES = setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")
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
