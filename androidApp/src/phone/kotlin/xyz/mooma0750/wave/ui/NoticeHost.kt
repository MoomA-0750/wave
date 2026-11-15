package xyz.mooma0750.wave.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kasane.KasaneSpace

/**
 * 出ている知らせ（スナックバー）の高さ（px、余白を含む。出ていなければ 0）。NoticeHost が書き、一覧が下の余白に使う。
 * 枠（AppShell）が渡す。知らせと重ならない一覧（デスクトップの並びの左のライブラリ）には 0 のままのものを渡す。
 */
val LocalNoticeHeight = compositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

/**
 * 一覧の下の余白（知らせが出ている間だけ、その高さぶん）。知らせはミニプレーヤーのすぐ上に重なって出るので、
 * 余白が無いと一番下の行が隠れて読めない・押せない。一番下まで送っていたときに知らせが出たら、余白の広がりに合わせて送り、最後の行を知らせの上に出す。
 */
@Composable
fun noticeBottomPadding(listState: ScrollableState): Dp {
    val target = LocalNoticeHeight.current.intValue
    // 知らせが出た瞬間（余白を足す前）に一番下にいたか
    val wasAtEnd = remember(target) { !listState.canScrollForward }
    val padding by animateIntAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "notice-padding")
    LaunchedEffect(target) {
        if (!wasAtEnd || target == 0) return@LaunchedEffect
        // 余白が広がるたびに一番下まで送る（行き過ぎは一番下で止まる）
        snapshotFlow { padding }.first { value ->
            listState.scrollBy(value.toFloat())
            value == target
        }
    }
    return with(LocalDensity.current) { padding.toDp() }
}

/** 知らせを出す場所。下に、消えるまでの残り時間を表すバーを付ける（Windows 版と同じ）。 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val state = remember { SnackbarHostState() }
    val height = LocalNoticeHeight.current
    // 閉じたら（スナックバーが無くなったら）余白を戻す
    DisposableEffect(height) { onDispose { height.intValue = 0 } }
    LaunchedEffect(state) {
        Notices.messages.collect { message ->
            // 前の知らせは閉じて、新しいものを出す
            state.currentSnackbarData?.dismiss()
            state.showSnackbar(message, duration = SnackbarDuration.Indefinite)
        }
    }
    SnackbarHost(state, modifier.onSizeChanged { height.intValue = it.height }) { data ->
        val remaining = remember(data) { Animatable(1f) }
        LaunchedEffect(data) {
            remaining.animateTo(0f, tween(Notices.DURATION_MS, easing = LinearEasing)) // m3e-allow: 消えるまでの残り時間を一定の速さで減らすタイマーで、画面の動きの演出ではない
            data.dismiss()
        }
        // スナックバーと同じ色・形の箱を自分で組み、バーは中の余白ではなく箱の下の縁に沿わせる
        Surface(
            modifier = Modifier.padding(horizontal = KasaneSpace.s3, vertical = KasaneSpace.s2).fillMaxWidth(),
            shape = SnackbarDefaults.shape,
            color = SnackbarDefaults.color,
            contentColor = SnackbarDefaults.contentColor,
            shadowElevation = 6.dp,
        ) {
            Column {
                Text(data.visuals.message, Modifier.padding(horizontal = KasaneSpace.s4, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium)
                // 箱の下の縁に沿わせる細いバーなので、波は付けず平らなまま減らす
                val barStroke = Stroke(width = with(LocalDensity.current) { 3.dp.toPx() }, cap = StrokeCap.Butt)
                LinearWavyProgressIndicator(
                    progress = { remaining.value },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    trackColor = Color.Transparent,
                    stroke = barStroke,
                    trackStroke = barStroke,
                    gapSize = 0.dp,
                    stopSize = 0.dp,
                    amplitude = { 0f },
                )
            }
        }
    }
}
