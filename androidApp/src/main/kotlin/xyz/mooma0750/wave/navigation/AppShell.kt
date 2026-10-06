package xyz.mooma0750.wave.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * 画面の枠（並びは Spotify と同じ）: 中身の下に、ミニプレーヤーと3つのタブ。
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        val panelWidth = (maxWidth * 0.32f).coerceIn(PANEL_MIN_WIDTH, PANEL_MAX_WIDTH)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // 下のタブがナビゲーションバーの分を受け持つので、中の画面では数えない
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    content()
                    // 知らせはミニプレーヤーのすぐ上に出す
                    NoticeHost(Modifier.align(Alignment.BottomCenter))
                }
                if (!wide || !hasPlayback) miniPlayer { playerOpen = true }
                NavigationBar {
                    TabItem(nav, Tab.Home, R.drawable.ic_home, R.drawable.ic_home_outline, stringResource(R.string.nav_home))
                    TabItem(nav, Tab.Search, R.drawable.ic_search, R.drawable.ic_search_outline, stringResource(R.string.nav_search))
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
            enter = slideInVertically(tween(300)) { it },
            exit = slideOutVertically(tween(250)) { it },
        ) {
            BackHandler { playerOpen = false }
            // 下の画面に触れないよう、全面で受け止める
            Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), indication = null) {}) {
                nowPlaying({ playerOpen = false }, false)
            }
        }
    }
}

/** これより広ければ、右に再生の列を出す（Material の Expanded の幅）。 */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private val PANEL_MIN_WIDTH = 340.dp
private val PANEL_MAX_WIDTH = 440.dp

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    nav: AppNavigator,
    tab: Tab,
    @DrawableRes selectedIcon: Int,
    @DrawableRes icon: Int,
    label: String,
) {
    val selected = nav.tab == tab
    NavigationBarItem(
        selected = selected,
        onClick = { nav.select(tab) },
        icon = { Icon(painterResource(if (selected) selectedIcon else icon), contentDescription = null) },
        label = { Text(label) },
    )
}
