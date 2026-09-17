package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable

/** 画面に依らない曲の表現。UI・再生キュー・端末間の受け渡しで共通に使う。 */
@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
) {
    /** 「アーティスト • アルバム」の1行。 */
    val subtitle: String
        get() = listOfNotNull(artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }, album?.title).joinToString(" • ")
}

@Serializable
data class ArtistRef(
    val name: String,
    val browseId: String?,
)

@Serializable
data class AlbumRef(
    val title: String,
    val browseId: String?,
)

/** 曲の一覧の1ページ。 */
data class SongPage(
    val songs: List<Song>,
    /** 続きを取るためのトークン。無ければ最後のページ。 */
    val continuation: String?,
)

/** ラジオ（次に流れる曲）の1ページ。 */
data class UpNextPage(
    val songs: List<Song>,
    val continuation: String?,
    /** ラジオの playlistId（続きの取得に使う）。 */
    val playlistId: String?,
)

/** ライブラリなどに並ぶプレイリスト。 */
@Serializable
data class PlaylistSummary(
    /** VL を除いた ID（キューの出どころや続きの取得に使う）。 */
    val playlistId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
)

/** プレイリストの1ページ。 */
data class PlaylistPage(
    val title: String?,
    val songs: List<Song>,
    val continuation: String?,
)
