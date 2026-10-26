package xyz.mooma0750.wave.overlay

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.WaveTypography

/** 重ね表示の中身。端の丸（＋曲が変わったときの吹き出し）か、ミニプレーヤー。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverlayContent(
    ui: PlayerOverlay.UiState,
    bubbleTouch: (MotionEvent) -> Boolean,
    miniTouch: (MotionEvent) -> Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSizeSettled: () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive(), typography = WaveTypography) {
        val now = ui.now ?: return@MaterialExpressiveTheme
        val motion = MaterialTheme.motionScheme
        val modeTransition = updateTransition(ui.mode, label = "overlay-mode")
        // 外から見える大きさ。掴んでいる間・動いている間（窓は画面いっぱい）は、丸とミニプレーヤーの大きさの間をばねで動かす。
        // 落ち着いている間（窓は中身の大きさ）はそのまま合わせる（吹き出し・歌詞の行の出し入れ）
        val shownSize = remember { Animatable(IntSize.Zero, IntSize.VectorConverter) }
        var targetSize by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(targetSize) {
            if (targetSize == IntSize.Zero) return@LaunchedEffect
            if (shownSize.value == IntSize.Zero || !ui.stage) shownSize.snapTo(targetSize) else shownSize.animateTo(targetSize, motion.defaultSpatialSpec())
            onSizeSettled()
        }
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                ui.shownSizeNow = IntSize(placeable.width, placeable.height)
                if (!ui.stage) {
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                } else {
                    // 窓は画面いっぱい（窓の左上が params の 0,0）。指の位置と、掴んだ所の中身の中の割合から置く
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val c = ui.centerFor(ui.finger, placeable.width, placeable.height)
                        placeable.place((c.x - placeable.width / 2f).roundToInt(), (c.y - placeable.height / 2f).roundToInt())
                    }
                }
            },
        ) {
            ModeContent(ui, now, motion, modeTransition, { shownSize.value }, { targetSize = it; ui.targetSizeNow = it }, bubbleTouch, miniTouch, onPrevious, onTogglePlay, onNext)
        }
    }
}

/**
 * 丸とミニプレーヤーは、どちらも組み立てたまま持っておき、切り替えでは見え方（透明度・拡大）と外の大きさだけを動かす。
 * 切り替えるたびに新しい方を組み立てると、その1コマに 30ms ほどかかって止まって見えた。
 */
@Composable
private fun ModeContent(
    ui: PlayerOverlay.UiState,
    now: xyz.mooma0750.wave.playback.NowPlayingSnapshot,
    motion: androidx.compose.material3.MotionScheme,
    transition: Transition<PlayerOverlay.Mode>,
    shownSize: () -> IntSize,
    onTargetSize: (IntSize) -> Unit,
    bubbleTouch: (MotionEvent) -> Boolean,
    miniTouch: (MotionEvent) -> Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val bubbleAlpha by transition.animateFloat({ if (targetState == PlayerOverlay.Mode.Bubble) motion.defaultEffectsSpec() else motion.fastEffectsSpec() }, label = "bubble-alpha") {
        if (it == PlayerOverlay.Mode.Bubble) 1f else 0f
    }
    val miniAlpha by transition.animateFloat({ if (targetState == PlayerOverlay.Mode.Mini) motion.defaultEffectsSpec() else motion.fastEffectsSpec() }, label = "mini-alpha") {
        if (it == PlayerOverlay.Mode.Mini) 1f else 0f
    }
    val bubbleScale by transition.animateFloat({ motion.defaultSpatialSpec() }, label = "bubble-scale") { if (it == PlayerOverlay.Mode.Bubble) 1f else HIDDEN_SCALE }
    val miniScale by transition.animateFloat({ motion.defaultSpatialSpec() }, label = "mini-scale") { if (it == PlayerOverlay.Mode.Mini) 1f else HIDDEN_SCALE }
    Layout(
        content = {
            Row(
                Modifier.graphicsLayer {
                    alpha = bubbleAlpha
                    scaleX = bubbleScale
                    scaleY = bubbleScale
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 吹き出しは丸の内側（画面の中央の側）へ出す
                if (ui.atEnd) Tooltip(ui, now.title, now.artist)
                Bubble(now.artworkUrl, now.playing, bubbleTouch)
                if (!ui.atEnd) Tooltip(ui, now.title, now.artist)
            }
            Box(
                Modifier.graphicsLayer {
                    alpha = miniAlpha
                    scaleX = miniScale
                    scaleY = miniScale
                },
            ) { Mini(ui, now, miniTouch, onPrevious, onTogglePlay, onNext) }
        },
    ) { measurables, _ ->
        val bubble = measurables[0].measure(Constraints())
        val mini = measurables[1].measure(Constraints())
        val target = if (ui.mode == PlayerOverlay.Mode.Bubble) IntSize(bubble.width, bubble.height) else IntSize(mini.width, mini.height)
        onTargetSize(target)
        val size = shownSize().takeIf { it != IntSize.Zero } ?: target
        val bubbleOnTop = ui.mode == PlayerOverlay.Mode.Bubble
        layout(size.width, size.height) {
            // 両方とも置いたままにし、今の方を上に重ねる（触れるのは上だけ）。見えていない方を外すと、
            // 切り替えの途中から掴んでいた指がその時点で取り消され、掴んだそばから離れてしまった
            bubble.place((size.width - bubble.width) / 2, (size.height - bubble.height) / 2, zIndex = if (bubbleOnTop) 1f else 0f)
            mini.place((size.width - mini.width) / 2, (size.height - mini.height) / 2, zIndex = if (bubbleOnTop) 0f else 1f)
        }
    }
}

/** 見えていない方の拡大率（切り替えでは、ここから広がり、ここへ縮む）。 */
private const val HIDDEN_SCALE = 0.6f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Bubble(
    artworkUrl: String?,
    playing: Boolean,
    touch: (MotionEvent) -> Boolean,
) {
    Box(Modifier.size(PlayerOverlay.BUBBLE_DP.dp).padding(6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxSize().pointerInteropFilter { touch(it) },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                coil3.compose.AsyncImage(
                    model = artworkUrl,
                    contentDescription = stringResource(R.string.overlay_bubble_description),
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                // 止めている間は、ジャケットを暗くして再生の印を出す（タップで再生できると分かるように）
                if (!playing) {
                    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), modifier = Modifier.fillMaxSize()) {}
                    Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable
private fun Tooltip(
    ui: PlayerOverlay.UiState,
    title: String,
    artist: String,
) {
    AnimatedVisibility(
        visible = ui.tooltip && !ui.dragging,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp).widthIn(max = 240.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMediumEmphasized)
                if (artist.isNotEmpty()) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Mini(
    ui: PlayerOverlay.UiState,
    now: xyz.mooma0750.wave.playback.NowPlayingSnapshot,
    touch: (MotionEvent) -> Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(6.dp).width((PlayerOverlay.MINI_WIDTH_DP - 12).dp),
    ) {
        Column {
            // 上の段（ジャケット・曲名・歌詞）を持って動かす。タップで wave を開く
            Column(Modifier.fillMaxWidth().pointerInteropFilter { touch(it) }.padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    coil3.compose.AsyncImage(
                        model = now.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMediumEmphasized)
                        Text(now.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ui.lyric?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconAction(R.drawable.ic_skip_previous, stringResource(R.string.nowplaying_previous), onPrevious)
                IconAction(
                    if (now.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    if (now.playing) stringResource(R.string.nowplaying_pause) else stringResource(R.string.nowplaying_play),
                    onTogglePlay,
                    size = 32.dp,
                )
                IconAction(R.drawable.ic_skip_next, stringResource(R.string.nowplaying_next), onNext)
            }
        }
    }
}

/** 丸を掴んでいる間に画面の下に出す ×。丸が乗ると大きくなり、色が変わる。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DismissContent(ui: PlayerOverlay.UiState) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive(), typography = WaveTypography) {
        val motion = MaterialTheme.motionScheme
        val scale by animateFloatAsState(if (ui.dismissHover) 1.25f else 1f, motion.fastSpatialSpec(), label = "dismiss-scale")
        val color by animateColorAsState(
            if (ui.dismissHover) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.inverseSurface,
            motion.defaultEffectsSpec(),
            label = "dismiss-color",
        )
        // 窓の大きさは変えない（丸を吸い付ける位置が動かないように）
        Box(Modifier.size(DISMISS_BOX_DP.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = ui.dismissShown,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.5f),
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.5f),
            ) {
                Surface(
                    shape = CircleShape,
                    color = color,
                    shadowElevation = 4.dp,
                    modifier =
                        Modifier.size(56.dp).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.overlay_dismiss),
                            tint = if (ui.dismissHover) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }
    }
}

/** × の窓の大きさ（大きくなった × と影が収まる）。 */
private const val DISMISS_BOX_DP = 88
