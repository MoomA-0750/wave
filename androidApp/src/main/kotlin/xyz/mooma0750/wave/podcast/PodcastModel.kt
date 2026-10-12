package xyz.mooma0750.wave.podcast

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PodcastEpisode
import xyz.mooma0750.wave.core.music.PodcastPage
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.WriteResult

data class PodcastUiState(
    val page: PodcastPage? = null,
    /** 読んだエピソード（最初のページ＋続き）。 */
    val episodes: List<PodcastEpisode> = emptyList(),
    val continuation: String? = null,
    val loadingMore: Boolean = false,
    val failure: FailureKind? = null,
    /** 保存・解除を送っている間。 */
    val saving: Boolean = false,
)

/** ポッドキャストの番組のページ。エピソードは下まで来たら続きを読む。 */
class PodcastViewModel(
    private val music: MusicRepository,
    private val browseId: String,
    /** 読めなかったときに出す題（「新しいエピソード」）。 */
    private val title: String = "",
) : ViewModel() {
    private val mutableState = MutableStateFlow(PodcastUiState())
    val state: StateFlow<PodcastUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        when (val result = music.podcast(browseId)) {
            is CallResult.Ok -> {
                val page = result.value
                Log.i(WaveApplication.LOG_TAG, "podcast episodes=${page.episodes.size} more=${page.continuation != null} saved=${page.saved}")
                mutableState.value = PodcastUiState(page = page, episodes = page.episodes, continuation = page.continuation)
            }
            is CallResult.Failed -> {
                Log.i(WaveApplication.LOG_TAG, "podcast failed ${result.kind}" + if (result.kind == FailureKind.Parse) " renderers=${music.lastRendererNames}" else "")
                // 保存した番組が無いアカウントの「新しいエピソード」は、番組のページの形で返らない。失敗ではなく空として出す（Mac・iOS と同じ）
                mutableState.value =
                    if (result.kind == FailureKind.Parse && browseId == MusicRepository.NEW_EPISODES_BROWSE) {
                        PodcastUiState(page = PodcastPage(browseId, title, null, null, null, emptyList(), null))
                    } else {
                        PodcastUiState(failure = result.kind)
                    }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        val page = current.page ?: return
        val token = current.continuation ?: return
        if (current.loadingMore) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = music.podcastEpisodes(token, ArtistRef(page.title, null), browseId)
            mutableState.update { state ->
                when (result) {
                    is CallResult.Ok -> {
                        val known = state.episodes.map { it.song.videoId }.toSet()
                        // 同じトークンが返ったら（形が変わったときなど）止める
                        state.copy(episodes = state.episodes + result.value.items.filter { it.song.videoId !in known }, continuation = result.value.continuation?.takeIf { it != token }, loadingMore = false)
                    }
                    // 続きが読めなければ、それ以上は読まない（読めた分は残す）
                    is CallResult.Failed -> state.copy(continuation = null, loadingMore = false)
                }
            }
        }
    }

    /** ライブラリへの保存の切り替え。アーティストの登録と同じく、書けたら表示を変える（分からなければ読み直す）。 */
    fun toggleSaved(failedMessage: String) {
        val current = state.value
        val page = current.page ?: return
        if (current.saving || page.saved == null) return
        val target = page.saved != true
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = music.setPodcastSaved(page.playlistId, target, music.currentLoginGeneration())
            Log.i(WaveApplication.LOG_TAG, "podcast save result=${result::class.simpleName} save=$target")
            when (result) {
                is WriteResult.Done -> mutableState.update { it.copy(page = it.page?.copy(saved = target)) }
                is WriteResult.Failed -> xyz.mooma0750.wave.ui.Notices.show(failedMessage)
                WriteResult.Unknown -> (music.podcast(browseId) as? CallResult.Ok)?.value?.let { fresh -> mutableState.update { it.copy(page = it.page?.copy(saved = fresh.saved)) } }
            }
            mutableState.update { it.copy(saving = false) }
        }
    }
}
