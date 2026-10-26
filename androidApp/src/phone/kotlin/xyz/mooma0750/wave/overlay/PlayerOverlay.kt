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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.flow.collectLatest
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

        /** 切り替えの動きの途中（窓は横いっぱいの帯）。 */
        var morph by mutableStateOf<Morph?>(null)
    }

    /** 切り替えの動き。帯の上端（画面の座標）と高さ、中身の中心の始まりと終わり（帯の中の座標）。 */
    internal class Morph(
        val top: Int,
        val height: Int,
        val from: IntOffset,
        val to: IntOffset,
    )

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
            }.distinctUntilChanged().collectLatest { want ->
                if (!want) {
                    hide()
                    return@collectLatest
                }
                // 設定でオンにすると許可の画面が開き、wave はその時点で裏に回る。許可は後から下りるので、下りるまで見て待つ
                while (!Settings.canDrawOverlays(app)) delay(PERMISSION_POLL_MS)
                show(app)
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
            ).apply {
                windowAnimations = android.R.style.Animation_Toast
                noMoveAnimation()
            }
        private var startX = 0

        /** 右端から測っているか（右端の丸）。 */
        private var fromEnd = false
        private var startY = 0

        /** このドラッグの起点を取ったか（切り替えの途中に掴んだら、落ち着いてから取る）。 */
        private var armed = false
        private var attached = false

        private fun dp(value: Int) = (value * density).roundToInt()

        // アプリの Context の currentWindowMetrics は窓が無いので画面の大きさにならない。画面いっぱいの大きさを使う
        private fun screen() = wm.maximumWindowMetrics.bounds

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
                    onMorphDone = ::morphDone,
                )
            }
            val bounds = screen()
            fromEnd = ui.mode == Mode.Bubble && ui.atEnd
            params.gravity = Gravity.TOP or if (fromEnd) Gravity.END else Gravity.START
            params.x = if (ui.mode == Mode.Bubble) 0 else x.coerceIn(0, bounds.width())
            params.y = if (y < 0) bounds.height() / 3 else y.coerceIn(0, bounds.height() - dp(BUBBLE_DP))
            wm.addView(view, params)
            attached = true
        }

        fun detach() {
            // 切り替えの途中で消えたら、落ち着く先へ置いたことにする
            ui.morph?.let { settle(it, if (ui.mode == Mode.Mini) IntSize(dp(MINI_WIDTH_DP), dp(BUBBLE_DP)) else IntSize(dp(BUBBLE_DP), dp(BUBBLE_DP))) }
            savePlace(app, params.x, params.y)
            attached = false
            registry.currentState = Lifecycle.State.DESTROYED
            runCatching { wm.removeView(view) }
        }

        private fun relayout() {
            if (attached) runCatching { wm.updateViewLayout(view, params) }
        }

        /** 今の窓の左端（右端から測っていれば左から測り直した値）。 */
        private fun left() = if (fromEnd) screen().width() - view.width - params.x else params.x

        /** ドラッグの間は左上から測る（端から測ったままだと、右端の丸を動かす向きが逆になる）。 */
        private fun dragStart() {
            ui.dragging = true
            ui.tooltip = false
            armed = false
            // 落ち着いていれば、掴んだ時の位置を起点にする（指が動き始めるまでのずれも追いつく）
            if (ui.morph == null) arm(0f, 0f)
        }

        /**
         * 起点は、指が動き始めた後の最初の動きで取る。切り替えの途中に掴んだときは、窓が落ち着くまで待ってから、
         * その時の指のずれを差し引いて取る（掴んだ時の古い位置を起点にすると、落ち着いた途端に別の所へ飛んだ）。
         */
        private fun arm(
            dx: Float,
            dy: Float,
        ) {
            // （gravity の END は向きの印も含むので、値を比べずに覚えておく）
            if (fromEnd) {
                params.x = left()
                params.gravity = Gravity.TOP or Gravity.START
                fromEnd = false
            }
            startX = params.x - dx.roundToInt()
            startY = params.y - dy.roundToInt()
            armed = true
        }

        private fun drag(
            dx: Float,
            dy: Float,
        ) {
            if (ui.morph != null) return
            if (!armed) arm(dx, dy)
            val bounds = screen()
            params.x = (startX + dx.roundToInt()).coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
            params.y = (startY + dy.roundToInt()).coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
            relayout()
        }

        /** 丸を離した: 端から十分に内側ならミニプレーヤーにし、そうでなければ近い方の端へ寄せる。 */
        private fun bubbleDropped() {
            ui.dragging = false
            if (ui.morph != null || !armed) return
            val width = screen().width()
            val center = left() + view.width / 2
            val edge = dp(MINI_EDGE_DP)
            if (center in edge..(width - edge)) {
                // 離した所を中心に、丸からミニプレーヤーへ広げる（画面からはみ出す分は内側へ寄せる）
                val half = dp(MINI_WIDTH_DP) / 2
                morph(Mode.Mini, ui.atEnd, center.coerceIn(half, (width - half).coerceAtLeast(half)))
            } else {
                toEdge(center > width / 2)
            }
        }

        /** ミニプレーヤーを離した: 端に寄せていれば丸に戻す。 */
        private fun miniDropped() {
            ui.dragging = false
            if (ui.morph != null || !armed) return
            val width = screen().width()
            val near = dp(EDGE_SNAP_DP)
            when {
                params.x <= near -> toEdge(false)
                params.x + view.width >= width - near -> toEdge(true)
                else -> savePlace(app, params.x, params.y)
            }
        }

        private fun toEdge(atEnd: Boolean) {
            val half = dp(BUBBLE_DP) / 2
            morph(Mode.Bubble, atEnd, if (atEnd) screen().width() - half else half)
        }

        /**
         * 丸⇔ミニプレーヤーの切り替えと、端へ寄せる動き。窓を動かしながら中身の大きさを変えると、窓が1コマ遅れて付いてきて揺れるので、
         * 動く間だけ窓を横いっぱいの帯にして止めておき、その中で中身の位置と大きさを動かす（[OverlayContent]）。
         * 落ち着いたら [morphDone] で窓を中身の大きさに戻す。帯の間（0.5秒ほど）は帯の中のほかのアプリを触れない。
         */
        private fun morph(
            mode: Mode,
            atEnd: Boolean,
            toCenterX: Int,
        ) {
            val bounds = screen()
            val strip = dp(MORPH_STRIP_DP).coerceAtMost(bounds.height())
            val from = IntOffset(left() + view.width / 2, params.y + view.height / 2)
            val top = (from.y - strip / 2).coerceIn(0, bounds.height() - strip)
            ui.morph = Morph(top = top, height = strip, from = IntOffset(from.x, from.y - top), to = IntOffset(toCenterX, strip / 2))
            ui.mode = mode
            ui.atEnd = atEnd
            fromEnd = false
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = top
            params.width = bounds.width()
            params.height = strip
            relayout()
        }

        /** 動き終えた: 窓を中身の大きさに戻し、落ち着いた所に置く（中身を帯の中に置くのをやめるのと同じコマで）。 */
        private fun morphDone(
            done: Morph,
            size: IntSize,
        ) {
            if (ui.morph !== done) return
            settle(done, size)
            relayout()
            savePlace(app, params.x, params.y)
        }

        private fun settle(
            done: Morph,
            size: IntSize,
        ) {
            val bounds = screen()
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.y = (done.top + done.to.y - size.height / 2).coerceIn(0, (bounds.height() - size.height).coerceAtLeast(0))
            if (ui.mode == Mode.Bubble) {
                fromEnd = ui.atEnd
                params.gravity = Gravity.TOP or if (ui.atEnd) Gravity.END else Gravity.START
                params.x = 0
            } else {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = (done.to.x - size.width / 2).coerceIn(0, (bounds.width() - size.width).coerceAtLeast(0))
            }
            ui.morph = null
        }
    }

    /**
     * 窓の位置を変えたとき、OS が古い位置から新しい位置へ窓を滑らせるのを止める（非公開の印 PRIVATE_FLAG_NO_MOVE_ANIMATION）。
     * 滑らせると、ドラッグでは指より遅れて揺れ、切り替えの終わりでは一度帯の端に出てから滑って戻って見えた。
     * 読めない OS では何もしない。
     */
    private fun WindowManager.LayoutParams.noMoveAnimation() {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(this, field.getInt(this) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }.onFailure { Log.w(WaveApplication.LOG_TAG, "overlay no-move-animation unavailable type=${it::class.simpleName}") }
    }

    private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

    private const val PREFS = "player_overlay"
    private const val KEY_MODE = "mode"
    private const val KEY_AT_END = "at_end"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val TOOLTIP_MS = 4_000L
    private const val LYRIC_TICK_MS = 250L
    private const val PERMISSION_POLL_MS = 1_000L

    /** 切り替えの間の帯の高さ（ミニプレーヤーが入る高さ）。 */
    private const val MORPH_STRIP_DP = 280

    /** 丸（影の余白を含む）。 */
    internal const val BUBBLE_DP = 68

    internal const val MINI_WIDTH_DP = 300

    /** 丸の中心が端からこれより内側で離されたら、ミニプレーヤーにする。 */
    private const val MINI_EDGE_DP = 110

    /** ミニプレーヤーの端がこれより端に寄ったら、丸に戻す。 */
    private const val EDGE_SNAP_DP = 24
}
