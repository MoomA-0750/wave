package xyz.mooma0750.wave.navigation

import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song

/** 仮の画面遷移（Phase 4 で作り直す）。ホームの上に積む。 */
sealed interface Screen {
    data object Search : Screen

    data class Playlist(
        val summary: PlaylistSummary,
    ) : Screen

    data class Artist(
        val browseId: String,
        val name: String,
    ) : Screen

    data class Album(
        val browseId: String,
        val title: String,
    ) : Screen
}

/** 曲の一覧・キュー・再生画面から共通に呼ぶ操作。 */
class SongActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val openArtist: (ArtistRef) -> Unit,
    val openAlbum: (AlbumRef) -> Unit,
) {
    /** シートの上から画面を開くときに、先にシートを閉じる。 */
    fun closing(close: () -> Unit): SongActions =
        SongActions(
            playNext = playNext,
            addToQueue = addToQueue,
            openArtist = {
                close()
                openArtist(it)
            },
            openAlbum = {
                close()
                openAlbum(it)
            },
        )
}
