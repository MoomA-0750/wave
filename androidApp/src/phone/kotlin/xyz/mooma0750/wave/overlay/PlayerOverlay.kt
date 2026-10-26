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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
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
import kotlin.math.abs
import kotlin.math.hypot
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

        /** 切り替えの途中に掴まれた: 動きを終わりまでとばして、すぐ掴めるようにする。 */
        var finishMorph by mutableStateOf(false)

        /** 丸を掴んでいる間、画面の下に消すための × を出す。 */
        var dismissShown by mutableStateOf(false)

        /** 丸が × の上にある（離すと消える）。 */
        var dismissHover by mutableStateOf(false)
    }

    /** 切り替えの動き。帯の上端（画面の座標）と高さ、中身の中心の始まりと終わり（帯の中の座標）。 */
    internal class Morph(
        val top: Int,
        val height: Int,
        val from: IntOffset,
        val to: IntOffset,
    )

    private val foreground = MutableStateFlow(true)

    /** × に乗せて消した。wave を開くまでは出さない。 */
    private val dismissed = MutableStateFlow(false)
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
                    Lifecycle.Event.ON_START -> {
                        foreground.value = true
                        dismissed.value = false
                    }
                    Lifecycle.Event.ON_STOP -> foreground.value = false
                    else -> Unit
                }
            },
        )
        val scope = container.appScope
        scope.launch(Dispatchers.Main) {
            combine(container.settings.playerOverlay, foreground, WidgetHooks.nowPlaying.map { it != null }.distinctUntilChanged(), dismissed) { on, front, hasSong, gone ->
                on && !front && hasSong && !gone
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

    /**
     * 重ねる窓。位置は窓ごと動かす。落ち着いた丸は寄せた側の端から測り（吹き出しが内側へ伸びる）、少し画面の外へはみ出させる。
     * 動かしている間・投げた後の動きの間は左上から測る。
     */
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
                // 端の丸ははみ出させ、投げたときは端の向こうまで行って戻るので、画面の外にも置けるようにする
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                windowAnimations = android.R.style.Animation_Toast
                noMoveAnimation()
            }
        private val dismiss = DismissTarget(app)
        private val motion = MotionScheme.expressive()
        private var startX = 0

        /** 右端から測っているか（右端の丸）。 */
        private var fromEnd = false
        private var startY = 0

        /** このドラッグの起点を取ったか（切り替えの途中に掴んだら、落ち着いてから取る）。 */
        private var armed = false
        private var attached = false

        /** 投げた後の動き。掴んだら止める。 */
        private var fling: Job? = null

        /** 掴む前の位置（× に乗せて消したら、次に出すときはここに戻す）。 */
        private var grabbedAt = IntOffset.Zero

        /** 窓の位置（params）と画面の座標のずれ（上の帯の分など）。掴んだ時に測る。 */
        private var screenOffset = IntOffset.Zero

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
                    onDragEnd = ::dropped,
                )
            val miniTouch =
                OverlayTouch(
                    slop = slop,
                    maxTaps = 1,
                    onTaps = { openApp(app) },
                    onLongPress = null,
                    onDragStart = ::dragStart,
                    onDrag = ::drag,
                    onDragEnd = ::dropped,
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
            params.x = if (ui.mode == Mode.Bubble) -dp(OVERHANG_DP) else x.coerceIn(0, bounds.width())
            params.y = if (y < 0) bounds.height() / 3 else y.coerceIn(0, bounds.height() - dp(BUBBLE_DP))
            // 端の丸は画面の端に重なるので、掴もうとするとジェスチャーナビの「戻る」に取られる。窓の中は端のジェスチャーから外す
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
            }
            // × は丸の下に重ねる（丸を × に乗せたとき、丸の周りに × の縁が見える）ので、先に置く
            dismiss.attach()
            wm.addView(view, params)
            attached = true
        }

        fun detach() {
            fling?.cancel()
            // 切り替えの途中で消えたら、落ち着く先へ置いたことにする
            ui.morph?.let { settle(it, if (ui.mode == Mode.Mini) IntSize(dp(MINI_WIDTH_DP), dp(BUBBLE_DP)) else IntSize(dp(BUBBLE_DP), dp(BUBBLE_DP))) }
            ui.dismissShown = false
            ui.dismissHover = false
            savePlace(app, params.x, params.y)
            attached = false
            registry.currentState = Lifecycle.State.DESTROYED
            runCatching { wm.removeView(view) }
            dismiss.detach()
        }

        private fun relayout() {
            if (attached) runCatching { wm.updateViewLayout(view, params) }
        }

        /** 今の窓の左端（右端から測っていれば左から測り直した値）。 */
        private fun left() = if (fromEnd) screen().width() - view.width - params.x else params.x

        /** 左上から測るのに切り替える。 */
        private fun measureFromLeft() {
            // （gravity の END は向きの印も含むので、値を比べずに覚えておく）
            if (fromEnd) {
                params.x = left()
                params.gravity = Gravity.TOP or Gravity.START
                fromEnd = false
            }
        }

        private fun dragStart() {
            // 投げた後の動きの途中でも、掴んだ所で止める
            fling?.cancel()
            ui.dragging = true
            ui.tooltip = false
            armed = false
            if (ui.morph == null) {
                // 落ち着いていれば、掴んだ時の位置を起点にする（指が動き始めるまでのずれも追いつく）
                arm(0f, 0f)
            } else {
                // 切り替えの途中なら、動きを終わりまでとばして落ち着かせる（起点は落ち着いてから取る）
                ui.finishMorph = true
            }
        }

        /**
         * 起点を取る。切り替えの途中に掴んだときは、落ち着くまで待ってから、その時の指のずれを差し引いて取る
         * （掴んだ時の古い位置を起点にすると、落ち着いた途端に別の所へ飛んだ）。
         */
        private fun arm(
            dx: Float,
            dy: Float,
        ) {
            measureFromLeft()
            grabbedAt = IntOffset(params.x, params.y)
            // 落ち着いている今の、窓の画面上の位置と params の差（× の位置と比べるのに使う）
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            screenOffset = IntOffset(at[0] - params.x, at[1] - params.y)
            startX = params.x - dx.roundToInt()
            startY = params.y - dy.roundToInt()
            armed = true
            relayout()
        }

        private fun drag(
            dx: Float,
            dy: Float,
        ) {
            if (ui.morph != null) return
            if (!armed) arm(dx, dy)
            val bounds = screen()
            val overhang = dp(OVERHANG_DP)
            var x = (startX + dx.roundToInt()).coerceIn(-overhang, bounds.width() - view.width + overhang)
            var y = (startY + dy.roundToInt()).coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
            if (ui.mode == Mode.Bubble) {
                ui.dismissShown = true
                // × の近くに来たら、丸を × に吸い付ける
                val target = dismiss.center()?.let { it - screenOffset }
                val cx = x + view.width / 2
                val cy = y + view.height / 2
                val near = if (ui.dismissHover) dp(DISMISS_LEAVE_DP) else dp(DISMISS_ENTER_DP)
                val hover = target != null && hypot((cx - target.x).toFloat(), (cy - target.y).toFloat()) < near
                if (hover && !ui.dismissHover) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                ui.dismissHover = hover
                if (hover && target != null) {
                    x = target.x - view.width / 2
                    y = target.y - view.height / 2
                }
            }
            params.x = x
            params.y = y
            relayout()
        }

        /** 離した: × の上なら消し、丸は投げた勢いで端へ（ゆっくり内側で離したらミニプレーヤー）、ミニプレーヤーは勢いで滑らせる。 */
        private fun dropped(
            vx: Float,
            vy: Float,
        ) {
            ui.dragging = false
            val hover = ui.dismissHover
            ui.dismissShown = false
            ui.dismissHover = false
            if (ui.morph != null || !armed) return
            if (ui.mode == Mode.Bubble && hover) {
                dismissNow()
                return
            }
            if (ui.mode == Mode.Bubble) bubbleReleased(vx, vy) else miniReleased(vx, vy)
        }

        private fun bubbleReleased(
            vx: Float,
            vy: Float,
        ) {
            val width = screen().width()
            val center = params.x + view.width / 2
            val edge = dp(MINI_EDGE_DP)
            val fast = hypot(vx, vy) > dp(FLING_DP_PER_S)
            if (!fast && center in edge..(width - edge)) {
                // 内側でそっと離した: 離した所を中心に、丸からミニプレーヤーへ広げる（画面からはみ出す分は内側へ寄せる）
                val half = dp(MINI_WIDTH_DP) / 2
                morph(Mode.Mini, ui.atEnd, center.coerceIn(half, (width - half).coerceAtLeast(half)))
                return
            }
            // 投げた向き（横の勢いが弱ければ近い方）の端へ。勢いのまま端の向こうまで行って跳ね返る
            val atEnd = if (abs(vx) > dp(FLING_DP_PER_S)) vx > 0 else center + vx * PROJECTION_S > width / 2
            val overhang = dp(OVERHANG_DP)
            val toX = if (atEnd) width - view.width + overhang else -overhang
            val toY = (params.y + vy * PROJECTION_S).roundToInt().coerceIn(0, (screen().height() - view.height).coerceAtLeast(0))
            ui.atEnd = atEnd
            throwTo(IntOffset(toX, toY), vx, vy) {
                fromEnd = atEnd
                params.gravity = Gravity.TOP or if (atEnd) Gravity.END else Gravity.START
                params.x = -overhang
                relayout()
            }
        }

        private fun miniReleased(
            vx: Float,
            vy: Float,
        ) {
            val bounds = screen()
            val near = dp(EDGE_SNAP_DP)
            val throwPast = dp(MINI_THROW_DP)
            val left = params.x + vx * PROJECTION_S
            val right = left + view.width
            when {
                // 端に寄せたか、端の向こうへ投げた: 丸に戻す
                params.x <= near || left < -throwPast -> toEdge(false)
                params.x + view.width >= bounds.width() - near || right > bounds.width() + throwPast -> toEdge(true)
                else -> {
                    // 勢いのまま滑らせ、画面の端では向こうへ少し行って戻る
                    val toX = left.roundToInt().coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
                    val toY = (params.y + vy * PROJECTION_S).roundToInt().coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
                    throwTo(IntOffset(toX, toY), vx, vy) {}
                }
            }
        }

        /** 投げた勢い（初速）のまま、ばねで行き先へ動かす。勢いが強ければ行き先を越えてから戻る（端なら画面の外まで行って跳ね返る）。 */
        private fun throwTo(
            to: IntOffset,
            vx: Float,
            vy: Float,
            settled: () -> Unit,
        ) {
            fling?.cancel()
            fling =
                app.container.appScope.launch(AndroidUiDispatcher.Main) {
                    val position = Animatable(Offset(params.x.toFloat(), params.y.toFloat()), Offset.VectorConverter)
                    position.animateTo(Offset(to.x.toFloat(), to.y.toFloat()), motion.defaultSpatialSpec(), initialVelocity = Offset(vx, vy)) {
                        params.x = value.x.roundToInt()
                        params.y = value.y.roundToInt()
                        relayout()
                    }
                    settled()
                    savePlace(app, params.x, params.y)
                }
        }

        /** × に乗せて離した: 丸を消す（wave を開くまで出さない）。次に出すときは掴む前の位置に出す。 */
        private fun dismissNow() {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            // 掴む前の高さの、元の端の丸として覚え直す（消すときに [detach] がこの位置を書き残す）
            fromEnd = ui.atEnd
            params.gravity = Gravity.TOP or if (ui.atEnd) Gravity.END else Gravity.START
            params.x = -dp(OVERHANG_DP)
            params.y = grabbedAt.y
            dismissed.value = true
        }

        private fun toEdge(atEnd: Boolean) {
            val half = dp(BUBBLE_DP) / 2
            val overhang = dp(OVERHANG_DP)
            morph(Mode.Bubble, atEnd, if (atEnd) screen().width() - half + overhang else half - overhang)
        }

        /**
         * 丸⇔ミニプレーヤーの切り替え。窓を動かしながら中身の大きさを変えると、窓が1コマ遅れて付いてきて揺れるので、
         * 動く間だけ窓を横いっぱいの帯にして止めておき、その中で中身の位置と大きさを動かす（[OverlayContent]）。
         * 落ち着いたら [morphDone] で窓を中身の大きさに戻す。帯の間（0.5秒ほど）は帯の中のほかのアプリを触れない。
         */
        private fun morph(
            mode: Mode,
            atEnd: Boolean,
            toCenterX: Int,
        ) {
            fling?.cancel()
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
                params.x = -dp(OVERHANG_DP)
            } else {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = (done.to.x - size.width / 2).coerceIn(0, (bounds.width() - size.width).coerceAtLeast(0))
            }
            ui.morph = null
            ui.finishMorph = false
        }
    }

    /** 丸を掴んでいる間、画面の下の真ん中に出す ×。触れない窓（下のアプリの操作を邪魔しない）。 */
    private class DismissTarget(
        private val app: WaveApplication,
    ) : LifecycleOwner,
        SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        private val wm = app.getSystemService(WindowManager::class.java)
        private val view = ComposeView(app)
        private var attached = false

        fun attach() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            view.setContent { DismissContent(ui) }
            val density = app.resources.displayMetrics.density
            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (DISMISS_BOTTOM_DP * density).roundToInt()
                }
            runCatching { wm.addView(view, params) }.onSuccess { attached = true }
        }

        fun detach() {
            registry.currentState = Lifecycle.State.DESTROYED
            if (attached) runCatching { wm.removeView(view) }
            attached = false
        }

        /** × の中心（画面の座標）。まだ置かれていなければ null。 */
        fun center(): IntOffset? {
            if (!attached || view.width == 0) return null
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            return IntOffset(at[0] + view.width / 2, at[1] + view.height / 2)
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

    /** 端の丸を画面の外へはみ出させる幅（影の余白 6dp を含む。丸そのものは 14dp ほど外に出る）。 */
    private const val OVERHANG_DP = 20

    /** これより速く離したら「投げた」とみなす。 */
    private const val FLING_DP_PER_S = 900

    /** 投げた勢いで、どこまで進むとみなすか（秒。速さにこれを掛けた所を行き先にする）。 */
    private const val PROJECTION_S = 0.18f

    /** ミニプレーヤーをこれより端の向こうへ投げたら、丸に戻す。 */
    private const val MINI_THROW_DP = 40

    /** 丸の中心が × からこれより近づいたら吸い付け、吸い付いた後はこれより離れたら外す。 */
    private const val DISMISS_ENTER_DP = 64
    private const val DISMISS_LEAVE_DP = 96

    /** × の下端の位置（画面の下から）。 */
    internal const val DISMISS_BOTTOM_DP = 72

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
