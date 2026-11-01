package xyz.mooma0750.wave.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import android.view.WindowInsetsController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.ui.IconAction
import xyz.mooma0750.wave.ui.NoticeHost

/** デスクトップと同じ並びにするときに、アプリの側から渡す部品。 */
class DesktopParts(
    /** 上の帯の中央の検索の欄。 */
    val searchField: @Composable (Modifier) -> Unit,
    /** 上の帯の右端（アカウント）。 */
    val account: @Composable () -> Unit,
    /** 左の欄のマイライブラリ。 */
    val library: @Composable () -> Unit,
    /** 右の欄のキュー。 */
    val queue: @Composable () -> Unit,
    /** 右の欄の Claude。 */
    val ai: @Composable () -> Unit,
    /** 下の再生の帯。 */
    val playerBar: @Composable (DesktopBar) -> Unit,
    /** デスクトップと同じ並びになった・やめた（中央の検索のページが自分の欄を出すかなどを決める）。 */
    val onActive: (Boolean) -> Unit,
)

/** 右の欄に出すもの（Mac・Windows と同じく、再生中・キュー・Claude を切り替える）。 */
enum class SidePane { NowPlaying, Queue, Ai }

/** 下の再生の帯のボタンが映す右の欄の状態と、押したときの動き。 */
class DesktopBar(
    val queueShown: Boolean,
    val onQueue: () -> Unit,
    /** 右の欄に再生中を出す（しまってあれば戻す）。 */
    val onShowPanel: () -> Unit,
)

/**
 * 縦横とも大きい画面の並び（Mac・Windows と同じ）: 上に戻る・ホーム・検索の欄・Claude・アカウント、
 * 左にマイライブラリ、中央にページ、右に再生中・キュー・Claude の欄（しまえる）、下に再生の操作の帯。
 * 下のタブは出さない（ホームと検索は上の帯、ライブラリは左の欄、Claude は右の欄から開く）。
 */
@Composable
internal fun DesktopShell(
    nav: AppNavigator,
    parts: DesktopParts,
    hasPlayback: Boolean,
    panelHidden: Boolean,
    setPanelHidden: (Boolean) -> Unit,
    openPlayer: () -> Unit,
    nowPlaying: @Composable (onClose: () -> Unit, inPanel: Boolean, onHidePanel: (() -> Unit)?) -> Unit,
    content: @Composable () -> Unit,
) {
    // 下のタブが無いので、ライブラリ・Claude のタブにいたらホームへ移す（奥の画面は残す。狭い画面に戻れば元のタブを選び直せる）
    LaunchedEffect(nav.tab) { if (nav.tab == Tab.Library || nav.tab == Tab.Ai) nav.show(Tab.Home) }
    val onActive by rememberUpdatedState(parts.onActive)
    DisposableEffect(Unit) {
        onActive(true)
        onDispose { onActive(false) }
    }
    var side by rememberSaveable { mutableStateOf(SidePane.NowPlaying) }
    // 再生中の欄は何か鳴っているときだけ。キュー・Claude は鳴っていなくても出せる
    val panelShown = !panelHidden && (side != SidePane.NowPlaying || hasPlayback)
    // 出ているものをもう一度押すと、キュー・Claude は再生中に戻し、再生中なら欄をしまう（Windows と同じ）
    val toggleSide: (SidePane) -> Unit = { pane ->
        if (panelShown && side == pane) {
            if (pane == SidePane.NowPlaying) setPanelHidden(true) else side = SidePane.NowPlaying
        } else {
            side = pane
            setPanelHidden(false)
        }
    }
    // 上の帯の検索の欄は中央のページと別の所にあるので、ほかを操作しても欄に残り続け、キーボードが閉じられない。
    // 欄の外を押したら欄を離す（押した操作そのものは邪魔しない）
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rootBounds by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onGloballyPositioned { rootBounds = it }
            .pointerInput(searchFocused) {
                if (!searchFocused) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val field = searchBounds
                    val root = rootBounds
                    val inField =
                        field != null && root != null && field.isAttached && root.isAttached &&
                            field.localPositionOf(root, down.position).let { it.x in 0f..field.size.width.toFloat() && it.y in 0f..field.size.height.toFloat() }
                    if (!inField) focusManager.clearFocus()
                }
            },
    ) {
        TopBar(
            nav,
            parts,
            aiShown = panelShown && side == SidePane.Ai,
            searchModifier = Modifier.onGloballyPositioned { searchBounds = it }.onFocusChanged { searchFocused = it.hasFocus },
        ) { toggleSide(SidePane.Ai) }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(WindowInsets.statusBars)
                .consumeWindowInsets(WindowInsets.captionBar)
                .consumeWindowInsets(WindowInsets.navigationBars)
                .padding(horizontal = PANE_GAP),
            horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
        ) {
            Pane(Modifier.width(LIBRARY_WIDTH)) { parts.library() }
            Pane(Modifier.weight(1f)) {
                content()
                NoticeHost(Modifier.align(Alignment.BottomCenter))
            }
            AnimatedVisibility(
                visible = panelShown,
                enter = expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                Pane(Modifier.width(SIDE_WIDTH)) {
                    when (side) {
                        SidePane.NowPlaying -> nowPlaying(openPlayer, true) { setPanelHidden(true) }
                        SidePane.Queue -> parts.queue()
                        SidePane.Ai -> parts.ai()
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
            parts.playerBar(
                DesktopBar(
                    queueShown = panelShown && side == SidePane.Queue,
                    onQueue = { toggleSide(SidePane.Queue) },
                    onShowPanel = {
                        side = SidePane.NowPlaying
                        setPanelHidden(false)
                    },
                ),
            )
        }
    }
}

/** 上の帯: 左に戻る、中央（窓の左右の中央）にホーム・検索の欄・Claude、右にアカウント。 */
@Composable
private fun TopBar(
    nav: AppNavigator,
    parts: DesktopParts,
    aiShown: Boolean,
    searchModifier: Modifier,
    onAi: () -> Unit,
) {
    val caption = captionSpace()
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (caption == null) {
                    // OS の帯があればその下に出す
                    Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar).only(WindowInsetsSides.Top)).height(TOP_BAR_HEIGHT)
                } else {
                    Modifier.height(maxOf(TOP_BAR_HEIGHT, caption.height)).padding(start = caption.start, end = caption.end)
                },
            ).padding(horizontal = 8.dp),
    ) {
        // 窓の帯に載せたときは、押せるものだけを OS の窓の移動から外す（空いた所は掴んで窓を動かせる）
        if (nav.canGoBack) IconAction(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), nav::back, Modifier.align(Alignment.CenterStart).systemGestureExclusion())
        Row(Modifier.align(Alignment.Center).systemGestureExclusion(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(
                if (nav.tab == Tab.Home && nav.current == null) R.drawable.ic_home else R.drawable.ic_home_outline,
                stringResource(R.string.nav_home),
                nav::goHome,
            )
            parts.searchField(searchModifier.widthIn(max = SEARCH_WIDTH).width(SEARCH_WIDTH))
            // Claude は検索の欄の右（Mac・Windows と同じ）。右の欄に出す
            IconAction(R.drawable.ic_ai, stringResource(R.string.ai_title), onAi, active = aiShown)
        }
        Box(Modifier.align(Alignment.CenterEnd).systemGestureExclusion()) { parts.account() }
    }
}

/** 窓の帯（デスクトップの窓のタイトルの帯）の高さと、OS のボタン（閉じる・最大化など）を避ける左右の幅。 */
private class CaptionSpace(val height: Dp, val start: Dp, val end: Dp)

/**
 * デスクトップの窓（Android 15 から）では、OS の窓の帯の地を透かして上の帯をそこに載せる（Mac・Windows で検索の欄がタイトルの帯にあるのと同じ）。
 * 窓の帯が無いとき（全画面・分割など）は null。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun captionSpace(): CaptionSpace? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return null
    // 帯を縮めて OS のボタンが無いとき（Galaxy など）は、透かすのをやめて OS の帯に戻し、その下に上の帯を出す
    var transparent by remember(view) { mutableStateOf(true) }
    // 帯の上の OS のボタンは、地が明るければ暗い色で描かせる
    val lightBar = MaterialTheme.colorScheme.surfaceContainerLowest.luminance() > 0.5f
    DisposableEffect(window, lightBar, transparent) {
        val mask = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        val flags = if (!transparent) 0 else WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or (if (lightBar) WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS else 0)
        window.insetsController?.setSystemBarsAppearance(flags, mask)
        // Galaxy は帯を作り直した後に透かしを伝えても灰色のまま描くので、少し後に外し、間を空けて付け直す
        // （同じ時に外して付け直すと、変わっていないと見なされて効かない）
        val reapply = Runnable {
            window.insetsController?.setSystemBarsAppearance(flags, mask)
        }
        val nudge = Runnable {
            window.insetsController?.setSystemBarsAppearance(0, mask)
            view.postDelayed(reapply, 300)
        }
        if (transparent) view.postDelayed(nudge, 500)
        onDispose {
            view.removeCallbacks(nudge)
            view.removeCallbacks(reapply)
            window.insetsController?.setSystemBarsAppearance(0, mask)
        }
    }
    val density = LocalDensity.current
    val top = WindowInsets.captionBar.getTop(density)
    val width = LocalWindowInfo.current.containerSize.width
    // OS のボタンの場所は、帯の高さや窓の幅が変わらなくても動く（Galaxy でボタンを縮めたときなど）ので、組み直しのたびに読み直す
    var rects by remember(view) { mutableStateOf(captionRects(view)) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { captionRects(view).let { if (it != rects) rects = it } }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    // 左右の端に寄ったものだけを避ける。窓の幅の大半を覆うもの（縮めたボタンなどで返ることがある）は避けると中身が消えるので見ない
    val start = rects.filter { it.right <= width * 2 / 5 }.maxOfOrNull { it.right } ?: 0
    val end = rects.filter { it.left >= width * 3 / 5 }.minOfOrNull { width - it.left } ?: 0
    val hasControls = start > 0 || end > 0
    // OS の帯に戻している間は、OS のボタンの場所が分からないことがある。帯の高さ・窓の幅・ボタンの場所が
    // 戻したときから変わったら、もう一度透かしてボタンがあるか見る（縮めるのをやめたときに上の帯を帯に戻す）
    var opaqueState by remember(view) { mutableStateOf<Any?>(null) }
    val state = listOf(top, width, rects)
    LaunchedEffect(state, transparent) {
        Log.i(WaveApplication.LOG_TAG, "caption top=$top width=$width rects=$rects transparent=$transparent")
        // 帯が変わる途中の値で決めない
        delay(300)
        if (transparent) {
            if (top > 0 && !hasControls) {
                opaqueState = null
                transparent = false
            }
        } else if (opaqueState == null) {
            opaqueState = state
        } else if (opaqueState != state) {
            transparent = true
        }
    }
    if (top == 0 || !transparent || !hasControls) return null
    return with(density) { CaptionSpace(top.toDp(), start.coerceAtMost(width / 3).toDp(), end.coerceAtMost(width / 3).toDp()) }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun captionRects(view: View): List<Rect> = view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar()).orEmpty()

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun Pane(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

private val TOP_BAR_HEIGHT = 64.dp
private val SEARCH_WIDTH = 440.dp
private val LIBRARY_WIDTH = 300.dp
private val SIDE_WIDTH = 340.dp
private val PANE_GAP = 8.dp
