package xyz.mooma0750.wave.nowplaying

import xyz.mooma0750.wave.ui.LargeIconSize
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.R
import androidx.media3.common.Player
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection
import xyz.mooma0750.wave.playback.togglePlayPause
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.ui.Durations

/** 画面の下に常に出すミニプレーヤー（仮）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    connection: PlayerConnection,
    actions: SongActions,
    lyrics: LyricsRepository,
    videos: MusicVideos,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    var showQueue by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    if (state.title == null) return

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { showFull = true }) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(state.artist, state.error?.let { "エラー: $it" }, "バッファ中".takeIf { state.buffering }).joinToString(" • "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconAction(R.drawable.ic_skip_previous, "前の曲", { controller?.seekToPreviousMediaItem() }, enabled = state.hasPrevious)
                IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.isPlaying) "一時停止" else "再生", { controller?.togglePlayPause() })
                IconAction(R.drawable.ic_skip_next, "次の曲", { controller?.seekToNextMediaItem() }, enabled = state.hasNext)
                IconAction(R.drawable.ic_queue_music, "再生キュー", { showQueue = true })
            }
            if (state.durationMs > 0) {
                Slider(
                    value = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f),
                    onValueChange = { controller?.seekTo((it * state.durationMs).toLong()) },
                )
            }
        }
    }

    if (showFull) {
        ModalBottomSheet(onDismissRequest = { showFull = false }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FullPlayer(state, controller, connection, lyrics, videos, actions = actions.closing { showFull = false }, onQueue = {
                showFull = false
                showQueue = true
            })
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheet(state, controller, connection, actions.closing { showQueue = false })
        }
    }
}

@Composable
private fun QueueSheet(
    state: PlayerUiState,
    controller: MediaController?,
    connection: PlayerConnection,
    actions: SongActions,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (state.currentIndex > 0) listState.scrollToItem(state.currentIndex) }
    Column(Modifier.fillMaxWidth()) {
        Text("再生キュー（${state.queue.size}曲）", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            itemsIndexed(state.queue, key = { _, row -> row.itemId }) { index, row ->
                val current = index == state.currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (current) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                        .clickable { scope.launch { connection.jumpTo(row.itemId) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        Text(row.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    controller?.takeIf { index < it.mediaItemCount }?.let { c ->
                        Media3QueuePlayer.songOf(c.getMediaItemAt(index))?.let { song ->
                            SongMenuButton(song, actions, inQueue = true)
                        }
                    }
                    if (!current) IconAction(R.drawable.ic_delete, "キューから削除", { scope.launch { connection.remove(row.itemId) } })
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 再生中の画面（大）。仮の見た目。 */
@Composable
private fun FullPlayer(
    state: PlayerUiState,
    controller: MediaController?,
    connection: PlayerConnection,
    lyrics: LyricsRepository,
    videos: MusicVideos,
    actions: SongActions,
    onQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val song = Media3QueuePlayer.songOf(controller?.currentMediaItem)
    var view by rememberSaveable { mutableStateOf(PlayerView.Artwork) }
    Column(
        Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (view == PlayerView.Lyrics && song != null) {
            LyricsPanel(song, controller, lyrics, Modifier.fillMaxWidth().aspectRatio(1f))
        } else if (view == PlayerView.Video && song != null) {
            MusicVideoPanel(song, controller, videos, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
        } else {
            coil3.compose.AsyncImage(
                model = song?.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        Text(state.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            song?.let { SongMenuButton(it, actions, inQueue = true) }
        }
        if (state.durationMs > 0) {
            Slider(
                value = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f),
                onValueChange = { controller?.seekTo((it * state.durationMs).toLong()) },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconAction(R.drawable.ic_shuffle, "残りをシャッフル", { scope.launch { connection.shuffleUpcoming() } }, enabled = state.queue.size - state.currentIndex > 2)
            IconAction(R.drawable.ic_skip_previous, "前の曲", { controller?.seekToPreviousMediaItem() }, enabled = state.hasPrevious)
            FilledIconButton(onClick = { controller?.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription = if (state.isPlaying) "一時停止" else "再生",
                    modifier = Modifier.size(LargeIconSize),
                )
            }
            IconAction(R.drawable.ic_skip_next, "次の曲", { controller?.seekToNextMediaItem() }, enabled = state.hasNext)
            IconAction(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "リピート: 全体"
                    Player.REPEAT_MODE_ONE -> "リピート: 1曲"
                    else -> "リピート: オフ"
                },
                { scope.launch { connection.cycleRepeatMode() } },
                active = state.repeatMode != Player.REPEAT_MODE_OFF,
            )
        }
        val like by connection.likeState.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            val videoId = like.videoId
            IconAction(
                when (like.status) {
                    LikeStatus.Like -> R.drawable.ic_favorite
                    null -> R.drawable.ic_refresh
                    else -> R.drawable.ic_favorite_border
                },
                when {
                    like.pending -> "変更中"
                    like.status == LikeStatus.Like -> "高く評価を外す"
                    like.status == null -> "評価を読み直す"
                    else -> "高く評価"
                },
                { if (videoId != null) scope.launch { connection.setLiked(videoId, like.status != LikeStatus.Like) } },
                enabled = videoId != null && !like.pending,
                active = like.status == LikeStatus.Like,
            )
            IconAction(
                R.drawable.ic_lyrics,
                if (view == PlayerView.Lyrics) "歌詞を閉じる" else "歌詞",
                { view = if (view == PlayerView.Lyrics) PlayerView.Artwork else PlayerView.Lyrics },
                enabled = song != null,
                active = view == PlayerView.Lyrics,
            )
            IconAction(
                R.drawable.ic_music_video,
                if (view == PlayerView.Video) "動画を閉じる" else "動画",
                { view = if (view == PlayerView.Video) PlayerView.Artwork else PlayerView.Video },
                enabled = song != null,
                active = view == PlayerView.Video,
            )
            IconAction(R.drawable.ic_queue_music, "再生キュー（${state.queue.size}曲）", onQueue)
        }
        like.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text("エラー: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatTime(ms: Long): String = Durations.ofMillis(ms)

/** 再生中の画面の上半分に出すもの。 */
private enum class PlayerView { Artwork, Lyrics, Video }
