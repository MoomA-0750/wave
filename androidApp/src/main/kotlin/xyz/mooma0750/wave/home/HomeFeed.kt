package xyz.mooma0750.wave.home

import android.util.Log
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
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
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.IconTextButton

data class HomeFeedUiState(
    /** 新作（アルバム・シングル）を先頭に、ホームの棚を続ける。 */
    val shelves: List<HomeShelf> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failure: FailureKind? = null,
)

/** ホームのおすすめ（YouTube Music のホームの棚と、新作）。 */
class HomeFeedViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeFeedUiState())
    val state: StateFlow<HomeFeedUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = HomeFeedUiState()
        viewModelScope.launch {
            val newReleases = async { music.newReleases() }
            val recent = async { music.recentActivity() }
            val home = music.home()
            val releases = (newReleases.await() as? CallResult.Ok)?.value.orEmpty()
            val recentItems = (recent.await() as? CallResult.Ok)?.value.orEmpty()
            Log.i(WaveApplication.LOG_TAG, "home recent items=${recentItems.size} kinds=${recentItems.groupingBy { it::class.simpleName.orEmpty() }.eachCount()}" + if (recentItems.isEmpty()) " renderers=${music.lastRendererNames}" else "")
            val recentShelf = listOfNotNull(recentItems.takeIf { it.isNotEmpty() }?.let { HomeShelf(RECENT_TITLE, null, it.take(RECENT_COUNT)) })
            val releaseShelf = recentShelf + listOfNotNull(releases.takeIf { it.isNotEmpty() }?.let { albums -> HomeShelf(NEW_RELEASES_TITLE, null, albums.take(NEW_RELEASES_COUNT).map(HomeItem::AlbumItem)) })
            when (home) {
                is CallResult.Ok -> {
                    // 棚の数と項目の種類の数だけを記録する（見出し・中身は記録しない）
                    Log.i(WaveApplication.LOG_TAG, "home shelves=${home.value.shelves.size} kinds=${kindCounts(home.value.shelves)} newReleases=${releases.size} continuation=${home.value.continuation != null}" + if (home.value.shelves.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                    var shelves = releaseShelf + home.value.shelves
                    var continuation = home.value.continuation
                    // ログイン中の最初のページは棚が2つほどしかない（続きに「もう一度聴く」なども来る）ので、数ページ先まで読んでおく
                    var pages = 1
                    while (shelves.size < MIN_SHELVES && continuation != null && pages < MAX_INITIAL_PAGES) {
                        val next = music.home(continuation) as? CallResult.Ok ?: break
                        shelves = shelves + next.value.shelves
                        continuation = next.value.continuation
                        pages++
                    }
                    Log.i(WaveApplication.LOG_TAG, "home initial pages=$pages shelves=${shelves.size} kinds=${kindCounts(shelves)}")
                    mutableState.value = HomeFeedUiState(shelves, continuation, loading = false)
                }
                is CallResult.Failed ->
                    mutableState.value = HomeFeedUiState(releaseShelf, null, loading = false, failure = home.kind.takeIf { releaseShelf.isEmpty() })
            }
        }
    }

    fun loadMore() {
        val token = state.value.continuation ?: return
        if (state.value.loadingMore) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = music.home(token)
            mutableState.update { current ->
                when (next) {
                    is CallResult.Ok -> current.copy(shelves = current.shelves + next.value.shelves, continuation = next.value.continuation, loadingMore = false)
                    is CallResult.Failed -> current.copy(continuation = null, loadingMore = false)
                }
            }
        }
    }

    private fun kindCounts(shelves: List<HomeShelf>): Map<String, Int> =
        shelves.flatMap { it.items }.groupingBy { it::class.simpleName.orEmpty() }.eachCount()

    private companion object {
        const val NEW_RELEASES_TITLE = "新作"
        const val RECENT_TITLE = "最近聴いたもの"
        const val RECENT_COUNT = 20
        const val NEW_RELEASES_COUNT = 20
        const val MIN_SHELVES = 8
        const val MAX_INITIAL_PAGES = 4
    }
}

/** ホームの棚を、横に流れる欄として並べる。 */
fun LazyListScope.homeFeedItems(
    state: HomeFeedUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    actions: SongActions,
) {
    state.shelves.forEachIndexed { index, shelf ->
        item(key = "shelf-$index") {
            Column(Modifier.padding(top = 12.dp)) {
                shelf.strapline?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
                Text(shelf.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.items) { item ->
                        when (item) {
                            is HomeItem.SongItem -> Card(item.song.thumbnailUrl, item.song.title, item.song.subtitle, RoundedCornerShape(6.dp)) { onPlaySong(item.song) }
                            is HomeItem.AlbumItem -> Card(item.album.thumbnailUrl, item.album.title, item.album.subtitle, RoundedCornerShape(6.dp)) { actions.openAlbum(AlbumRef(item.album.title, item.album.browseId)) }
                            is HomeItem.PlaylistItem -> Card(item.playlist.thumbnailUrl, item.playlist.title, item.playlist.subtitle, RoundedCornerShape(6.dp)) { onOpenPlaylist(item.playlist) }
                            is HomeItem.ArtistItem -> Card(item.artist.thumbnailUrl, item.artist.name, item.artist.subtitle, CircleShape) { actions.openArtist(ArtistRef(item.artist.name, item.artist.browseId)) }
                        }
                    }
                }
            }
        }
    }
    // 下にライブラリが続くので、続きの棚は自動では読まない
    if (state.continuation != null) {
        item(key = "home-more") {
            IconTextButton(R.drawable.ic_add, if (state.loadingMore) "読み込んでいます…" else "おすすめをもっと見る", onLoadMore, Modifier.padding(horizontal = 8.dp), enabled = !state.loadingMore)
        }
    }
    when {
        state.loading -> item(key = "home-loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
        state.failure != null ->
            item(key = "home-failure") {
                Column(Modifier.padding(16.dp)) {
                    Text("おすすめを読み込めませんでした（${failureMessage(state.failure)}）", style = MaterialTheme.typography.bodySmall)
                    IconTextButton(R.drawable.ic_refresh, "もう一度", onRetry)
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
    onClick: () -> Unit,
) {
    Column(Modifier.width(CARD_SIZE).clickable(onClick = onClick)) {
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
