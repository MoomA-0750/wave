package xyz.mooma0750.wave.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.mooma0750.wave.R
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
    val panelShown: Boolean,
    val onPanel: () -> Unit,
    val onExpand: () -> Unit,
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        TopBar(nav, parts, aiShown = panelShown && side == SidePane.Ai) { toggleSide(SidePane.Ai) }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(WindowInsets.statusBars)
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
                    panelShown = panelShown && side == SidePane.NowPlaying,
                    onPanel = { toggleSide(SidePane.NowPlaying) },
                    onExpand = openPlayer,
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
    onAi: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(TOP_BAR_HEIGHT).padding(horizontal = 8.dp)) {
        if (nav.canGoBack) IconAction(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), nav::back, Modifier.align(Alignment.CenterStart))
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(
                if (nav.tab == Tab.Home && nav.current == null) R.drawable.ic_home else R.drawable.ic_home_outline,
                stringResource(R.string.nav_home),
                nav::goHome,
            )
            parts.searchField(Modifier.widthIn(max = SEARCH_WIDTH).width(SEARCH_WIDTH))
            // Claude は検索の欄の右（Mac・Windows と同じ）。右の欄に出す
            IconAction(R.drawable.ic_ai, stringResource(R.string.ai_title), onAi, active = aiShown)
        }
        Box(Modifier.align(Alignment.CenterEnd)) { parts.account() }
    }
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
