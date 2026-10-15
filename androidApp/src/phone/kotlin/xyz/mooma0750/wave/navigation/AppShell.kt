package xyz.mooma0750.wave.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.mooma0750.wave.ui.NoticeHost
import androidx.compose.ui.res.painterResource
import xyz.mooma0750.wave.R
import androidx.compose.ui.res.stringResource

/**
 * 画面の枠（並びは Spotify と同じ）: 中身の下に、ミニプレーヤーと4つのタブ（ホーム・検索・Claude・ライブラリ）。
 * ミニプレーヤーを押すと、再生画面が下からせり上がって全面を覆う。部品は Material のもの。
 *
 * 広い画面（開いた折りたたみ・タブレット）では Spotify のタブレット版と同じく、左に中身と下のタブ、右に再生の列を常に出す
 * （ミニプレーヤーは出さない。右の列の「全面に広げる」で全面の再生画面を開く）。
 */
@Composable
fun AppShell(
    nav: AppNavigator,
    /** 何か再生している（ほかの端末の再生を映している）か。広い画面で右の列を出すかを決める。 */
    hasPlayback: Boolean,
    miniPlayer: @Composable (onOpen: () -> Unit) -> Unit,
    /** inPanel: 広い画面の右の列として出す（閉じる代わりに全面に広げる）。 */
    nowPlaying: @Composable (onClose: () -> Unit, inPanel: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    var playerOpen by remember { mutableStateOf(false) }
    // キューの通知から開いた: 狭い画面では再生画面を開く（キューは再生画面が出す）
    val openQueue by xyz.mooma0750.wave.playback.QueueNotice.openRequested.collectAsState()
    LaunchedEffect(openQueue) { if (openQueue) playerOpen = true }
    // ウィジェットから開いた: 再生画面を開く
    val openFromWidget by xyz.mooma0750.wave.widget.WidgetHooks.openNowPlaying.collectAsState()
    LaunchedEffect(openFromWidget) {
        if (openFromWidget) {
            playerOpen = true
            xyz.mooma0750.wave.widget.WidgetHooks.consumeOpenNowPlaying()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        // 横向きのスマホ（縦が狭い）: 下のタブの代わりに左に縦のレールを出し、縦の場所を中身に回す
        val rail = !wide && maxWidth > maxHeight && maxHeight < RAIL_MAX_HEIGHT
        val panelWidth = (maxWidth * 0.32f).coerceIn(PANEL_MIN_WIDTH, PANEL_MAX_WIDTH)
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                WideNavigationRail(Modifier.fillMaxHeight()) {
                    RailItem(nav, Tab.Home, R.drawable.ic_home, R.drawable.ic_home_outline, stringResource(R.string.nav_home))
                    RailItem(nav, Tab.Search, R.drawable.ic_search, R.drawable.ic_search_outline, stringResource(R.string.nav_search))
                    RailItem(nav, Tab.Ai, R.drawable.ic_ai, R.drawable.ic_ai, stringResource(R.string.nav_ai))
                    RailItem(nav, Tab.Library, R.drawable.ic_library, R.drawable.ic_library_outline, stringResource(R.string.nav_library))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // 下のタブがナビゲーションバーの分を受け持つので、中の画面では数えない
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    content()
                    // 知らせはミニプレーヤーのすぐ上に出す
                    NoticeHost(Modifier.align(Alignment.BottomCenter))
                }
                if (!wide || !hasPlayback) miniPlayer { playerOpen = true }
                if (!rail) ShortNavigationBar {
                    TabItem(nav, Tab.Home, R.drawable.ic_home, R.drawable.ic_home_outline, stringResource(R.string.nav_home))
                    TabItem(nav, Tab.Search, R.drawable.ic_search, R.drawable.ic_search_outline, stringResource(R.string.nav_search))
                    // Claude はどの画面からでも聞けるように、行き先（タブ）の1つにする（Material ではバーに押すだけのボタンは置かない）
                    TabItem(nav, Tab.Ai, R.drawable.ic_ai, R.drawable.ic_ai, stringResource(R.string.nav_ai))
                    TabItem(nav, Tab.Library, R.drawable.ic_library, R.drawable.ic_library_outline, stringResource(R.string.nav_library))
                }
            }
            if (wide && hasPlayback) {
                VerticalDivider()
                // 右の再生の列（幅は画面の3割ほど。狭すぎず、左の中身を潰さない）
                Box(Modifier.width(panelWidth).fillMaxHeight()) {
                    nowPlaying({ playerOpen = true }, true)
                }
            }
        }
        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it },
            exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it },
        ) {
            BackHandler { playerOpen = false }
            // 下の画面に触れないよう、全面で受け止める
            Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), indication = null) {}) {
                nowPlaying({ playerOpen = false }, false)
            }
        }
    }
}

/** これより縦が狭い横向きの画面（スマホを横にした）では、下のタブの代わりに左のレールにする（Material の Compact の高さ）。 */
private val RAIL_MAX_HEIGHT = 480.dp

/** これより広ければ、右に再生の列を出す（Material の Expanded の幅）。 */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private val PANEL_MIN_WIDTH = 340.dp
private val PANEL_MAX_WIDTH = 440.dp

@Composable
private fun RailItem(
    nav: AppNavigator,
    tab: Tab,
    @DrawableRes selectedIcon: Int,
    @DrawableRes icon: Int,
    label: String,
) {
    val selected = nav.tab == tab
    WideNavigationRailItem(
        railExpanded = false,
        selected = selected,
        onClick = { nav.select(tab) },
        icon = { Icon(painterResource(if (selected) selectedIcon else icon), contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun TabItem(
    nav: AppNavigator,
    tab: Tab,
    @DrawableRes selectedIcon: Int,
    @DrawableRes icon: Int,
    label: String,
) {
    val selected = nav.tab == tab
    ShortNavigationBarItem(
        selected = selected,
        onClick = { nav.select(tab) },
        icon = { Icon(painterResource(if (selected) selectedIcon else icon), contentDescription = null) },
        label = { Text(label) },
    )
}
