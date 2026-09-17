package xyz.mooma0750.wave.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage

/** ホームに出すライブラリの一覧（高く評価した曲 + プレイリスト）。 */
fun LazyListScope.libraryItems(
    state: LibraryUiState,
    onOpen: (PlaylistSummary) -> Unit,
) {
    item {
        Text("ライブラリ", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
    }
    item {
        PlaylistRow(PlaylistSummary(MusicRepository.LIKED_SONGS, "高く評価した曲", null, null), onOpen)
    }
    when {
        state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
        state.loading -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
    itemsIndexed(state.playlists, key = { _, playlist -> playlist.playlistId }) { _, playlist -> PlaylistRow(playlist, onOpen) }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    onOpen: (PlaylistSummary) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(playlist) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        playlist.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onPlay: (songs: List<Song>, index: Int, continuation: String?) -> Unit,
    onShufflePlay: (songs: List<Song>, continuation: String?) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: "プレイリスト", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Button(onClick = { onPlay(state.songs, 0, state.continuation) }, enabled = state.songs.isNotEmpty()) { Text("すべて再生") }
                    TextButton(onClick = { onShufflePlay(state.songs, state.continuation) }, enabled = state.songs.isNotEmpty()) { Text("シャッフル再生") }
                }
            }
            state.failure?.let { item { Text(failureMessage(it), Modifier.padding(16.dp)) } }
            if (!state.loading && state.failure == null && state.songs.isEmpty()) item { Text("曲がありません", Modifier.padding(16.dp)) }
            itemsIndexed(state.songs, key = { index, song -> "$index-${song.videoId}" }) { index, song ->
                SongRow(song, onClick = { onPlay(state.songs, index, state.continuation) }) {
                    TextButton(onClick = { onPlayNext(song) }) { Text("次に") }
                    TextButton(onClick = { onAddToQueue(song) }) { Text("追加") }
                }
                if (index == state.songs.lastIndex && state.continuation != null) LaunchedEffect(state.songs.size) { viewModel.loadMore() }
            }
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
}
