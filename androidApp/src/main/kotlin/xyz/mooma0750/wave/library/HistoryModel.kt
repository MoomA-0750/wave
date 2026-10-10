package xyz.mooma0750.wave.library

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.HistorySection
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

data class HistoryUiState(
    val sections: List<HistorySection> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = true,
    val failure: FailureKind? = null,
) {
    /** 見出しと曲を1本の並びにしたもの（画面に出す順）。曲には、見出しを除いた並びでの番号（再生を始める位置）を持たせる。 */
    val rows: List<HistoryRow>
        get() {
            var songIndex = 0
            return sections.flatMap { section ->
                listOfNotNull(section.title?.let { HistoryRow.Heading(it) }) + section.songs.map { HistoryRow.Item(it, songIndex++) }
            }
        }

    val songs: List<Song> get() = sections.flatMap { it.songs }
}

sealed interface HistoryRow {
    data class Heading(
        val title: String,
    ) : HistoryRow

    data class Item(
        val song: Song,
        /** 見出しを除いた並び（songs）での番号。 */
        val songIndex: Int,
    ) : HistoryRow
}

/** 再生履歴（自分が聴いた曲）。送信の設定とは別で、切っていても見られる（切っている間の分は載らない）。 */
class HistoryViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = music.history()) {
                is CallResult.Ok -> {
                    Log.i(WaveApplication.LOG_TAG, "history sections=${result.value.sections.size} songs=${result.value.songs.size} continuation=${result.value.continuation != null}")
                    mutableState.update { it.copy(sections = result.value.sections, continuation = result.value.continuation, loading = false) }
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
            when (val result = music.history(token)) {
                is CallResult.Ok ->
                    mutableState.update { state ->
                        // 同じ見出し（「今日」など）が続いたらつなげる
                        val merged =
                            result.value.sections.fold(state.sections) { acc, section ->
                                val last = acc.lastOrNull()
                                if (last != null && last.title == section.title) {
                                    acc.dropLast(1) + last.copy(songs = last.songs + section.songs)
                                } else {
                                    acc + section
                                }
                            }
                        state.copy(sections = merged, continuation = result.value.continuation, loading = false)
                    }
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, continuation = null) }
            }
        }
    }
}
