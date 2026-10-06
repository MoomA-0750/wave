package xyz.mooma0750.wave.navigation

import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.playback.PlayerConnection

/** 仮の画面遷移（Phase 4 で作り直す）。ホームの上に積む。 */
sealed interface Screen {
    data object Search : Screen

    /** 端末の連携（Phase 6 のペアリングと引き取り）。 */
    data object Connect : Screen

    /** 再生履歴（自分が聴いた曲）。 */
    data object History : Screen

    /** 気分・ジャンルのページ。 */
    data class Mood(
        val title: String,
        val params: String,
    ) : Screen

    /** 設定（アカウント・再生履歴の送信・ログアウト）。 */
    data object Settings : Screen

    data class Playlist(
        val summary: PlaylistSummary,
        /** 開いたときに入れておく絞り込み（アーティストの高評価曲から来たときなど）。 */
        val initialQuery: String? = null,
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
data class SongActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val openArtist: (ArtistRef) -> Unit,
    val openAlbum: (AlbumRef) -> Unit,
    val addToPlaylist: (Song) -> Unit,
    /** その曲から始まるラジオを再生する（Spotify の「曲のラジオを再生」。キューを置き換える）。 */
    val startRadio: (Song) -> Unit,
) {
    /** シートの上から画面を開くときに、先にシートを閉じる。 */
    fun closing(close: () -> Unit): SongActions =
        copy(
            openArtist = {
                close()
                openArtist(it)
            },
            openAlbum = {
                close()
                openAlbum(it)
            },
            // プレイリストへの追加はダイアログが上に重なるので、再生中の画面は閉じない（追加の後に元の画面へ戻る）
        )
}

/** 画面から再生サービスへのキューの操作（画面の scope で送る。結果は待たない）。 */
class PlayerActions(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val player: PlayerConnection,
) {
    fun playSong(song: Song) {
        scope.launch { player.playSong(song) }
    }

    fun playFrom(
        songs: List<Song>,
        index: Int,
        playlistId: String? = null,
        continuation: String? = null,
        shuffle: Boolean = false,
    ) {
        scope.launch { player.playFrom(songs, index, playlistId, continuation, shuffle) }
    }

    /** 丸ごとキューに入れる。next = 次に再生（false は最後に追加）。 */
    fun queue(
        songs: List<Song>,
        next: Boolean,
    ) {
        scope.launch { if (next) player.addNext(songs) else player.addToEnd(songs) }
    }
}
