package xyz.mooma0750.wave.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.playback.NowPlayingSnapshot

/**
 * 再生サービスからホーム画面のウィジェットへの口（時計版は wear の同名のもので、何もしない）。
 * 曲・再生中かが変わったときだけウィジェットを描き直す（定期的には読まない）。
 */
object WidgetHooks {
    private val mutableNowPlaying = MutableStateFlow<NowPlayingSnapshot?>(null)

    /** 今の曲。プロセスの中だけに持つ（プロセスが無ければ再生サービスも無いので「再生していない」で正しい）。 */
    val nowPlaying: StateFlow<NowPlayingSnapshot?> = mutableNowPlaying.asStateFlow()

    @Volatile
    private var started = false

    /** Main から呼ぶ。 */
    fun onNowPlayingChanged(
        context: Context,
        snapshot: NowPlayingSnapshot?,
    ) {
        mutableNowPlaying.value = snapshot
        if (started) return
        started = true
        val app = context.applicationContext
        val container = (app as WaveApplication).container
        // 同じ値は StateFlow が落とす。描き直しの途中に変わったら、最後の値で描き直す
        container.appScope.launch {
            nowPlaying.collect {
                runCatching { NowPlayingWidget().updateAll(app) }
                    .onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
            }
        }
    }

    private val mutableOpenNowPlaying = MutableStateFlow(false)

    /** ウィジェットから「再生画面を開いて」と頼まれた（画面が開いたら [consumeOpenNowPlaying]）。 */
    val openNowPlaying: StateFlow<Boolean> = mutableOpenNowPlaying.asStateFlow()

    fun requestOpenNowPlaying() {
        mutableOpenNowPlaying.value = true
    }

    fun consumeOpenNowPlaying() {
        mutableOpenNowPlaying.value = false
    }

    /** ウィジェットから開いたときの印（MainActivity が読む）。 */
    const val EXTRA_OPEN_NOW_PLAYING = "xyz.mooma0750.wave.widget.OPEN_NOW_PLAYING"
    const val EXTRA_OPEN_PLAYLIST_ID = "xyz.mooma0750.wave.widget.OPEN_PLAYLIST_ID"
    const val EXTRA_OPEN_PLAYLIST_TITLE = "xyz.mooma0750.wave.widget.OPEN_PLAYLIST_TITLE"
}
