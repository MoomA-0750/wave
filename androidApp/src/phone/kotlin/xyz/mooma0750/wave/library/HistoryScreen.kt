package xyz.mooma0750.wave.library

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.HistorySection
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.BackButton
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    actions: SongActions,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library_history_title)) }, navigationIcon = { BackButton(onBack) }) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let { item { Text(failureMessage(it), Modifier.padding(16.dp)) } }
            if (!state.loading && state.failure == null && state.sections.isEmpty()) {
                item { Text(stringResource(R.string.library_history_empty), Modifier.padding(16.dp)) }
            }
            val rows = state.rows
            itemsIndexed(rows, key = { index, row -> if (row is HistoryRow.Item) "$index-${row.song.videoId}" else "$index-h" }) { index, row ->
                when (row) {
                    is HistoryRow.Heading -> Text(row.title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                    // 並びの中の位置（見出しを除いた番号）から再生する
                    is HistoryRow.Item -> SongRow(row.song, onClick = { onPlay(state.songs, row.songIndex) }) { SongMenuButton(row.song, actions) }
                }
                if (index == rows.lastIndex && state.continuation != null) LaunchedEffect(rows.size) { viewModel.loadMore() }
            }
            if (state.loading) item { LoadingIndicator(Modifier.padding(16.dp)) }
        }
    }
}
