package xyz.mooma0750.wave.home

import android.util.Log
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.HomeChip
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuSheet
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.IconTextButton
/** ホームの棚を、横に流れる欄として並べる。 */
fun LazyListScope.homeFeedItems(
    state: HomeFeedUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    actions: SongActions,
) {
    state.shelves.forEachIndexed { index, shelf ->
        item(key = "shelf-$index") {
            Column(Modifier.padding(top = 12.dp)) {
                shelf.strapline?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
                Text(shelfTitle(shelf), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.items) { item ->
                        val look = HomeItemLook.of(item)
                        // 曲のカードは長押しで曲のメニューを開く（Spotify と同じ）
                        var menu by remember { mutableStateOf(false) }
                        Card(
                            look.thumbnailUrl,
                            look.title,
                            look.subtitle,
                            if (item is HomeItem.ArtistItem) CircleShape else MaterialTheme.shapes.small,
                            onLongClick = if (item is HomeItem.SongItem) ({ menu = true }) else null,
                        ) {
                            openHomeItem(item, onOpenPlaylist, actions)
                        }
                        if (menu && item is HomeItem.SongItem) SongMenuSheet(item.song, actions, onDismiss = { menu = false })
                    }
                }
            }
        }
    }
    // 下にライブラリが続くので、続きの棚は自動では読まない
    if (state.continuation != null) {
        item(key = "home-more") {
            IconTextButton(R.drawable.ic_add, if (state.loadingMore) stringResource(R.string.home_loading) else stringResource(R.string.home_more_recommendations), onLoadMore, Modifier.padding(horizontal = 8.dp), enabled = !state.loadingMore)
        }
    }
    when {
        state.loading -> item(key = "home-loading") { LoadingIndicator(Modifier.padding(16.dp)) }
        state.failure != null ->
            item(key = "home-failure") {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.home_recommendations_failed, failureMessage(state.failure)), style = MaterialTheme.typography.bodySmall)
                    IconTextButton(R.drawable.ic_refresh, stringResource(R.string.home_retry), onRetry)
                }
            }
    }
}

@Composable
private fun Card(
    thumbnailUrl: String?,
    title: String,
    subtitle: String?,
    shape: Shape,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(CARD_SIZE).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        coil3.compose.AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(CARD_SIZE).clip(shape),
            contentScale = ContentScale.Crop,
        )
        Text(title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    }
}

private val CARD_SIZE = 140.dp
