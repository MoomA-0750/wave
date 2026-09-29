package xyz.mooma0750.wave.nowplaying

import xyz.mooma0750.wave.ui.IconTextButton
import xyz.mooma0750.wave.R
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.lyrics.LyricsTiming
import xyz.mooma0750.wave.core.music.Song

/** 再生中の曲の歌詞。時刻付きなら再生位置に合わせて今の行を強調し、行を押すとそこへ飛ぶ。 */
@Composable
fun LyricsPanel(
    song: Song,
    player: Player?,
    lyrics: LyricsRepository,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(song.videoId) { mutableIntStateOf(0) }
    val result by produceState<LyricsRepository.Result?>(null, song.videoId, attempt) { value = lyrics.lyrics(song) }
    when (val current = result) {
        null -> Message("歌詞を探しています…", modifier)
        LyricsRepository.Result.None -> Message("この曲の歌詞は見つかりませんでした", modifier)
        LyricsRepository.Result.Failed ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("歌詞を読み込めませんでした")
                    IconTextButton(R.drawable.ic_refresh, "もう一度", { attempt++ })
                }
            }
        is LyricsRepository.Result.Found ->
            when (val found = current.lyrics) {
                is Lyrics.Synced -> SyncedLyrics(found, player, modifier)
                is Lyrics.Plain ->
                    Column(modifier.verticalScroll(rememberScrollState())) {
                        Text(found.text, style = MaterialTheme.typography.bodyLarge)
                        Source(found.source)
                    }
            }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics.Synced,
    player: Player?,
    modifier: Modifier,
) {
    var positionMs by remember { mutableLongStateOf(player?.currentPosition ?: 0) }
    // 再生位置は通知が来ないので読みに行く。行の切り替えが目で見て遅れない間隔
    LaunchedEffect(player) {
        while (player != null) {
            positionMs = player.currentPosition
            delay(POSITION_POLL_MS)
        }
    }
    // 同じ時刻の行（原文と読み・訳が並ぶ歌詞）は、まとめて今の行とする
    val currentStartMs = lyrics.lines.getOrNull(LyricsTiming.currentIndex(lyrics.lines, positionMs))?.startMs
    val currentIndex = if (currentStartMs == null) -1 else lyrics.lines.indexOfFirst { it.startMs == currentStartMs }
    val listState = rememberLazyListState()

    // 本人がスクロールしたら、しばらく自動で追わない
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) userScrolledAt = SystemClock.elapsedRealtime() }
    }
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0 || SystemClock.elapsedRealtime() - userScrolledAt < USER_SCROLL_HOLD_MS) return@LaunchedEffect
        // 今の行を表示の上から3分の1あたりに置く
        listState.animateScrollToItem(currentIndex, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
    }

    LazyColumn(modifier, state = listState) {
        items(lyrics.lines) { line ->
            val isCurrent = line.startMs == currentStartMs
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            player?.seekTo(line.startMs)
                            userScrolledAt = 0
                        }.padding(vertical = 8.dp),
            )
        }
        item { Source(lyrics.source) }
    }
}

@Composable
private fun Source(source: String?) {
    source?.let { Text(it, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Message(
    text: String,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text) }
}

private const val POSITION_POLL_MS = 100L
private const val USER_SCROLL_HOLD_MS = 3_000L
