package xyz.mooma0750.wave.widget

import android.content.Context
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/** 時計にはホーム画面のウィジェットが無いので何もしない（スマホ版は phone の同名のもの）。 */
object WidgetHooks {
    @Suppress("UNUSED_PARAMETER")
    fun onNowPlayingChanged(
        context: Context,
        snapshot: NowPlayingSnapshot?,
    ) = Unit
}
