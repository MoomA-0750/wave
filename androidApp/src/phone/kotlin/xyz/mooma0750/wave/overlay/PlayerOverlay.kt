package xyz.mooma0750.wave.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.lyrics.LyricLine
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.lyrics.LyricsTiming
import xyz.mooma0750.wave.playback.NowPlayingSnapshot
import xyz.mooma0750.wave.playback.playFromButton
import xyz.mooma0750.wave.widget.WidgetHooks
import xyz.mooma0750.wave.widget.openAppIntent
import kotlin.math.roundToInt

/**
 * ほかのアプリの上に重ねる再生の丸（iPhone の音楽の小窓と同じ役目）。設定でオンにし、重ねて表示する許可があり、
 * wave が裏にいて曲があるときだけ出す。
 * 端にいる間はジャケットの丸: タップで再生・一時停止、2回で次の曲、3回で前の曲、長押しで wave の再生画面を開く。
 * 曲が変わったら、丸の横に曲名とアーティストの吹き出しを少し出す。
 * 画面の内側へ引き出すとミニプレーヤー（時刻付きの歌詞があれば今の行も出す）。ミニプレーヤーを端へ寄せると丸に戻る。
 */
object PlayerOverlay {
    enum class Mode { Bubble, Mini }

    /** 画面の中身（Compose が読む）。 */
    internal class UiState {
        var mode by mutableStateOf(Mode.Bubble)
        var atEnd by mutableStateOf(true)
        var now by mutableStateOf<NowPlayingSnapshot?>(null)
        var lyric by mutableStateOf<String?>(null)
        var tooltip by mutableStateOf(false)
        var dragging by mutableStateOf(false)
    }

    private val foreground = MutableStateFlow(true)
    private var started = false
    private var window: OverlayWindow? = null
    private var lines: List<LyricLine>? = null
    private var ticker: Job? = null
    internal val ui = UiState()

    /** 再生サービスが最初に曲を知らせたときに呼ぶ（Main）。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext as WaveApplication
        val container = app.container
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        foreground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground.value = true
                    Lifecycle.Event.ON_STOP -> foreground.value = false
                    else -> Unit
                }
            },
        )
        val scope = container.appScope
        scope.launch(Dispatchers.Main) {
            combine(container.settings.playerOverlay, foreground, WidgetHooks.nowPlaying.map { it != null }.distinctUntilChanged()) { on, front, hasSong ->
                on && !front && hasSong
            }.distinctUntilChanged().collect { want ->
                if (want && Settings.canDrawOverlays(app)) show(app) else hide()
            }
        }
        // 曲が変わったら、吹き出しを出し、歌詞を読み直す
        scope.launch(Dispatchers.Main) {
            var previous: String? = null
            WidgetHooks.nowPlaying.collect { now ->
                ui.now = now
                val id = now?.videoId ?: now?.title
                if (id == previous) return@collect
                val changed = previous != null && id != null
                previous = id
                lines = null
                ui.lyric = null
                if (changed && window != null && ui.mode == Mode.Bubble) showTooltip(app)
                val song = now?.song ?: return@collect
                val result = withContext(Dispatchers.IO) { runCatching { container.lyrics.lyrics(song) }.getOrNull() }
                if (WidgetHooks.nowPlaying.value?.videoId == song.videoId) {
                    lines = ((result as? LyricsRepository.Result.Found)?.lyrics as? Lyrics.Synced)?.lines?.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    private var tooltipJob: Job? = null

    private fun showTooltip(app: WaveApplication) {
        tooltipJob?.cancel()
        tooltipJob =
            app.container.appScope.launch(Dispatchers.Main) {
                ui.tooltip = true
                delay(TOOLTIP_MS)
                ui.tooltip = false
            }
    }

    private fun show(app: WaveApplication) {
        if (window != null) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ui.mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(Mode.Bubble)
        ui.atEnd = prefs.getBoolean(KEY_AT_END, true)
        ui.tooltip = false
        window = OverlayWindow(app).also { it.attach(prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, -1)) }
        // ミニプレーヤーの歌詞を再生位置に合わせて送る
        ticker =
            app.container.appScope.launch(Dispatchers.Main) {
                while (true) {
                    val current = lines
                    ui.lyric =
                        if (ui.mode == Mode.Mini && current != null) {
                            val position = runCatching { app.container.player.controller().currentPosition }.getOrNull()
                            position?.let { LyricsTiming.currentAndNext(current, it).first }
                        } else {
                            null
                        }
                    delay(LYRIC_TICK_MS)
                }
            }
        Log.i(WaveApplication.LOG_TAG, "player overlay shown mode=${ui.mode}")
    }

    private fun hide() {
        ticker?.cancel()
        ticker = null
        tooltipJob?.cancel()
        ui.tooltip = false
        window?.detach()
        window = null
    }

    private fun savePlace(
        context: Context,
        x: Int,
        y: Int,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, ui.mode.name)
            .putBoolean(KEY_AT_END, ui.atEnd)
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    private fun withPlayer(
        app: WaveApplication,
        action: (androidx.media3.session.MediaController) -> Unit,
    ) {
        app.container.appScope.launch(Dispatchers.Main) {
            runCatching { action(app.container.player.controller()) }
                .onFailure { Log.w(WaveApplication.LOG_TAG, "overlay control failed type=${it::class.simpleName}") }
        }
    }

    internal fun togglePlay(app: WaveApplication) = withPlayer(app) { if (it.playWhenReady) it.pause() else it.playFromButton() }

    internal fun next(app: WaveApplication) = withPlayer(app) { it.seekToNext() }

    internal fun previous(app: WaveApplication) = withPlayer(app) { it.seekToPrevious() }

    internal fun openApp(app: WaveApplication) {
        runCatching { app.startActivity(openAppIntent(app).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true)) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "overlay open failed type=${it::class.simpleName}") }
    }

    /** 重ねる窓。位置は窓ごと動かす。端の丸は寄せた側の端から測り（吹き出しが内側へ伸びる）、ミニプレーヤーは左から測る。 */
    private class OverlayWindow(
        private val app: WaveApplication,
    ) : LifecycleOwner,
        SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        private val wm = app.getSystemService(WindowManager::class.java)
        private val density = app.resources.displayMetrics.density
        private val view = ComposeView(app)
        private val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { windowAnimations = android.R.style.Animation_Toast }
        private var startX = 0
        private var startY = 0

        private fun dp(value: Int) = (value * density).roundToInt()

        private fun screen() = wm.currentWindowMetrics.bounds

        fun attach(
            x: Int,
            y: Int,
        ) {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            val slop = ViewConfiguration.get(app).scaledTouchSlop
            val bubbleTouch =
                OverlayTouch(
                    slop = slop,
                    maxTaps = 3,
                    onTaps = { count ->
                        when (count) {
                            1 -> togglePlay(app)
                            2 -> next(app)
                            else -> previous(app)
                        }
                    },
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openApp(app)
                    },
                    onDragStart = ::dragStart,
                    onDrag = ::drag,
                    onDragEnd = ::bubbleDropped,
                )
            val miniTouch =
                OverlayTouch(
                    slop = slop,
                    maxTaps = 1,
                    onTaps = { openApp(app) },
                    onLongPress = null,
                    onDragStart = ::dragStart,
                    onDrag = ::drag,
                    onDragEnd = ::miniDropped,
                )
            view.setContent {
                OverlayContent(
                    ui,
                    bubbleTouch = bubbleTouch::onTouch,
                    miniTouch = miniTouch::onTouch,
                    onPrevious = { previous(app) },
                    onTogglePlay = { togglePlay(app) },
                    onNext = { next(app) },
                )
            }
            val bounds = screen()
            params.gravity = Gravity.TOP or if (ui.mode == Mode.Bubble && ui.atEnd) Gravity.END else Gravity.START
            params.x = if (ui.mode == Mode.Bubble) 0 else x.coerceIn(0, bounds.width())
            params.y = if (y < 0) bounds.height() / 3 else y.coerceIn(0, bounds.height() - dp(BUBBLE_DP))
            wm.addView(view, params)
        }

        fun detach() {
            registry.currentState = Lifecycle.State.DESTROYED
            runCatching { wm.removeView(view) }
        }

        /** ドラッグの間は左上から測る（端から測ったままだと、右端の丸を動かす向きが逆になる）。 */
        private fun dragStart() {
            ui.dragging = true
            ui.tooltip = false
            val width = screen().width()
            if (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.END) {
                params.x = width - view.width - params.x
                params.gravity = Gravity.TOP or Gravity.START
            }
            startX = params.x
            startY = params.y
            wm.updateViewLayout(view, params)
        }

        private fun drag(
            dx: Float,
            dy: Float,
        ) {
            val bounds = screen()
            params.x = (startX + dx.roundToInt()).coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
            params.y = (startY + dy.roundToInt()).coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
            wm.updateViewLayout(view, params)
        }

        /** 丸を離した: 端から十分に内側ならミニプレーヤーにし、そうでなければ近い方の端へ寄せる。 */
        private fun bubbleDropped() {
            ui.dragging = false
            val width = screen().width()
            val center = params.x + view.width / 2
            val edge = dp(MINI_EDGE_DP)
            if (center in edge..(width - edge)) {
                ui.mode = Mode.Mini
                params.x = (center - dp(MINI_WIDTH_DP) / 2).coerceIn(0, (width - dp(MINI_WIDTH_DP)).coerceAtLeast(0))
            } else {
                snapToEdge(center > width / 2)
            }
            wm.updateViewLayout(view, params)
            savePlace(app, params.x, params.y)
        }

        /** ミニプレーヤーを離した: 端に寄せていれば丸に戻す。 */
        private fun miniDropped() {
            ui.dragging = false
            val width = screen().width()
            val near = dp(EDGE_SNAP_DP)
            when {
                params.x <= near -> snapToEdge(false)
                params.x + view.width >= width - near -> snapToEdge(true)
            }
            wm.updateViewLayout(view, params)
            savePlace(app, params.x, params.y)
        }

        private fun snapToEdge(atEnd: Boolean) {
            ui.mode = Mode.Bubble
            ui.atEnd = atEnd
            params.gravity = Gravity.TOP or if (atEnd) Gravity.END else Gravity.START
            params.x = 0
        }
    }

    private const val PREFS = "player_overlay"
    private const val KEY_MODE = "mode"
    private const val KEY_AT_END = "at_end"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val TOOLTIP_MS = 4_000L
    private const val LYRIC_TICK_MS = 250L

    /** 丸（影の余白を含む）。 */
    internal const val BUBBLE_DP = 68

    internal const val MINI_WIDTH_DP = 300

    /** 丸の中心が端からこれより内側で離されたら、ミニプレーヤーにする。 */
    private const val MINI_EDGE_DP = 110

    /** ミニプレーヤーの端がこれより端に寄ったら、丸に戻す。 */
    private const val EDGE_SNAP_DP = 24
}
