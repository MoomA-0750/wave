package xyz.mooma0750.wave.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

data class SearchUiState(
    val query: String = "",
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val failure: FailureKind? = null,
    val continuation: String? = null,
    /** 検索したが0件だった。 */
    val empty: Boolean = false,
)

class SearchViewModel(
    private val music: MusicRepository,
    private val history: SearchHistoryStore? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun search() {
        val query = state.value.query.trim().takeIf { it.isNotEmpty() } ?: return
        history?.add(SearchHistoryEntry.Query(query))
        job?.cancel()
        mutableState.update { it.copy(songs = emptyList(), loading = true, failure = null, continuation = null, empty = false) }
        job =
            viewModelScope.launch {
                when (val result = music.searchSongs(query)) {
                    is CallResult.Ok -> {
                        // 件数だけを記録する（ログイン中の応答の形が匿名と違って0件になっていないかの確認用）
                        Log.i(WaveApplication.LOG_TAG, "search songs=${result.value.songs.size} continuation=${result.value.continuation != null}")
                        mutableState.update { it.copy(songs = result.value.songs, continuation = result.value.continuation, loading = false, empty = result.value.songs.isEmpty()) }
                    }
                    is CallResult.Failed -> mutableState.update { it.copy(loading = false, failure = result.kind) }
                }
            }
    }

    /** 履歴の語をそのまま探す。 */
    fun searchFor(query: String) {
        mutableState.update { it.copy(query = query) }
        search()
    }

    fun loadMore() {
        val current = state.value
        val continuation = current.continuation ?: return
        if (current.loading) return
        mutableState.update { it.copy(loading = true) }
        job =
            viewModelScope.launch {
                when (val result = music.searchSongs(current.query, continuation)) {
                    is CallResult.Ok ->
                        mutableState.update { state ->
                            val known = state.songs.map { it.videoId }.toSet()
                            state.copy(songs = state.songs + result.value.songs.filter { it.videoId !in known }, continuation = result.value.continuation, loading = false)
                        }
                    is CallResult.Failed -> mutableState.update { it.copy(loading = false, continuation = null) }
                }
            }
    }
}
