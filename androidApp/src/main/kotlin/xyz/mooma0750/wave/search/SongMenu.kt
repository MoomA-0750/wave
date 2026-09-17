package xyz.mooma0750.wave.search

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions

/** 曲のメニュー（仮）。キューへの追加と、アーティスト・アルバムのページへの移動。 */
@Composable
fun SongMenuButton(
    song: Song,
    actions: SongActions,
    inQueue: Boolean = false,
    /** 画面ごとの追加の項目（プレイリストから外す など）。 */
    extraItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("…") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!inQueue) {
                DropdownMenuItem(text = { Text("次に再生") }, onClick = {
                    open = false
                    actions.playNext(song)
                })
                DropdownMenuItem(text = { Text("キューに追加") }, onClick = {
                    open = false
                    actions.addToQueue(song)
                })
            }
            DropdownMenuItem(text = { Text("プレイリストに追加") }, onClick = {
                open = false
                actions.addToPlaylist(song)
            })
            extraItems.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    action()
                })
            }
            // チャンネル（browseId が無いもの）は開けない
            song.artists.filter { it.browseId != null }.forEach { artist ->
                DropdownMenuItem(text = { Text("アーティスト: ${artist.name}") }, onClick = {
                    open = false
                    actions.openArtist(artist)
                })
            }
            song.album?.takeIf { it.browseId != null }?.let { album ->
                DropdownMenuItem(text = { Text("アルバム: ${album.title}") }, onClick = {
                    open = false
                    actions.openAlbum(album)
                })
            }
        }
    }
}
