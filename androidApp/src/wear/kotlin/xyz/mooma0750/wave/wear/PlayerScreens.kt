package xyz.mooma0750.wave.wear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.lyrics.LyricsTiming
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.nowplaying.rememberPlayerUiState
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection

/** 再生キュー（スマホの再生画面のキューと同じ）。押すとその曲へ、長押しで曲のメニュー（キューから外す）。 */
@Composable
fun QueueScreen(
    connection: PlayerConnection,
    onOpenSong: (Song, String) -> Unit,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    val scope = rememberCoroutineScope()
    val shuffled by connection.shuffled.collectAsState()
    ListScreen(stringResource(R.string.nowplaying_queue)) { look ->
        item(key = "shuffle") {
            ActionRow(look, R.drawable.ic_shuffle, stringResource(if (shuffled) R.string.ui_shuffle_on else R.string.ui_shuffle_off)) { scope.launch { connection.toggleShuffle() } }
        }
        item(key = "repeat") {
            ActionRow(look, if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, repeatLabel(state.repeatMode)) {
                scope.launch { connection.cycleRepeatMode() }
            }
        }
        if (state.queue.any { it.userAdded }) {
            item(key = "clear") { ActionRow(look, R.drawable.ic_delete, stringResource(R.string.nowplaying_clear_queue)) { scope.launch { connection.clearUserQueue() } } }
        }
        // 今の曲から後ろ（前の曲は1曲だけ残す）
        val start = (state.currentIndex - 1).coerceAtLeast(0)
        val rows = state.queue.drop(start)
        if (rows.isEmpty()) item(key = "empty") { MessageRow(look, stringResource(R.string.queue_notice_empty)) }
        itemsIndexed(rows, key = { _, row -> "q-${row.itemId}" }) { offset, row ->
            val index = start + offset
            val title = if (index == state.currentIndex) "▶ ${row.title}" else row.title
            EntryRow(
                look,
                title,
                if (row.userAdded) "${row.artist} · ${stringResource(R.string.nowplaying_next_in_queue)}" else row.artist,
                row.thumbnailUrl,
                onLongClick = {
                    // 押すまでの間にキューが変わっていることがあるので、行の ID で今の位置を引き直す
                    controller?.let { c -> (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == row.itemId }?.let { c.getMediaItemAt(it) } }
                        ?.let(Media3QueuePlayer::songOf)
                        ?.let { onOpenSong(it, row.itemId) }
                },
            ) { scope.launch { connection.jumpTo(row.itemId) } }
        }
    }
}

/** 歌詞（スマホと同じ取り方）。時刻のある歌詞は今の行を大きく出して追う。行を押すとそこへ移る。 */
@Composable
fun LyricsScreen(
    connection: PlayerConnection,
    repository: LyricsRepository,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    val song = controller?.currentMediaItem?.let(Media3QueuePlayer::songOf)
    val result by produceState<LyricsRepository.Result?>(null, song?.videoId) { value = song?.let { repository.lyrics(it) } }
    val listState = rememberTransformingLazyColumnState()
    val lines = ((result as? LyricsRepository.Result.Found)?.lyrics as? Lyrics.Synced)?.lines
    val current = lines?.let { LyricsTiming.currentIndex(it, state.positionMs) } ?: -1
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem(current + 1) }
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item(key = "title") { Text(state.title.orEmpty(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2) }
            when (val found = result) {
                null -> item { Text(stringResource(R.string.nowplaying_lyrics_searching), textAlign = TextAlign.Center) }
                LyricsRepository.Result.None -> item { Text(stringResource(R.string.nowplaying_lyrics_none), textAlign = TextAlign.Center) }
                LyricsRepository.Result.Failed -> item { Text(stringResource(R.string.nowplaying_lyrics_load_failed), textAlign = TextAlign.Center) }
                is LyricsRepository.Result.Found ->
                    when (val lyrics = found.lyrics) {
                        is Lyrics.Synced ->
                            itemsIndexed(lyrics.lines) { index, line ->
                                Text(
                                    line.text.ifBlank { "♪" },
                                    modifier = Modifier.fillMaxWidth().clickable { controller?.seekTo(line.startMs) }.padding(vertical = 2.dp),
                                    textAlign = TextAlign.Center,
                                    style = if (index == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        is Lyrics.Plain -> item { Text(lyrics.text, textAlign = TextAlign.Center) }
                    }
            }
            (result as? LyricsRepository.Result.Found)?.lyrics?.source?.let { source ->
                item { Text(source, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
            }
        }
    }
}
