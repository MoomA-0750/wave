package xyz.mooma0750.wave.ui

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
