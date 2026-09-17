package xyz.mooma0750.wave.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

data class LibraryUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

class LibraryViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val result = music.libraryPlaylists()) {
                is CallResult.Ok -> {
                    Log.i(WaveApplication.LOG_TAG, "library playlists=${result.value.size}" + if (result.value.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                    mutableState.update { it.copy(playlists = result.value, loading = false) }
                }
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, failure = result.kind) }
            }
        }
    }
}

data class PlaylistUiState(
    val title: String? = null,
    val songs: List<Song> = emptyList(),
    /** songs と同じ並びの、削除に使う ID。 */
    val setVideoIds: List<String?> = emptyList(),
    val editable: Boolean = false,
    val continuation: String? = null,
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

class PlaylistViewModel(
    private val music: MusicRepository,
    val playlistId: String,
    initialTitle: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlaylistUiState(title = initialTitle))
    val state: StateFlow<PlaylistUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = music.playlist(playlistId)) {
                is CallResult.Ok -> {
                    Log.i(WaveApplication.LOG_TAG, "playlist songs=${result.value.songs.size} continuation=${result.value.continuation != null}" + if (result.value.songs.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                    mutableState.update {
                        it.copy(
                            title = result.value.title ?: it.title,
                            songs = result.value.songs,
                            setVideoIds = result.value.setVideoIds,
                            editable = result.value.editable,
                            continuation = result.value.continuation,
                            loading = false,
                        )
                    }
                }
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, failure = result.kind) }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        val token = current.continuation ?: return
        if (current.loading) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = music.playlist(playlistId, token)) {
                is CallResult.Ok ->
                    mutableState.update {
                        it.copy(songs = it.songs + result.value.songs, setVideoIds = it.setVideoIds + result.value.setVideoIds, continuation = result.value.continuation, loading = false)
                    }
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, continuation = null) }
            }
        }
    }
}
