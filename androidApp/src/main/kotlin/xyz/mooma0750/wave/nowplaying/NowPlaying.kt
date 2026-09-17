package xyz.mooma0750.wave.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.playback.PlayerConnection

/** 画面の下に常に出すミニプレーヤー（仮）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    connection: PlayerConnection,
    actions: xyz.mooma0750.wave.navigation.SongActions,
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
                TextButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = state.hasPrevious) { Text("前") }
                TextButton(onClick = {
                    controller?.run {
                        if (isPlaying) {
                            pause()
                        } else {
                            if (playerError != null) prepare()
                            play()
                        }
                    }
                }) { Text(if (state.isPlaying) "停止" else "再生") }
                TextButton(onClick = { controller?.seekToNextMediaItem() }, enabled = state.hasNext) { Text("次") }
                TextButton(onClick = { showQueue = true }) { Text("キュー") }
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
            FullPlayer(state, controller, connection, actions = actions.closing { showFull = false }, onQueue = {
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
    actions: xyz.mooma0750.wave.navigation.SongActions,
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
                        xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(c.getMediaItemAt(index))?.let { song ->
                            xyz.mooma0750.wave.search.SongMenuButton(song, actions, inQueue = true)
                        }
                    }
                    if (!current) TextButton(onClick = { scope.launch { connection.remove(row.itemId) } }) { Text("削除") }
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
    actions: xyz.mooma0750.wave.navigation.SongActions,
    onQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        coil3.compose.AsyncImage(
            model = controller?.currentMediaItem?.mediaMetadata?.artworkUri,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Text(state.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(controller?.currentMediaItem)?.let { song ->
                xyz.mooma0750.wave.search.SongMenuButton(song, actions, inQueue = true)
            }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = state.hasPrevious) { Text("前へ") }
            androidx.compose.material3.Button(onClick = {
                controller?.run {
                    if (isPlaying) {
                        pause()
                    } else {
                        if (playerError != null) prepare()
                        play()
                    }
                }
            }) { Text(if (state.isPlaying) "一時停止" else "再生") }
            TextButton(onClick = { controller?.seekToNextMediaItem() }, enabled = state.hasNext) { Text("次へ") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { scope.launch { connection.shuffleUpcoming() } }, enabled = state.queue.size - state.currentIndex > 2) { Text("残りをシャッフル") }
            TextButton(onClick = { scope.launch { connection.cycleRepeatMode() } }) {
                Text(
                    when (state.repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> "リピート: 全体"
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> "リピート: 1曲"
                        else -> "リピート: オフ"
                    },
                )
            }
        }
        val like by connection.likeState.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val videoId = like.videoId
            TextButton(
                onClick = { if (videoId != null) scope.launch { connection.toggleLike(videoId) } },
                enabled = videoId != null && like.status != null && !like.pending,
            ) {
                Text(
                    when {
                        like.pending -> "変更中…"
                        like.status == xyz.mooma0750.wave.core.music.LikeStatus.Like -> "高く評価済み（外す）"
                        like.status == null -> "評価を確認中"
                        else -> "高く評価"
                    },
                )
            }
        }
        like.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text("エラー: $it", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onQueue) { Text("再生キュー（${state.queue.size}曲）") }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
