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
import androidx.compose.animation.core.animateFloat
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
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
        deviceIcon = deviceLabel?.let { R.drawable.ic_watch },
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
 * 再生画面の並び（この時計の再生とスマホの再生で同じ形）。Wear OS のメディア操作の指針（上・真ん中・下の3段）に合わせる:
 * 上に再生している端末のアイコンと曲名・アーティスト、真ん中に前・再生・次（再生ボタンの周りの輪が進み具合。鳴っている間は
 * ボタンと輪が波打つ）、下に小さなボタン（大きい画面は3つ、小さい画面は2つ）。背景は今の曲のアートワークをぼかして暗くしたもの。
 * ベゼルを回すと onRotate（true を返したら少しの間だけ音量を出す）。
 */
@Composable
fun PlayerLayout(
    artworkUrl: String?,
    progress: Float,
    title: String,
    subtitle: String,
    onSubtitle: (() -> Unit)?,
    /** 再生している端末のアイコン（曲名の前。押すとスマホとこの時計の表示を入れ替える）。 */
    deviceIcon: Int?,
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
    likeButton: (@Composable () -> Unit)?,
    onOpenQueue: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
) {
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    // 指針の区切り: 225dp 以上の画面は操作を大きくし、下のボタンを3つにする
    val large = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= LARGE_SCREEN_DP
    val sizes = if (large) PlayerSizes.Large else PlayerSizes.Small
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
            // 背景に今の曲のアートワーク（ぼかして、文字とボタンが読めるよう上に暗い幕をかける）
            artworkUrl?.let { url ->
                coil3.compose.AsyncImage(
                    model = Media3QueuePlayer.largeArtwork(url),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)))
            }
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
                Modifier.fillMaxSize().padding(top = sizes.topMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 上: 端末のアイコンと曲名（長い曲名は流す）、アーティスト（切る）
                Row(
                    Modifier.padding(horizontal = sizes.titleMargin),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    deviceIcon?.let { icon ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onDeviceLabel),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(icon), deviceLabel, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(
                        title,
                        modifier = Modifier.basicMarquee(),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    subtitle,
                    modifier = Modifier.padding(horizontal = sizes.artistMargin).then(if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier),
                    color = if (onSubtitle != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            // 真ん中: 前・再生（周りに進み具合の輪）・次。画面のまん中に置く
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SideButton(R.drawable.ic_skip_previous, stringResource(R.string.nowplaying_previous), enabled, sizes, onPrevious)
                Box(Modifier.size(sizes.main), contentAlignment = Alignment.Center) {
                    WavyProgressRing({ progress }, isPlaying, sizes.ringStroke, Modifier.fillMaxSize())
                    PlayButton(isPlaying, enabled, sizes, onPlayPause)
                }
                SideButton(R.drawable.ic_skip_next, stringResource(R.string.nowplaying_next), enabled && hasNext, sizes, onNext)
            }
            // 下: 小さなボタン。小さい画面は2つまで（メニューがあれば評価を外す。メニューの「プレイリストに追加」で代わりになる）
            val queue: (@Composable () -> Unit)? =
                onOpenQueue?.let { { SecondaryButton(R.drawable.ic_queue_music, stringResource(R.string.nowplaying_queue), sizes, onClick = it) } }
            val menu: (@Composable () -> Unit)? =
                onOpenMenu?.let { { SecondaryButton(R.drawable.ic_more_horiz, stringResource(R.string.library_more_options), sizes, onClick = it) } }
            val slots = listOfNotNull(likeButton, queue, menu).let { if (!large && it.size > 2) it.drop(1) else it }
            CompositionLocalProvider(LocalPlayerSizes provides sizes) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = sizes.bottomMargin),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { slots.forEach { it() } }
            }
        }
    }
}

/** 再生画面の大きさ（Wear OS のメディア操作の指針の値。225dp で切り替える）。再生ボタンは、波打つ輪との間に隙間が見えるよう指針（54・70dp）より小さくする。 */
private enum class PlayerSizes(
    val main: androidx.compose.ui.unit.Dp,
    val button: androidx.compose.ui.unit.Dp,
    val icon: androidx.compose.ui.unit.Dp,
    val ringStroke: androidx.compose.ui.unit.Dp,
    val sideWidth: androidx.compose.ui.unit.Dp,
    val sideHeight: androidx.compose.ui.unit.Dp,
    val secondaryWidth: androidx.compose.ui.unit.Dp,
    val secondaryHeight: androidx.compose.ui.unit.Dp,
    val topMargin: androidx.compose.ui.unit.Dp,
    val titleMargin: androidx.compose.ui.unit.Dp,
    val artistMargin: androidx.compose.ui.unit.Dp,
    val bottomMargin: androidx.compose.ui.unit.Dp,
) {
    Small(64.dp, 50.dp, 26.dp, 3.dp, 52.dp, 64.dp, 56.dp, 40.dp, 26.dp, 34.dp, 24.dp, 14.dp),
    Large(80.dp, 64.dp, 32.dp, 4.dp, 60.dp, 80.dp, 48.dp, 38.dp, 30.dp, 40.dp, 32.dp, 16.dp),
}

private val LocalPlayerSizes = androidx.compose.runtime.staticCompositionLocalOf { PlayerSizes.Large }

private const val LARGE_SCREEN_DP = 225

/** 前・次（再生ボタンを左右から挟む、縦長の塗りのボタン。押すと角が締まる）。 */
@Composable
private fun SideButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    sizes: PlayerSizes,
    onClick: () -> Unit,
) {
    FilledIconButton(
        shapes = IconButtonDefaults.animatedShapes(MaterialTheme.shapes.extraLarge, MaterialTheme.shapes.medium),
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledVariantIconButtonColors(),
        modifier = Modifier.size(sizes.sideWidth, sizes.sideHeight),
    ) {
        Icon(painterResource(icon), label, Modifier.size(sizes.icon))
    }
}

/** 再生・一時停止。鳴っている間は波の形（クッキー）、止まっている間は丸。形はばねで移る。 */
@Composable
private fun PlayButton(
    isPlaying: Boolean,
    enabled: Boolean,
    sizes: PlayerSizes,
    onClick: () -> Unit,
) {
    val morph = remember { androidx.graphics.shapes.Morph(circlePolygon(), cookiePolygon()) }
    val amount by androidx.compose.animation.core.animateFloatAsState(
        if (isPlaying) 1f else 0f,
        MaterialTheme.motionScheme.slowSpatialSpec(),
    )
    val shape = remember(amount) { MorphShape(morph, amount) }
    FilledIconButton( // m3e-allow: 形は鳴っているかで波と丸の間を移る（押したときの形の変化の代わり）
        shapes = IconButtonDefaults.shapes(shape),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(sizes.button),
    ) {
        Icon(
            painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
            stringResource(if (isPlaying) R.string.nowplaying_pause else R.string.nowplaying_play),
            modifier = Modifier.size(sizes.icon),
        )
    }
}

private fun circlePolygon() = androidx.graphics.shapes.RoundedPolygon.circle(numVertices = COOKIE_WAVES)

private fun cookiePolygon() =
    androidx.graphics.shapes.RoundedPolygon.star(
        numVerticesPerRadius = COOKIE_WAVES,
        innerRadius = 0.86f,
        rounding = androidx.graphics.shapes.CornerRounding(0.5f),
    )

private const val COOKIE_WAVES = 12

/** 2つの形の間の形（graphics-shapes の Morph。大きさは -1〜1 の形を部品の大きさへ広げる）。 */
private class MorphShape(
    private val morph: androidx.graphics.shapes.Morph,
    private val progress: Float,
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val path = morph.toPath(progress, android.graphics.Path())
        val matrix = android.graphics.Matrix().apply { setScale(size.width / 2, size.height / 2); postTranslate(size.width / 2, size.height / 2) }
        path.transform(matrix)
        return androidx.compose.ui.graphics.Outline.Generic(path.asComposePath())
    }
}

/** 再生ボタンの周りの進み具合の輪。鳴っている間は波打ち、波がゆっくり回る（止まると平らな輪に戻る）。 */
@Composable
private fun WavyProgressRing(
    progress: () -> Float,
    playing: Boolean,
    stroke: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val amplitude by androidx.compose.animation.core.animateFloatAsState(if (playing) 1f else 0f, MaterialTheme.motionScheme.slowEffectsSpec())
    val phase by androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
        0f,
        (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(WAVE_PERIOD_MS, easing = androidx.compose.animation.core.LinearEasing), // m3e-allow: 波の位相を等速で回し続ける（ばねでは止まってしまう）
        ),
    )
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    androidx.compose.foundation.Canvas(modifier) {
        val strokePx = stroke.toPx()
        // 輪は再生ボタン（指針: 輪 80dp・ボタン 70dp）の外側の隙間に収める
        val waveHeight = strokePx * WAVE_HEIGHT * amplitude
        val radius = size.minDimension / 2 - strokePx / 2 - strokePx * WAVE_HEIGHT
        val center = this.center
        val style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun ring(
            from: Float,
            sweep: Float,
        ): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            val steps = (sweep * 90).toInt().coerceAtLeast(2)
            for (i in 0..steps) {
                val t = from + sweep * i / steps
                val angle = (t * 2 * Math.PI - Math.PI / 2).toFloat()
                val r = radius + waveHeight * kotlin.math.sin(angle * RING_WAVES + phase)
                val x = center.x + r * kotlin.math.cos(angle)
                val y = center.y + r * kotlin.math.sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        val done = progress().coerceIn(0f, 1f)
        drawPath(ring(0f, 1f), track, style = style)
        if (done > 0f) drawPath(ring(0f, done), color, style = style)
    }
}

private const val RING_WAVES = 12
private const val WAVE_HEIGHT = 0.3f
private const val WAVE_PERIOD_MS = 4_000

/** 下の小さなボタン（塗りのボタン。選んでいるときは濃い塗り）。 */
@Composable
fun SecondaryButton(
    icon: Int,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = SecondaryButton(icon, label, LocalPlayerSizes.current, selected, enabled, onClick)

@Composable
private fun SecondaryButton(
    icon: Int,
    label: String,
    sizes: PlayerSizes,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilledIconButton(
        shapes = IconButtonDefaults.animatedShapes(),
        onClick = onClick,
        enabled = enabled,
        colors = if (selected) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = Modifier.size(sizes.secondaryWidth, sizes.secondaryHeight),
    ) {
        Icon(painterResource(icon), label, Modifier.size(IconButtonDefaults.SmallIconSize))
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
    SecondaryButton(
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
            saved -> onSaved()
            else -> scope.launch { connection.setLiked(videoId, true) }
        }
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
