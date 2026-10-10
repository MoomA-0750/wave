package xyz.mooma0750.wave.song

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuItems
import xyz.mooma0750.wave.ui.CollectionScreen
import xyz.mooma0750.wave.ui.DetailText
import xyz.mooma0750.wave.ui.PlayFab

/**
 * 曲の画面（ホームの棚の曲を押したときに開く）。アルバム・プレイリストと同じく、押してもすぐには再生せず、
 * 再生ボタン（その曲から始まるラジオ）と曲のメニューと同じ操作を並べる。
 */
@Composable
fun SongScreen(
    song: Song,
    actions: SongActions,
    onPlay: (Song) -> Unit,
    onBack: () -> Unit,
) {
    CollectionScreen(
        title = song.title,
        coverUrl = song.thumbnailUrl,
        onBack = onBack,
        details = {
            song.subtitle.takeIf { it.isNotEmpty() }?.let { DetailText(it) }
            song.durationSeconds?.let { DetailText("%d:%02d".format(it / 60, it % 60)) }
        },
        play = null,
        actions = { PlayFab(playing = false, onClick = { onPlay(song) }, modifier = Modifier.padding(start = 12.dp, end = 8.dp)) },
    ) {
        item(key = "song-actions") { SongMenuItems(song, actions, showRadio = false) }
    }
}
