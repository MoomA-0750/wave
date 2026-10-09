package xyz.mooma0750.wave.ui

import androidx.compose.animation.core.Animatable
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

/**
 * アプリの中の短い知らせ（スナックバー）。どこからでも show で出せる。画面が無いとき（裏・ログイン前）の知らせは捨てる。
 */
object Notices {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = flow.asSharedFlow()

    fun show(message: String) {
        flow.tryEmit(message)
    }

    /** 知らせを出しておく時間。下のバーがこの時間で満タンから空になる。 */
    const val DURATION_MS = 4_000
}

/** 知らせを出す場所。下に、消えるまでの残り時間を表すバーを付ける（Windows 版と同じ）。 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val state = remember { SnackbarHostState() }
    LaunchedEffect(state) {
        Notices.messages.collect { message ->
            // 前の知らせは閉じて、新しいものを出す
            state.currentSnackbarData?.dismiss()
            state.showSnackbar(message, duration = SnackbarDuration.Indefinite)
        }
    }
    SnackbarHost(state, modifier) { data ->
        val remaining = remember(data) { Animatable(1f) }
        LaunchedEffect(data) {
            remaining.animateTo(0f, tween(Notices.DURATION_MS, easing = LinearEasing)) // m3e-allow: 消えるまでの残り時間を一定の速さで減らすタイマーで、画面の動きの演出ではない
            data.dismiss()
        }
        // スナックバーと同じ色・形の箱を自分で組み、バーは中の余白ではなく箱の下の縁に沿わせる
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            shape = SnackbarDefaults.shape,
            color = SnackbarDefaults.color,
            contentColor = SnackbarDefaults.contentColor,
            shadowElevation = 6.dp,
        ) {
            Column {
                Text(data.visuals.message, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium)
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
