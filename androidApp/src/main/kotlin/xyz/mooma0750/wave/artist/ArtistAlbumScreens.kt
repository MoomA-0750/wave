package xyz.mooma0750.wave.artist

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumPage
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistPage
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.library.LikedByArtist
import xyz.mooma0750.wave.library.LikedSongsCache
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage

/** 1回だけ読むページの状態。 */
data class PageUiState<T>(
    val page: T? = null,
    val failure: FailureKind? = null,
)

/** アーティストのページに出す、そのアーティストの高評価曲。 */
data class ArtistLikedState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    /** 高く評価した曲を最後まで読めなかった（この先にもあるかもしれない）。 */
    val incomplete: Boolean = false,
)

class ArtistViewModel(
    music: MusicRepository,
    private val browseId: String,
    private val liked: LikedSongsCache,
    private val likedSongsVersion: Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PageUiState<ArtistPage>())
    val state: StateFlow<PageUiState<ArtistPage>> = mutableState.asStateFlow()

    private val mutableLiked = MutableStateFlow(ArtistLikedState())
    val likedSongs: StateFlow<ArtistLikedState> = mutableLiked.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.artist(browseId)) {
                    is CallResult.Ok -> {
                        val page = result.value
                        Log.i(WaveApplication.LOG_TAG, "artist songs=${page.topSongs.size} all=${page.allSongsPlaylistId != null} shelves=${page.albumShelves.map { it.albums.size }}")
                        PageUiState(page = page)
                    }
                    is CallResult.Failed -> {
                        Log.i(WaveApplication.LOG_TAG, "artist failed ${result.kind}" + if (result.kind == FailureKind.Parse) " renderers=${music.lastRendererNames}" else "")
                        PageUiState(failure = result.kind)
                    }
                }
            loadLiked()
        }
    }

    private suspend fun loadLiked() {
        val name = state.value.page?.name.orEmpty()
        val result = liked.songs(likedSongsVersion)
        val songs = LikedByArtist.filter(result.songs, browseId, name)
        Log.i(WaveApplication.LOG_TAG, "artist liked songs=${songs.size} of=${result.songs.size} complete=${result.complete}")
        mutableLiked.value = ArtistLikedState(songs = songs, loading = false, incomplete = !result.complete)
    }
}

class AlbumViewModel(
    music: MusicRepository,
    browseId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PageUiState<AlbumPage>())
    val state: StateFlow<PageUiState<AlbumPage>> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.album(browseId)) {
                    is CallResult.Ok -> {
                        val page = result.value
                        Log.i(WaveApplication.LOG_TAG, "album songs=${page.songs.size} playlist=${page.playlistId != null}" + if (page.songs.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                        if (page.playlistId == null) Log.i(WaveApplication.LOG_TAG, "album playlistId paths=${music.lastKeyPaths}")
                        PageUiState(page = page)
                    }
                    is CallResult.Failed -> {
                        Log.i(WaveApplication.LOG_TAG, "album failed ${result.kind}" + if (result.kind == FailureKind.Parse) " renderers=${music.lastRendererNames}" else "")
                        PageUiState(failure = result.kind)
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel,
    title: String,
    actions: SongActions,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onOpenAllSongs: (playlistId: String, artistName: String) -> Unit,
    /** 「高く評価した曲」をこのアーティストで絞った一覧を開く。 */
    onOpenLiked: (artistName: String) -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    val liked by viewModel.likedSongs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(page?.name ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
                page == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                else -> {
                    item {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("曲", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            page.allSongsPlaylistId?.let { id -> Button(onClick = { onOpenAllSongs(id, page.name) }) { Text("すべての曲") } }
                        }
                    }
                    itemsIndexed(page.topSongs, key = { index, song -> "song-$index-${song.videoId}" }) { index, song ->
                        SongRow(song, onClick = { onPlay(page.topSongs, index) }) { SongMenuButton(song, actions) }
                    }
                    // このアーティストの、自分が高く評価した曲
                    if (liked.loading || liked.songs.isNotEmpty()) {
                        item(key = "liked-heading") {
                            Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("高く評価した曲", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (liked.songs.isNotEmpty()) TextButton(onClick = { onOpenLiked(page.name) }) { Text("一覧で見る") }
                            }
                        }
                        if (liked.loading) item(key = "liked-loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
                        itemsIndexed(liked.songs, key = { index, song -> "liked-$index-${song.videoId}" }) { index, song ->
                            SongRow(song, onClick = { onPlay(liked.songs, index) }) { SongMenuButton(song, actions) }
                        }
                        if (liked.incomplete) {
                            item(key = "liked-incomplete") {
                                Text("高く評価した曲を全部は読めていません", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    page.albumShelves.forEachIndexed { shelfIndex, shelf ->
                        item(key = "shelf-$shelfIndex") {
                            Column(Modifier.padding(top = 16.dp)) {
                                Text(shelf.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(shelf.albums, key = { it.browseId }) { album -> AlbumCard(album, onOpenAlbum) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: AlbumSummary,
    onOpen: (AlbumSummary) -> Unit,
) {
    Column(Modifier.width(140.dp).clickable { onOpen(album) }) {
        coil3.compose.AsyncImage(
            model = album.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        album.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: AlbumViewModel,
    title: String,
    actions: SongActions,
    onPlay: (page: AlbumPage, index: Int, shuffle: Boolean) -> Unit,
    /** アルバムを丸ごとキューに入れる。next = 次に再生（false は最後に追加）。 */
    onQueue: (page: AlbumPage, next: Boolean) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    Scaffold(
        topBar = { TopAppBar(title = { Text(page?.title ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
                page == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                else -> {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(page.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium)
                            page.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onPlay(page, 0, false) }, enabled = page.songs.isNotEmpty()) { Text("すべて再生") }
                                TextButton(onClick = { onPlay(page, 0, true) }, enabled = page.songs.isNotEmpty()) { Text("シャッフル再生") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onQueue(page, true) }, enabled = page.songs.isNotEmpty()) { Text("次に再生") }
                                TextButton(onClick = { onQueue(page, false) }, enabled = page.songs.isNotEmpty()) { Text("キューに追加") }
                            }
                            if (page.songs.isEmpty()) Text("曲がありません")
                        }
                    }
                    itemsIndexed(page.songs, key = { index, song -> "$index-${song.videoId}" }) { index, song ->
                        SongRow(song, onClick = { onPlay(page, index, false) }) { SongMenuButton(song, actions) }
                    }
                }
            }
        }
    }
}
