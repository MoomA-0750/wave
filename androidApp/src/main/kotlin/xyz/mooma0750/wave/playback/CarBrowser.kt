package xyz.mooma0750.wave.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult

/**
 * Android Auto に出す一覧（ADR-0014）。車のコントローラーにだけ見せる。
 * 曲の ID は一覧で渡したもの（`s:<親>:<位置>`）だけで、選ばれたら覚えている一覧から曲を引く（任意の videoId を受けない）。
 * メインスレッドだけから使う。
 */
internal class CarBrowser(
    private val music: MusicRepository,
    private val playlistsTitle: String,
    private val historyTitle: String,
) {
    /** 車で選んだ曲（画面で一覧から選んだときと同じ playFrom で流す）。 */
    data class Selection(
        val songs: List<Song>,
        val index: Int,
        val playlistId: String?,
        val continuation: String?,
    )

    /** 渡した一覧（親の ID → 曲・続き）。選ばれたときに引く。 */
    private val lists = HashMap<String, Listed>()

    private data class Listed(
        val songs: List<Song>,
        val playlistId: String?,
        val continuation: String?,
    )

    fun root(): MediaItem = folder(ROOT, "wave")

    /** 子の一覧。知らない親・読めなかったら null。 */
    suspend fun children(parentId: String): List<MediaItem>? =
        when {
            parentId == ROOT -> listOf(folder(PLAYLISTS, playlistsTitle), folder(HISTORY, historyTitle))
            parentId == PLAYLISTS ->
                when (val result = music.libraryPlaylists()) {
                    is CallResult.Ok -> result.value.map { folder(PLAYLIST_PREFIX + it.playlistId, it.title, it.subtitle) }
                    is CallResult.Failed -> null
                }
            parentId == HISTORY ->
                when (val result = music.history()) {
                    is CallResult.Ok -> songs(HISTORY, Listed(result.value.songs.filterNot { it.isLive }, null, null))
                    is CallResult.Failed -> null
                }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX)
                when (val result = music.playlist(playlistId)) {
                    is CallResult.Ok -> songs(parentId, Listed(result.value.songs, playlistId, result.value.continuation))
                    is CallResult.Failed -> null
                }
            }
            else -> null
        }

    /** 一覧で渡した曲の ID から、流す曲を引く。渡していない ID は null。 */
    fun selection(mediaId: String): Selection? {
        if (!mediaId.startsWith(SONG_PREFIX)) return null
        val rest = mediaId.removePrefix(SONG_PREFIX)
        val split = rest.lastIndexOf(':')
        if (split <= 0) return null
        val listed = lists[rest.substring(0, split)] ?: return null
        val index = rest.substring(split + 1).toIntOrNull()?.takeIf { it in listed.songs.indices } ?: return null
        return Selection(listed.songs, index, listed.playlistId, listed.continuation)
    }

    private fun songs(
        parentId: String,
        listed: Listed,
    ): List<MediaItem> {
        lists[parentId] = listed
        // 覚える一覧は増えすぎないようにする（開いたプレイリストの数だけ増える）
        if (lists.size > MAX_LISTS) lists.keys.firstOrNull { it != parentId }?.let(lists::remove)
        return listed.songs.mapIndexed { index, song ->
            MediaItem
                .Builder()
                .setMediaId("$SONG_PREFIX$parentId:$index")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(song.title)
                        .setArtist(song.artistsText)
                        .setAlbumTitle(song.album?.title)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                ).build()
        }
    }

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(if (id.startsWith(PLAYLIST_PREFIX)) MediaMetadata.MEDIA_TYPE_PLAYLIST else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            ).build()

    companion object {
        /** Android Auto（車の画面・Desktop Head Unit）。 */
        const val CAR_PACKAGE = "com.google.android.projection.gearhead"
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val HISTORY = "history"
        private const val PLAYLIST_PREFIX = "pl:"
        private const val SONG_PREFIX = "s:"
        private const val MAX_LISTS = 20
    }
}
