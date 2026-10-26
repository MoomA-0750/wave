package xyz.mooma0750.wave.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        WidgetScale.load(app)
        val container = (app as WaveApplication).container
        // ほかのアプリの上に重ねる再生の丸（設定でオンのときだけ出る）
        xyz.mooma0750.wave.overlay.PlayerOverlay.start(app)
        // 同じ値は StateFlow が落とす。描き直しの途中に変わったら、最後の値で描き直す
        container.appScope.launch {
            nowPlaying.collect {
                runCatching { NowPlayingWidget().updateAll(app) }
                    .onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
            }
        }
        // ワンタップ再生は、鳴っているもの・再生中かが変わったときだけ（再生・一時停止のボタンを替える）
        container.appScope.launch {
            nowPlaying.map { now -> now?.let { Triple(it.sourceId, it.videoId, it.playing) } }.distinctUntilChanged().collect {
                runCatching { PlaylistWidget().updateAll(app) }
                    .onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
            }
        }
        // キューのウィジェットは、次に流れる曲とほかの端末の名前が変わったとき（何も鳴っていない・次が無いの出し分けも）
        container.appScope.launch {
            nowPlaying
                .map { now -> now?.let { it.upNext to it.device } }
                .distinctUntilChanged()
                .collect {
                    runCatching { QueueWidget().updateAll(app) }
                        .onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
                }
        }
        // プレイリストのウィジェットは、上に出す今の曲と、鳴らしているプレイリストの印が変わったとき
        container.appScope.launch {
            nowPlaying
                .map { now -> now?.let { listOf(it.title, it.artist, it.device, it.artworkUrl, it.sourceId, it.playing) } }
                .distinctUntilChanged()
                .collect { updatePlaylistGrid(app) }
        }
    }

    private suspend fun updatePlaylistGrid(context: Context) {
        runCatching { PlaylistGridWidget().updateAll(context) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
    }

    /**
     * アプリが起動した: ランチャーのウィジェット追加画面の見本を OS に渡し（Android 15 以降、版ごとに一度）、
     * アイコンの長押しに出す「最近のプレイリスト」を最近再生した順に合わせる。
     */
    fun onAppStarted(context: Context) {
        val app = context.applicationContext
        val scope = (app as WaveApplication).container.appScope
        scope.launch { WidgetPreview.publish(app) }
        scope.launch { PlaylistShortcuts.refresh(app) }
    }

    /** 置いてあるウィジェットを全部描き直す（大きさの倍率を変えたときなど）。 */
    suspend fun updateAllWidgets(context: Context) {
        runCatching {
            NowPlayingWidget().updateAll(context)
            PlaylistWidget().updateAll(context)
            PlaylistGridWidget().updateAll(context)
            QueueWidget().updateAll(context)
        }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget update failed type=${it::class.simpleName}") }
    }

    /** ログアウトした: 残したプレイリストの一覧を消して描き直す。 */
    fun onLoggedOut(context: Context) {
        WidgetPlaylists.clear(context)
        PlaylistShortcuts.clear(context)
        (context.applicationContext as WaveApplication).container.appScope.launch { updatePlaylistGrid(context) }
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
}
