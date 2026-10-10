package xyz.mooma0750.wave.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.playback.PlayerConnection

/**
 * 曲の画面（スマホの曲の画面と、曲のメニューを兼ねる）。再生・次に再生・キューに追加・プレイリストに追加・ラジオ・アルバム・アーティスト。
 * 再生中の曲なら、歌詞・音量も出す。キューから開いたら、キューから外せる。
 */
@Composable
fun SongPage(
    song: Song,
    queueItemId: String?,
    isCurrent: Boolean,
    connection: PlayerConnection,
    actions: SongActions,
    onPlay: (Song) -> Unit,
    onLyrics: () -> Unit,
    onVolume: () -> Unit,
    /** 自分のプレイリストから開いたとき: そのプレイリストから外す。 */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ListScreen(
        title = null,
        edgeButton = { MainEdgeButton(R.drawable.ic_play_arrow, stringResource(R.string.ui_play)) { onPlay(song) } },
    ) { look ->
        item(key = "head") { EntryRow(look, song.title, song.subtitle, song.thumbnailUrl) { onPlay(song) } }
        if (isCurrent) {
            item(key = "lyrics") { ActionRow(look, R.drawable.ic_lyrics, stringResource(R.string.nowplaying_lyrics), onClick = onLyrics) }
            item(key = "volume") { ActionRow(look, R.drawable.ic_volume_up, stringResource(R.string.wear_volume), onClick = onVolume) }
        }
        item(key = "add") { ActionRow(look, R.drawable.ic_playlist_add, stringResource(R.string.search_add_to_playlist)) { actions.addToPlaylist(song) } }
        item(key = "next") {
            ActionRow(look, R.drawable.ic_playlist_play, stringResource(R.string.search_play_next)) {
                actions.playNext(song)
                onDone()
            }
        }
        item(key = "queue") {
            ActionRow(look, R.drawable.ic_queue_music, stringResource(R.string.search_add_to_queue)) {
                actions.addToQueue(song)
                onDone()
            }
        }
        if (queueItemId != null) {
            item(key = "remove") {
                ActionRow(look, R.drawable.ic_delete, stringResource(R.string.nowplaying_remove_from_queue)) {
                    scope.launch { connection.remove(queueItemId) }
                    onDone()
                }
            }
        }
        if (onRemoveFromPlaylist != null) {
            item(key = "remove-playlist") {
                ActionRow(look, R.drawable.ic_delete, stringResource(R.string.library_remove_from_this_playlist)) {
                    onRemoveFromPlaylist()
                    onDone()
                }
            }
        }
        item(key = "radio") { ActionRow(look, R.drawable.ic_graphic_eq, stringResource(R.string.search_start_radio)) { actions.startRadio(song) } }
        song.album?.takeIf { it.browseId != null }?.let { album ->
            item(key = "album") { ActionRow(look, R.drawable.ic_album, stringResource(R.string.search_go_to_album), secondary = album.title) { actions.openAlbum(AlbumRef(album.title, album.browseId)) } }
        }
        song.artists.filter { it.browseId != null }.forEach { artist ->
            item(key = "artist-${artist.browseId}") {
                ActionRow(look, R.drawable.ic_person, stringResource(R.string.search_go_to_artist), secondary = artist.name) { actions.openArtist(ArtistRef(artist.name, artist.browseId)) }
            }
        }
    }
}
