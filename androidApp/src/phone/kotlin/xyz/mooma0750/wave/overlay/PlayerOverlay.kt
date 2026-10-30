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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.toOffset
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

        /**
         * 掴んでいる間と、離した後の動きの間は、窓を画面いっぱいにして止め、中身をその中で動かす（窓を動かすと、窓が1コマ遅れて付いてきて揺れる）。
         * 中身の中心は [finger] と [grab] から決める。
         */
        var stage by mutableStateOf(false)

        /** 指の位置（params の座標。離した後は動きの行き先へ向かう点）。 */
        var finger by mutableStateOf(Offset.Zero)

        /** 掴んだ所が中身のどこか（左上 0・右下 1 の割合）。丸とミニプレーヤーが入れ替わっても、指の下は同じ割合の所になる。 */
        var grab by mutableStateOf(Offset(0.5f, 0.5f))

        /** 今の中身の大きさと、行き着く大きさ（Compose が置くたびに書く。窓の側が読む）。 */
        @Volatile var shownSizeNow = IntSize.Zero

        @Volatile var targetSizeNow = IntSize.Zero

        fun centerFor(
            finger: Offset,
            width: Int,
            height: Int,
        ) = Offset(finger.x + (0.5f - grab.x) * width, finger.y + (0.5f - grab.y) * height)

        /** 丸を掴んでいる間、画面の下に消すための × を出す。 */
        var dismissShown by mutableStateOf(false)

        /** 丸が × の上にある（離すと消える）。 */
        var dismissHover by mutableStateOf(false)
    }


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

    /**
     * 評価のボタン（ウィジェットと同じ）。＋なら高く評価し、✓ならアプリを開かずにプレイリストに追加する画面（透明な画面のダイアログ）を出す。
     */
    internal fun like(
        app: WaveApplication,
        now: NowPlayingSnapshot?,
    ) {
        val song = now?.song
        if (now?.saved == true && song != null) {
            runCatching {
                app.startActivity(
                    android.content.Intent(app, xyz.mooma0750.wave.library.AddToPlaylistActivity::class.java)
                        .putExtra(xyz.mooma0750.wave.library.LikedNotice.EXTRA_SONG, xyz.mooma0750.wave.playback.SongJson.encode(song))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.onFailure { Log.w(WaveApplication.LOG_TAG, "overlay add to playlist failed type=${it::class.simpleName}") }
            return
        }
        val videoId = now?.videoId ?: return
        app.container.appScope.launch(Dispatchers.Main) {
            runCatching { app.container.player.setLiked(videoId, true) }
                .onFailure { Log.w(WaveApplication.LOG_TAG, "overlay like failed type=${it::class.simpleName}") }
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
     * 重ねる窓。落ち着いている間は中身の大きさで、丸は寄せた側の端から測り（吹き出しが内側へ伸びる）、少し画面の外へはみ出させる。
     * 掴んでいる間と離した後の動きの間は、窓を画面いっぱいにして止め、中身の位置・大きさだけを動かす（[UiState.stage]）。
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
                // 端の丸ははみ出させるので、画面の外にも置けるようにする
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                windowAnimations = android.R.style.Animation_Toast
                noMoveAnimation()
            }
        private val dismiss = DismissTarget(app)
        private val motion = MotionScheme.expressive()

        /** 右端から測っているか（右端の丸）。 */
        private var fromEnd = false
        private var attached = false

        /** 指を置いた所（画面の座標）。 */
        private var down = Offset.Zero

        /** 離した後の動き。掴んだら止める。 */
        private var fling: Job? = null

        /** 入れ替えたときに、指の下を中身の真ん中へ寄せる動き。 */
        private var recenter: Job? = null

        /** 掴む前の丸の高さ（× に乗せて消したら、次に出すときはここに戻す）。 */
        private var grabbedY = 0

        /** 窓の位置（params）と画面の座標のずれ（上の帯の分など）。落ち着いている時に測る。 */
        private var screenOffset = IntOffset.Zero

        private fun dp(value: Int) = (value * density).roundToInt()

        // アプリの Context の currentWindowMetrics は窓が無いので画面の大きさにならない。画面いっぱいの大きさを使う
        private fun screen() = wm.maximumWindowMetrics.bounds

        /** params の座標で使える高さ（上の帯の分だけ窓の原点が下にある）。 */
        private fun usableHeight() = screen().height() - screenOffset.y

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
                    // 長押しを待つ間は、指のずれを 3 倍まで許す
                    longPressSlop = slop * 3,
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
                    onLike = { like(app, ui.now) },
                    onSizeSettled = {},
                )
            }
            val bounds = screen()
            ui.stage = false
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
            // 動きの途中で消えたら、今の所で落ち着いたことにする
            if (ui.stage) settleNow()
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

        /** 落ち着いている窓の左端（右端から測っていれば左から測り直した値）。 */
        private fun left() = if (fromEnd) screen().width() - view.width - params.x else params.x

        /** 窓を画面いっぱいにし、中身を今と同じ所に置く。 */
        private fun enterStage() {
            if (ui.stage) return
            // 落ち着いている今の、窓の画面上の位置と params の差（指の位置・× の位置を params の座標にするのに使う）
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            val leftNow = left()
            screenOffset = IntOffset(at[0] - leftNow, at[1] - params.y)
            grabbedY = params.y
            ui.grab = Offset(0.5f, 0.5f)
            ui.finger = Offset(leftNow + view.width / 2f, params.y + view.height / 2f)
            val bounds = screen()
            fromEnd = false
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            params.width = bounds.width()
            params.height = bounds.height()
            ui.stage = true
            relayout()
        }

        private fun dragStart(
            downX: Float,
            downY: Float,
        ) {
            // 離した後の動きの途中でも、掴んだ所で止める
            fling?.cancel()
            recenter?.cancel()
            ui.dragging = true
            ui.tooltip = false
            enterStage()
            down = Offset(downX, downY)
            // 中身が跳ばないよう、指の下が中身のどこかを測り直す
            val size = ui.shownSizeNow.takeIf { it != IntSize.Zero } ?: IntSize(view.width, view.height)
            val center = ui.centerFor(ui.finger, size.width, size.height)
            val finger = down - screenOffset.toOffset()
            ui.grab = Offset(0.5f - (center.x - finger.x) / size.width, 0.5f - (center.y - finger.y) / size.height)
            ui.finger = finger
        }

        private fun drag(
            dx: Float,
            dy: Float,
        ) {
            if (!ui.stage) return
            var finger = down + Offset(dx, dy) - screenOffset.toOffset()
            val width = screen().width()
            val bubble = dp(BUBBLE_DP)
            val miniWidth = dp(MINI_WIDTH_DP)
            val miniHeight = ui.targetSizeNow.height.takeIf { ui.mode == Mode.Mini && it > 0 } ?: dp(MINI_HEIGHT_GUESS_DP)
            ui.dismissShown = true
            val target = dismiss.center()?.let { (it - screenOffset).toOffset() }
            val nearDismiss = target != null && (finger - target).getDistance() < dp(DISMISS_ZONE_DP)
            // 掴んだまま入れ替える: × の近くは丸。ミニプレーヤーは端に当たったら丸、丸は端から十分に離れたらミニプレーヤー
            val mini = ui.centerFor(finger, miniWidth, miniHeight)
            val margin = dp(MINI_OPEN_MARGIN_DP)
            val mode =
                when {
                    nearDismiss -> Mode.Bubble
                    ui.mode == Mode.Mini -> if (mini.x - miniWidth / 2 < 0 || mini.x + miniWidth / 2 > width) Mode.Bubble else Mode.Mini
                    else -> if (mini.x - miniWidth / 2 >= margin && mini.x + miniWidth / 2 <= width - margin) Mode.Mini else Mode.Bubble
                }
            if (mode != ui.mode) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                ui.mode = mode
                // 入れ替えた方は、指を真ん中にして持つ（ミニプレーヤーの上の段を掴んだまま丸にすると、指が丸の端に寄っていた）
                recenter?.cancel()
                recenter =
                    app.container.appScope.launch(AndroidUiDispatcher.Main) {
                        Animatable(ui.grab, Offset.VectorConverter).animateTo(Offset(0.5f, 0.5f), motion.defaultSpatialSpec()) { ui.grab = value }
                    }
            }
            if (mode == Mode.Bubble) {
                ui.atEnd = ui.centerFor(finger, bubble, bubble).x > width / 2
                // × の近くに来たら、丸を × に吸い付ける
                val center = ui.centerFor(finger, bubble, bubble)
                val near = if (ui.dismissHover) dp(DISMISS_LEAVE_DP) else dp(DISMISS_ENTER_DP)
                val hover = target != null && (center - target).getDistance() < near
                if (hover && !ui.dismissHover) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                ui.dismissHover = hover
                if (hover && target != null) finger += target - center
            } else {
                ui.dismissHover = false
            }
            ui.finger = finger
        }

        /** 離した: × の上なら消す。丸は近い方（強く投げたら投げた向き）の端へ、ミニプレーヤーは勢いのまま滑らせる。 */
        private fun dropped(
            vx: Float,
            vy: Float,
        ) {
            ui.dragging = false
            val hover = ui.dismissHover
            ui.dismissShown = false
            ui.dismissHover = false
            recenter?.cancel()
            if (!ui.stage) return
            if (ui.mode == Mode.Bubble && hover) {
                dismissNow()
                return
            }
            // ここからは中身の中心を動かす
            val size = ui.shownSizeNow
            val center = ui.centerFor(ui.finger, size.width, size.height)
            ui.grab = Offset(0.5f, 0.5f)
            ui.finger = center
            val width = screen().width()
            val height = usableHeight()
            val target =
                if (ui.mode == Mode.Bubble) {
                    val atEnd = if (abs(vx) > dp(FLING_EDGE_DP_PER_S)) vx > 0 else center.x > width / 2
                    ui.atEnd = atEnd
                    val half = dp(BUBBLE_DP) / 2f
                    val overhang = dp(OVERHANG_DP)
                    Offset(
                        if (atEnd) width + overhang - half else half - overhang,
                        (center.y + vy * PROJECTION_S).coerceIn(half, height - half),
                    )
                } else {
                    val goal = ui.targetSizeNow.takeIf { it != IntSize.Zero } ?: size
                    val halfW = goal.width / 2f
                    val halfH = goal.height / 2f
                    Offset(
                        (center.x + vx * PROJECTION_S).coerceIn(halfW, (width - halfW).coerceAtLeast(halfW)),
                        (center.y + vy * PROJECTION_S).coerceIn(halfH, (height - halfH).coerceAtLeast(halfH)),
                    )
                }
            // 離した勢い（初速）のまま、ばねで行き先へ。勢いがあれば行き先を越え、画面の端なら外まで行って戻る
            fling =
                app.container.appScope.launch(AndroidUiDispatcher.Main) {
                    val position = Animatable(center, Offset.VectorConverter)
                    position.animateTo(target, motion.defaultSpatialSpec(), initialVelocity = Offset(vx, vy)) { ui.finger = value }
                    // 丸とミニプレーヤーの大きさの入れ替えが終わるのを待ってから、窓を中身の大きさに戻す
                    repeat(SETTLE_WAIT_FRAMES) {
                        if (ui.shownSizeNow == ui.targetSizeNow) return@repeat
                        withFrameNanos {}
                    }
                    settleNow()
                    relayout()
                    savePlace(app, params.x, params.y)
                }
        }

        /** 窓を中身の大きさに戻し、中身の今の所に置く（中身を画面いっぱいの窓の中に置くのをやめるのと同じコマで）。 */
        private fun settleNow() {
            val size = ui.targetSizeNow.takeIf { it != IntSize.Zero } ?: ui.shownSizeNow
            val center = ui.centerFor(ui.finger, size.width, size.height)
            val width = screen().width()
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.y = (center.y - size.height / 2f).roundToInt().coerceIn(0, (usableHeight() - size.height).coerceAtLeast(0))
            if (ui.mode == Mode.Bubble) {
                ui.atEnd = center.x > width / 2
                fromEnd = ui.atEnd
                params.gravity = Gravity.TOP or if (ui.atEnd) Gravity.END else Gravity.START
                params.x = -dp(OVERHANG_DP)
            } else {
                fromEnd = false
                params.gravity = Gravity.TOP or Gravity.START
                params.x = (center.x - size.width / 2f).roundToInt().coerceIn(0, (width - size.width).coerceAtLeast(0))
            }
            ui.stage = false
        }

        /** × に乗せて離した: 丸を消す（wave を開くまで出さない）。次に出すときは掴む前の高さの、元の端の丸にする。 */
        private fun dismissNow() {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            fling?.cancel()
            fromEnd = ui.atEnd
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or if (ui.atEnd) Gravity.END else Gravity.START
            params.x = -dp(OVERHANG_DP)
            params.y = grabbedY
            ui.stage = false
            dismissed.value = true
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

    /** 丸をこれより速く横へ投げたら、近い方ではなく投げた向きの端へ寄せる。 */
    private const val FLING_EDGE_DP_PER_S = 1500

    /** 離した勢いで、どこまで進むとみなすか（秒。速さにこれを掛けた所を行き先にする）。大きいと、真ん中に置きたいのに端まで行く */
    private const val PROJECTION_S = 0.08f

    /** 丸からミニプレーヤーに開くのは、開いたミニプレーヤーの両端が画面の端からこれだけ内側に収まるとき（閉じるのは端に当たったとき。間を空けて行き来しないように）。 */
    private const val MINI_OPEN_MARGIN_DP = 24

    /** ミニプレーヤーの高さの見込み（まだ測っていないとき）。 */
    private const val MINI_HEIGHT_GUESS_DP = 150

    /** 指が × からこれより近いと、ミニプレーヤーも丸にする（× に乗せられるように）。 */
    private const val DISMISS_ZONE_DP = 140

    /** 離した後、大きさの入れ替えが終わるのを待つ最大のコマ数。 */
    private const val SETTLE_WAIT_FRAMES = 90

    /** 丸の中心が × からこれより近づいたら吸い付け、吸い付いた後はこれより離れたら外す。 */
    private const val DISMISS_ENTER_DP = 64
    private const val DISMISS_LEAVE_DP = 96

    /** × の下端の位置（画面の下から）。 */
    internal const val DISMISS_BOTTOM_DP = 72

    /** 丸（影の余白を含む）。 */
    internal const val BUBBLE_DP = 68

    internal const val MINI_WIDTH_DP = 300

}
