package xyz.mooma0750.wave.overlay

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
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
) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive(), typography = WaveTypography) {
        val now = ui.now ?: return@MaterialExpressiveTheme
        when (ui.mode) {
            PlayerOverlay.Mode.Bubble ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 吹き出しは丸の内側（画面の中央の側）へ出す
                    if (ui.atEnd) Tooltip(ui, now.title, now.artist)
                    Bubble(now.artworkUrl, now.playing, bubbleTouch)
                    if (!ui.atEnd) Tooltip(ui, now.title, now.artist)
                }
            PlayerOverlay.Mode.Mini -> Mini(ui, now, miniTouch, onPrevious, onTogglePlay, onNext)
        }
    }
}

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
