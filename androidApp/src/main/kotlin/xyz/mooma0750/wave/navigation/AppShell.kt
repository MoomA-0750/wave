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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import xyz.mooma0750.wave.R

/**
 * 画面の枠（並びは Spotify と同じ）: 中身の下に、ミニプレーヤーと3つのタブ。
 * ミニプレーヤーを押すと、再生画面が下からせり上がって全面を覆う。部品は Material のもの。
 */
@Composable
fun AppShell(
    nav: AppNavigator,
    miniPlayer: @Composable (onOpen: () -> Unit) -> Unit,
    nowPlaying: @Composable (onClose: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var playerOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 下のタブがナビゲーションバーの分を受け持つので、中の画面では数えない
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { content() }
            miniPlayer { playerOpen = true }
            NavigationBar {
                TabItem(nav, Tab.Home, R.drawable.ic_home, R.drawable.ic_home_outline, "ホーム")
                TabItem(nav, Tab.Search, R.drawable.ic_search, R.drawable.ic_search_outline, "検索")
                TabItem(nav, Tab.Library, R.drawable.ic_library, R.drawable.ic_library_outline, "マイライブラリ")
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
                nowPlaying { playerOpen = false }
            }
        }
    }
}

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
