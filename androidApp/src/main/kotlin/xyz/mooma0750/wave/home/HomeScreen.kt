package xyz.mooma0750.wave.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.AccountResult
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.settings.AccountInfo
import xyz.mooma0750.wave.settings.Avatar
import xyz.mooma0750.wave.ui.MenuIcon

/**
 * ホーム（並びは Spotify と同じ）: 左上のアカウントのアイコン（押すと横からメニュー）と絞り込みのチップ、
 * 最近聴いたものの2列の格子、おすすめの棚。
 */
@Composable
fun HomeScreen(
    account: AccountInfo,
    feed: HomeFeedViewModel,
    onPlaySong: (Song) -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    songActions: SongActions,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnect: () -> Unit,
    showConnect: Boolean,
) {
    val state by feed.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeThen = { action: () -> Unit ->
        scope.launch { drawer.close() }
        action()
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account, 48.dp)
                    Text(
                        (account.account as? AccountResult.Ok)?.name ?: "アカウント",
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(label = { Text("最近再生した曲") }, icon = { MenuIcon(R.drawable.ic_history) }, selected = false, onClick = { closeThen(onOpenHistory) })
                if (showConnect) {
                    NavigationDrawerItem(label = { Text("端末の連携") }, icon = { MenuIcon(R.drawable.ic_devices) }, selected = false, onClick = { closeThen(onConnect) })
                }
                NavigationDrawerItem(label = { Text("設定") }, icon = { MenuIcon(R.drawable.ic_settings) }, selected = false, onClick = { closeThen(onOpenSettings) })
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "top") {
                Row(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account, 32.dp, Modifier.padding(start = 16.dp).clip(CircleShape).clickable { scope.launch { drawer.open() } })
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = state.selected == null, onClick = { feed.select(null) }, label = { Text("すべて") }) }
                        items(state.chips) { chip -> FilterChip(selected = state.selected == chip, onClick = { feed.select(chip) }, label = { Text(chip.title) }) }
                    }
                }
            }
            if (state.recent.isNotEmpty()) {
                item(key = "recent") { RecentGrid(state.recent) { openHomeItem(it, onPlaySong, onOpenPlaylist, songActions) } }
            }
            homeFeedItems(state, feed::refresh, feed::loadMore, onPlaySong, onOpenPlaylist, songActions)
            item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 最近聴いたものの2列の格子（Spotify のホームの先頭）。画像を左に、名前を右に。 */
@Composable
private fun RecentGrid(
    items: List<HomeItem>,
    onClick: (HomeItem) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    val look = HomeItemLook.of(item)
                    Card(
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            coil3.compose.AsyncImage(
                                model = look.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).then(if (item is HomeItem.ArtistItem) Modifier.padding(4.dp).clip(CircleShape) else Modifier),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                look.title,
                                Modifier.padding(horizontal = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
