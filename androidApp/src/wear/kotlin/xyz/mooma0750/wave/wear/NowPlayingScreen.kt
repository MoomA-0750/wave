package xyz.mooma0750.wave.wear

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.library.PlaylistMembership
import xyz.mooma0750.wave.nowplaying.rememberPlayerUiState
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection

/**
 * 時計の再生画面（この時計で鳴らしている曲）。周りの輪が進み具合、真ん中に曲名と前・再生・次、下に評価（✓）・キュー・メニュー。
 * ベゼル（回転入力）で音量を変える（Wear OS の音楽アプリと同じ）。
 */
@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    membership: PlaylistMembership,
    deviceLabel: String?,
    onDeviceLabel: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMenu: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
) {
    val (state, controller) = rememberPlayerUiState(connection)
    val scope = rememberCoroutineScope()
    val song = controller?.currentMediaItem?.let(Media3QueuePlayer::songOf)
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableFloatStateOf(audio.volumeFraction()) }
    // 時計のスピーカーでは鳴らさない（Wear OS の決まり）。イヤホンが無くて止められたら、出力先を選ぶ画面を1回だけ開き、案内も出す
    var suppressed by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(controller) {
        val current = controller ?: return@DisposableEffect onDispose {}
        fun read() {
            val now = current.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT && current.playWhenReady
            if (now && !suppressed) openOutputSwitcher(context)
            suppressed = now
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) = read()
            }
        current.addListener(listener)
        read()
        onDispose { current.removeListener(listener) }
    }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    PlayerLayout(
        artworkUrl = song?.thumbnailUrl,
        progress = progress,
        title = state.title ?: stringResource(R.string.wear_nothing_playing),
        subtitle =
            when {
                suppressed -> stringResource(R.string.wear_connect_headphones)
                state.error != null -> stringResource(R.string.nowplaying_error)
                state.buffering -> stringResource(R.string.nowplaying_buffering)
                else -> state.artist.orEmpty()
            },
        // イヤホンが無いときは、押すと出力先を選ぶ画面を開く
        onSubtitle = if (suppressed) ({ openOutputSwitcher(context) }) else null,
        deviceLabel = deviceLabel,
        onDeviceLabel = onDeviceLabel,
        isPlaying = state.isPlaying,
        enabled = controller != null,
        hasNext = state.hasNext,
        onPrevious = { controller?.seekToPrevious() },
        onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
        onNext = { controller?.seekToNext() },
        volume = { volume },
        onRotate = { up ->
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            volume = audio.volumeFraction()
            true
        },
        likeButton = { LikeButton(connection, membership, song, onSaved = { song?.let(onAddToPlaylist) }, scope = scope) },
        onOpenQueue = onOpenQueue,
        onOpenMenu = song?.let { { onOpenMenu(it) } },
    )
}

/**
 * 再生画面の並び（この時計の再生とスマホの再生で同じ形）。周りの輪が進み具合、上にどこで鳴っているか、真ん中に曲名と前・再生・次、下に評価・キュー・メニュー。
 * ベゼルを回すと onRotate（true を返したら少しの間だけ音量を出す）。
 */
@Composable
fun PlayerLayout(
    artworkUrl: String?,
    progress: Float,
    title: String,
    subtitle: String,
    onSubtitle: (() -> Unit)?,
    deviceLabel: String?,
    onDeviceLabel: () -> Unit,
    isPlaying: Boolean,
    enabled: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    volume: () -> Float?,
    onRotate: (up: Boolean) -> Boolean,
    likeButton: @Composable () -> Unit,
    onOpenQueue: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
) {
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    if (onRotate(event.verticalScrollPixels > 0)) volumeShownAt = System.currentTimeMillis()
                    true
                }
                // Wear の画面の重なり（スワイプで戻る箱など）ではフォーカスを階層で配るので、表に出たときに受け取る（requestFocus では届かなかった）
                .requestFocusOnHierarchyActive()
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            // 背景に今の曲のアートワーク（文字とボタンが読めるよう、上に暗い幕をかける）
            artworkUrl?.let { url ->
                coil3.compose.AsyncImage(
                    model = Media3QueuePlayer.largeArtwork(url),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)))
            }
            // 部品は渡した関数の中で読んだ状態の変化だけを追うので、状態を通して渡す（値をそのまま渡すと最初の値のまま動かない）
            val progressState = androidx.compose.runtime.rememberUpdatedState(progress)
            CircularProgressIndicator(progress = { progressState.value }, modifier = Modifier.fillMaxSize().padding(3.dp), strokeWidth = 5.dp)
            // 回したら少しの間だけ音量を出す
            var showVolume by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(volumeShownAt) {
                if (volumeShownAt == 0L) return@LaunchedEffect
                showVolume = 1f
                delay(1_500)
                showVolume = 0f
            }
            val level = volume()
            if (showVolume > 0f && level != null) LevelIndicator(value = { level }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // どこで鳴っているか（押すとスマホとこの時計の表示を切り替える）
                    Text(
                        deviceLabel.orEmpty(),
                        modifier = Modifier.clickable(enabled = deviceLabel != null, onClick = onDeviceLabel).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        title,
                        modifier = Modifier.basicMarquee(),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        subtitle,
                        modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier,
                        color = if (onSubtitle != null) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onPrevious, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.nowplaying_previous))
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        enabled = enabled,
                    ) {
                        Icon(
                            painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                            stringResource(if (isPlaying) R.string.nowplaying_pause else R.string.nowplaying_play),
                            modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                        )
                    }
                    IconButton(onClick = onNext, enabled = enabled && hasNext) {
                        Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.nowplaying_next))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    likeButton()
                    IconButton(onClick = { onOpenQueue?.invoke() }, enabled = onOpenQueue != null) {
                        Icon(painterResource(R.drawable.ic_queue_music), stringResource(R.string.nowplaying_queue))
                    }
                    IconButton(onClick = { onOpenMenu?.invoke() }, enabled = onOpenMenu != null) {
                        Icon(painterResource(R.drawable.ic_more_horiz), stringResource(R.string.library_more_options))
                    }
                }
            }
        }
    }
}

/** 評価のボタン（スマホと同じ）。高く評価したか、どれかのプレイリストに入っていれば✓。✓を押すとプレイリストに追加する画面。 */
@Composable
private fun LikeButton(
    connection: PlayerConnection,
    membership: PlaylistMembership,
    song: Song?,
    onSaved: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val like by connection.likeState.collectAsState()
    val nowIn by membership.nowPlaying.collectAsState()
    val videoId = like.videoId
    val saved = like.savedWith(nowIn)
    IconButton(
        onClick = {
            when {
                videoId == null -> Unit
                saved -> onSaved()
                else -> scope.launch { connection.setLiked(videoId, true) }
            }
        },
        enabled = videoId != null && !like.pending && song != null,
    ) {
        Icon(
            painterResource(
                when {
                    saved -> R.drawable.ic_check_circle
                    like.status == null && videoId != null -> R.drawable.ic_refresh
                    else -> R.drawable.ic_add_circle
                },
            ),
            stringResource(
                when {
                    like.pending -> R.string.nowplaying_like_pending
                    saved -> R.string.nowplaying_saved_in_playlist
                    else -> R.string.nowplaying_like
                },
            ),
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun AudioManager.volumeFraction(): Float {
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

/** 繰り返しの次の状態の文言。 */
@Composable
fun repeatLabel(mode: Int): String =
    stringResource(
        when (mode) {
            Player.REPEAT_MODE_ALL -> R.string.nowplaying_repeat_all
            Player.REPEAT_MODE_ONE -> R.string.nowplaying_repeat_one
            else -> R.string.nowplaying_repeat_off
        },
    )
