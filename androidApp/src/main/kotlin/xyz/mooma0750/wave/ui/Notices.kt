package xyz.mooma0750.wave.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
            remaining.animateTo(0f, tween(Notices.DURATION_MS, easing = LinearEasing))
            data.dismiss()
        }
        Snackbar(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column {
                Text(data.visuals.message)
                LinearProgressIndicator(
                    progress = { remaining.value },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}
