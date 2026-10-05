package xyz.mooma0750.wave.search

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.MenuIcon

/**
 * 曲のメニュー（Spotify の「⋯」と同じく、下からのシートに曲の画像と名前、その下に操作）。
 * キューへの追加・プレイリストへの追加・アーティストとアルバムのページへの移動。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuButton(
    song: Song,
    actions: SongActions,
    inQueue: Boolean = false,
    /** 画面ごとの追加の項目（プレイリストから外す など）。 */
    extraItems: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    IconAction(R.drawable.ic_more_vert, "メニュー", { open = true })
    if (!open) return
    val close = { open = false }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                coil3.compose.AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(song.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow(R.drawable.ic_add_circle, "プレイリストに追加") {
                close()
                actions.addToPlaylist(song)
            }
            if (!inQueue) {
                MenuRow(R.drawable.ic_playlist_play, "次に再生") {
                    close()
                    actions.playNext(song)
                }
                MenuRow(R.drawable.ic_queue_music, "キューに追加") {
                    close()
                    actions.addToQueue(song)
                }
            }
            extraItems.forEach { (label, action) ->
                MenuRow(R.drawable.ic_delete, label) {
                    close()
                    action()
                }
            }
            song.album?.takeIf { it.browseId != null }?.let { album ->
                MenuRow(R.drawable.ic_album, "アルバムへ移動") {
                    close()
                    actions.openAlbum(album)
                }
            }
            // チャンネル（browseId が無いもの）は開けない
            song.artists.filter { it.browseId != null }.forEach { artist ->
                MenuRow(R.drawable.ic_person, if (song.artists.size > 1) "アーティストへ移動: ${artist.name}" else "アーティストへ移動") {
                    close()
                    actions.openArtist(artist)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MenuRow(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { MenuIcon(icon) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
