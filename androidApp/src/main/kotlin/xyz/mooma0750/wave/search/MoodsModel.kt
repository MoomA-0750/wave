package xyz.mooma0750.wave.search

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.music.MoodSection
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.home.HomeFeedUiState

data class MoodsUiState(
    val sections: List<MoodSection> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

class MoodsViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MoodsUiState())
    val state: StateFlow<MoodsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = MoodsUiState()
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.moods()) {
                    is CallResult.Ok -> MoodsUiState(result.value, loading = false)
                    is CallResult.Failed -> MoodsUiState(loading = false, failure = result.kind)
                }
        }
    }
}

class MoodCategoryViewModel(
    private val music: MusicRepository,
    private val params: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeFeedUiState())
    val state: StateFlow<HomeFeedUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = HomeFeedUiState()
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.moodCategory(params)) {
                    is CallResult.Ok -> HomeFeedUiState(shelves = result.value.shelves, loading = false)
                    is CallResult.Failed -> HomeFeedUiState(loading = false, failure = result.kind)
                }
        }
    }
}
