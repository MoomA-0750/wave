package xyz.mooma0750.wave.nowplaying

import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.R
import androidx.media3.common.Player
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import xyz.mooma0750.wave.ui.PlayFab
import xyz.mooma0750.wave.ui.rememberArtworkColor
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * 画面の下のミニプレーヤー（並びは Spotify と同じ: ジャケット・曲名・端末・評価・再生、下に進み具合の線）。
 * 押すと再生画面を開く。左右に払うと前後の曲へ。
 */
@Composable
fun MiniPlayer(
    connection: PlayerConnection,
    onOpen: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    if (state.title == null) return
    val scope = rememberCoroutineScope()
    val song = Media3QueuePlayer.songOf(controller?.currentMediaItem)
    val art by rememberArtworkColor(song?.thumbnailUrl)
    val container = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.35f)
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(controller) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                drag < -SWIPE_PX -> controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
                                drag > SWIPE_PX -> controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
                            }
                            drag = 0f
                        },
                    ) { _, amount -> drag += amount }
                }.clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                coil3.compose.AsyncImage(
                    model = song?.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(state.artist, state.error?.let { stringResource(R.string.nowplaying_error, it) }, if (state.buffering) stringResource(R.string.nowplaying_buffering) else null).joinToString(" • "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconAction(R.drawable.ic_devices, stringResource(R.string.nowplaying_connected_devices), onOpenDevices)
                LikeButton(connection, scope)
                IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (state.isPlaying) stringResource(R.string.nowplaying_pause) else stringResource(R.string.nowplaying_play), { controller?.togglePlayPause() })
            }
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

/** 評価のボタン（Spotify の「＋」と「✓」）。押すと「高く評価」を付け外しする。 */
@Composable
private fun LikeButton(
    connection: PlayerConnection,
    scope: kotlinx.coroutines.CoroutineScope,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val like by connection.likeState.collectAsState()
    val videoId = like.videoId
    val liked = like.status == LikeStatus.Like
    IconAction(
        when {
            liked -> R.drawable.ic_check_circle
            like.status == null && videoId != null -> R.drawable.ic_refresh
            else -> R.drawable.ic_add_circle
        },
        when {
            like.pending -> stringResource(R.string.nowplaying_like_pending)
            liked -> stringResource(R.string.nowplaying_unlike)
            like.status == null -> stringResource(R.string.nowplaying_like_reload)
            else -> stringResource(R.string.nowplaying_like)
        },
        { if (videoId != null) scope.launch { connection.setLiked(videoId, !liked) } },
        enabled = videoId != null && !like.pending,
        active = liked,
        size = size,
    )
}

/**
 * 再生画面（並びは Spotify と同じ）。上にジャケットの色のグラデーション、閉じる・曲／動画の切り替え・メニュー、
 * 大きなジャケット（動画のときは映像）、曲名と評価、シークバー、操作の列、端末と再生キュー、下に歌詞のカード。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    lyrics: LyricsRepository,
    videos: MusicVideos,
    actions: SongActions,
    onClose: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    val scope = rememberCoroutineScope()
    val song = Media3QueuePlayer.songOf(controller?.currentMediaItem)
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var video by rememberSaveable { mutableStateOf(false) }
    val art by rememberArtworkColor(song?.thumbnailUrl)
    val background = MaterialTheme.colorScheme.background
    val top = lerp(background, art, 0.6f)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.75f to background)),
    ) {
        // 曲名から操作の列までが最初の画面に収まるよう、ジャケットは高さに合わせて小さくする（開いた折りたたみ・横向き）
        val artSize = minOf(minOf(maxWidth, MAX_CONTENT_WIDTH) - 48.dp, maxHeight - CONTROLS_HEIGHT).coerceAtLeast(160.dp)
        Column(
            Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_arrow_down, stringResource(R.string.nowplaying_close), onClose)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Spotify の「曲｜動画」の切り替え
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = !video, onClick = { video = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text(stringResource(R.string.nowplaying_song)) }
                        SegmentedButton(selected = video, onClick = { video = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}, enabled = song != null) { Text(stringResource(R.string.nowplaying_video)) }
                    }
                }
                song?.let { SongMenuButton(it, actions, inQueue = true) } ?: Spacer(Modifier.size(48.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                if (video && song != null) {
                    MusicVideoPanel(song, controller, videos, Modifier.size(artSize).clip(RoundedCornerShape(8.dp)))
                } else {
                    coil3.compose.AsyncImage(
                        model = song?.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork),
                        contentDescription = null,
                        modifier = Modifier.size(artSize).clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val artist = song?.artists?.firstOrNull()
                    Text(
                        state.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(if (artist?.browseId != null) Modifier.clickable { actions.openArtist(artist) } else Modifier),
                    )
                }
                LikeButton(connection, scope, size = 28.dp)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Slider(
                    value = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (state.durationMs > 0) controller?.seekTo((it * state.durationMs).toLong()) },
                    enabled = state.durationMs > 0,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconAction(R.drawable.ic_shuffle, stringResource(R.string.nowplaying_shuffle_upcoming), { scope.launch { connection.shuffleUpcoming() } }, enabled = state.queue.size - state.currentIndex > 2)
                IconAction(R.drawable.ic_skip_previous, stringResource(R.string.nowplaying_previous), { controller?.seekToPreviousMediaItem() }, enabled = state.hasPrevious, size = 36.dp)
                PlayFab(state.isPlaying, { controller?.togglePlayPause() }, size = 72.dp)
                IconAction(R.drawable.ic_skip_next, stringResource(R.string.nowplaying_next), { controller?.seekToNextMediaItem() }, enabled = state.hasNext, size = 36.dp)
                IconAction(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ALL -> stringResource(R.string.nowplaying_repeat_all)
                        Player.REPEAT_MODE_ONE -> stringResource(R.string.nowplaying_repeat_one)
                        else -> stringResource(R.string.nowplaying_repeat_off)
                    },
                    { scope.launch { connection.cycleRepeatMode() } },
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_devices, stringResource(R.string.nowplaying_connected_devices), onOpenDevices)
                Spacer(Modifier.weight(1f))
                IconAction(R.drawable.ic_queue_music, pluralStringResource(R.plurals.nowplaying_queue_with_count, state.queue.size, state.queue.size), { showQueue = true })
            }
            val like by connection.likeState.collectAsState()
            like.message?.let { Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(stringResource(R.string.nowplaying_error, it), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall) }
            // 下に続く歌詞のカード（Spotify と同じ。押すと全面で開く）
            if (song != null) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, art, 0.5f)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nowplaying_lyrics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconAction(R.drawable.ic_lyrics, stringResource(R.string.nowplaying_lyrics_open_full), { showLyrics = true })
                    }
                    LyricsPanel(song, controller, lyrics, Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 8.dp))
                }
            }
        }
    }

    if (showLyrics && song != null) {
        ModalBottomSheet(onDismissRequest = { showLyrics = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(state.title.orEmpty(), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.artist.orEmpty(), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LyricsPanel(song, controller, lyrics, Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(8.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            QueueSheet(state, controller, connection, actions.closing { showQueue = false })
        }
    }
}

private const val SWIPE_PX = 120f

/** ジャケットの下の、曲名から端末・キューの列までの高さ（ジャケットの大きさの上限を決める）。 */
private val CONTROLS_HEIGHT = 400.dp

/** 広い画面でも、操作の列が横に間延びしないように（Spotify と同じく中央の列に収める）。 */
private val MAX_CONTENT_WIDTH = 560.dp

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
        Text(stringResource(R.string.nowplaying_queue), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    if (!current) IconAction(R.drawable.ic_delete, stringResource(R.string.nowplaying_remove_from_queue), { scope.launch { connection.remove(row.itemId) } })
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun formatTime(ms: Long): String = Durations.ofMillis(ms)

