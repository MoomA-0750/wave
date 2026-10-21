@file:OptIn(com.google.android.horologist.annotations.ExperimentalHorologistApi::class)

package xyz.mooma0750.wave.wear

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.foundation.pager.PagerDefaults
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.VerticalPagerScaffold
import com.google.android.horologist.audio.ui.material3.components.actions.SettingsButton
import com.google.android.horologist.audio.ui.material3.components.actions.SettingsButtonDefaults
import com.google.android.horologist.images.base.paintable.DrawableResPaintable
import com.google.android.horologist.images.base.paintable.Paintable
import com.google.android.horologist.media.ui.material3.components.animated.AnimatedMediaControlButtons
import com.google.android.horologist.media.ui.material3.components.animated.AnimatedMediaInfoDisplay
import com.google.android.horologist.media.ui.material3.components.background.ArtworkImageBackground
import com.google.android.horologist.media.ui.material3.screens.player.PlayerScreen
import com.google.android.horologist.media.ui.state.model.MediaUiModel
import com.google.android.horologist.media.ui.state.model.TrackPositionUiModel
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.library.PlaylistMembership
import xyz.mooma0750.wave.nowplaying.rememberPlayerUiState
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.PlayerConnection

/**
 * 再生画面とその下のページ（キュー・歌詞など）。下へ送るとキューが出る（Wear の Material 3 の縦のページ送り）。
 * ベゼルは再生画面では音量に使うので、ページ送りには使わない（キューの一覧はベゼルで送れる）。
 */
@Composable
fun PlayerPager(
    player: @Composable () -> Unit,
    pages: List<@Composable () -> Unit>,
) {
    val pagerState = rememberPagerState(pageCount = { 1 + pages.size })
    VerticalPagerScaffold(pagerState = pagerState) {
        VerticalPager(
            state = pagerState,
            flingBehavior =
                PagerDefaults.snapFlingBehavior(
                    state = pagerState,
                    maxFlingPages = 1,
                    snapPositionalThreshold = PagerScaffoldDefaults.HighSnapPositionalThreshold,
                    snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
            rotaryScrollableBehavior = null,
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                if (page == 0) player() else pages[page - 1]()
            }
        }
    }
}

/** 再生画面の下のボタン（再生する端末・評価・メニュー）。 */
class PlayerAction(
    val icon: Int,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 時計の再生画面（この時計で鳴らしている曲）。ベゼル（回転入力）で音量を変える（Wear OS の音楽アプリと同じ）。
 * 下のボタンは、再生する端末・評価（✓）・メニュー（大きい画面だけ）。
 */
@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    membership: PlaylistMembership,
    deviceIcon: Int,
    onOpenDevices: () -> Unit,
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
    val like by connection.likeState.collectAsState()
    val nowIn by membership.nowPlaying.collectAsState()
    val saved = like.savedWith(nowIn)
    val videoId = like.videoId
    WavePlayer(
        title = state.title,
        subtitle =
            when {
                suppressed -> stringResource(R.string.wear_connect_headphones)
                state.error != null -> stringResource(R.string.nowplaying_error)
                else -> state.artist.orEmpty()
            },
        artworkUrl = song?.thumbnailUrl,
        deviceIcon = deviceIcon,
        loading = false,
        playing = state.isPlaying,
        buffering = state.buffering,
        enabled = controller != null && state.title != null,
        hasPrevious = controller != null,
        hasNext = state.hasNext,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        onPlay = { controller?.play() },
        onPause = { controller?.pause() },
        onPrevious = { controller?.seekToPrevious() },
        onNext = { controller?.seekToNext() },
        volume = { volume },
        onRotate = { up ->
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            volume = audio.volumeFraction()
            true
        },
        device = PlayerAction(deviceIcon, stringResource(R.string.nowplaying_connected_devices), onClick = onOpenDevices),
        // 評価（スマホと同じ）。高く評価したか、どれかのプレイリストに入っていれば✓。✓を押すとプレイリストに追加する画面
        like =
            PlayerAction(
                when {
                    saved -> R.drawable.ic_check_circle
                    like.status == null && videoId != null -> R.drawable.ic_refresh
                    else -> R.drawable.ic_add_circle
                },
                stringResource(
                    when {
                        like.pending -> R.string.nowplaying_like_pending
                        saved -> R.string.nowplaying_saved_in_playlist
                        else -> R.string.nowplaying_like
                    },
                ),
                selected = saved,
                enabled = videoId != null && !like.pending && song != null,
            ) {
                when {
                    videoId == null -> Unit
                    saved -> song?.let(onAddToPlaylist)
                    else -> scope.launch { connection.setLiked(videoId, true) }
                }
            },
        menu = song?.let { PlayerAction(R.drawable.ic_more_horiz, stringResource(R.string.library_more_options)) { onOpenMenu(it) } },
    )
}

/**
 * 再生画面の並び（この時計の再生とスマホの再生で同じ）。Google の Horologist の再生画面（Wear OS のメディア操作の指針どおり）を使う:
 * 上に曲名（前に再生している端末のアイコン）とアーティスト、真ん中に前・再生・次（再生ボタンの周りの波打つ輪が進み具合。押した
 * ボタンが広がる）、下に小さなボタン（小さい画面は2つ、225dp 以上は3つ）、背景は今の曲のアートワーク。
 * ベゼルを回すと onRotate（true を返したら少しの間だけ音量を出す）。
 */
@Composable
fun WavePlayer(
    title: String?,
    subtitle: String,
    artworkUrl: String?,
    deviceIcon: Int,
    loading: Boolean,
    playing: Boolean,
    buffering: Boolean,
    enabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    volume: () -> Float?,
    onRotate: (up: Boolean) -> Boolean,
    device: PlayerAction,
    like: PlayerAction?,
    menu: PlayerAction?,
) {
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    val artwork = remember(artworkUrl) { artworkUrl?.let(::ArtworkPaintable) }
    val media =
        title?.let {
            MediaUiModel.Ready(id = it, title = it, subtitle = subtitle, artwork = artwork, titleIcon = DrawableResPaintable(deviceIcon))
        }
    val position =
        when {
            title == null -> TrackPositionUiModel.Hidden
            buffering -> TrackPositionUiModel.Loading(shouldAnimate = true)
            else ->
                TrackPositionUiModel.Actual(
                    percent = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    duration = durationMs.milliseconds,
                    position = positionMs.milliseconds,
                    shouldAnimate = true,
                )
        }
    val large = LocalConfiguration.current.screenWidthDp >= LARGE_SCREEN_DP
    // 指針: 下のボタンは小さい画面で2つ、225dp 以上で3つまで
    val actions = listOfNotNull(device, like, menu).take(if (large) 3 else 2)
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            PlayerScreen(
                mediaDisplay = { AnimatedMediaInfoDisplay(media = media, loading = loading) },
                controlButtons = {
                    AnimatedMediaControlButtons(
                        onPlayButtonClick = onPlay,
                        onPauseButtonClick = onPause,
                        playPauseButtonEnabled = enabled,
                        playing = playing,
                        onSeekToPreviousButtonClick = onPrevious,
                        seekToPreviousButtonEnabled = enabled && hasPrevious,
                        onSeekToNextButtonClick = onNext,
                        seekToNextButtonEnabled = enabled && hasNext,
                        trackPositionUiModel = position,
                    )
                },
                buttons = { PlayerButtons(actions) },
                modifier =
                    Modifier
                        .onRotaryScrollEvent { event ->
                            if (onRotate(event.verticalScrollPixels > 0)) volumeShownAt = System.currentTimeMillis()
                            true
                        }
                        // Wear の画面の重なり（スワイプで戻る箱・ページ送り）ではフォーカスを階層で配るので、表に出たときに受け取る
                        .requestFocusOnHierarchyActive()
                        .focusable(),
                background = { ArtworkImageBackground(artwork = artwork, modifier = Modifier.fillMaxSize()) },
            )
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
        }
    }
}

/** 下の小さなボタン（Horologist の見本と同じ並べ方）。選んでいるもの（✓）は塗る。 */
@Composable
private fun PlayerButtons(actions: List<PlayerAction>) {
    val configuration = LocalConfiguration.current
    Row(
        Modifier.padding(horizontal = (configuration.screenWidthDp / 10).dp).padding(bottom = (configuration.screenHeightDp / 100).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SettingsButton(
                    onClick = action.onClick,
                    imageVector = ImageVector.vectorResource(action.icon),
                    contentDescription = action.label,
                    enabled = action.enabled,
                    buttonColors = if (action.selected) IconButtonDefaults.filledIconButtonColors() else SettingsButtonDefaults.buttonColors(),
                )
            }
        }
    }
}

/** 今の曲のアートワーク（wave の Coil 3 で読む。Horologist の CoilPaintable は Coil 2 なので使わない）。 */
private data class ArtworkPaintable(
    val url: String,
) : Paintable {
    @Composable
    override fun rememberPainter(): Painter = coil3.compose.rememberAsyncImagePainter(Media3QueuePlayer.largeArtwork(url))
}

private const val LARGE_SCREEN_DP = 225

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
