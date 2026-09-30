package xyz.mooma0750.wave.library

import xyz.mooma0750.wave.core.music.PlaylistOrder
import kotlinx.coroutines.job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
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
import xyz.mooma0750.wave.core.music.PlaylistPage
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
    /** プレイリストの中の絞り込み（手元で絞る）。 */
    val query: String = "",
    /** 絞り込みのために全ページを読んでいる途中。 */
    val loadingAll: Boolean = false,
    /** 上限まで読んでも続きがあった（絞り込みが全曲を見ていない）。 */
    val incomplete: Boolean = false,
    val order: PlaylistOrder = PlaylistOrder(),
    /** サーバーで並べ替えられるプレイリストか（できなければカスタムだけ）。 */
    val sortable: Boolean = false,
)

/** プレイリストごとに選んだ並べ方（アプリの設定に持つ）。 */
interface PlaylistOrderStore {
    suspend fun get(playlistId: String): PlaylistOrder

    suspend fun set(
        playlistId: String,
        order: PlaylistOrder,
    )
}

class PlaylistViewModel(
    private val music: MusicRepository,
    private val playlistId: String,
    initialTitle: String?,
    initialQuery: String? = null,
    private val orders: PlaylistOrderStore,
) : ViewModel() {
    private companion object {
        /** 1ページ100曲。中を探すときに読むページ数の上限。 */
        const val MAX_PAGES = 50
    }

    private val mutableState = MutableStateFlow(PlaylistUiState(title = initialTitle, query = initialQuery.orEmpty()))
    val state: StateFlow<PlaylistUiState> = mutableState.asStateFlow()

    /** 「もっと読む」と「全部読む」が同じ続きを2回読まないように、続きは1つずつ読む。 */
    private var pages = NextPageLoader()

    /** 読み込みの入れ物。並べ方を変えたら取り替えて、前の並べ方の続きを読み足さない。 */
    private var loads = newLoads()

    private fun newLoads() = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext.job))

    private suspend fun appendNextPage(): CallResult<PlaylistPage>? =
        pages.next(
            currentToken = { state.value.continuation },
            fetch = { token -> music.playlist(playlistId, token) },
            apply = { result ->
                if (result is CallResult.Ok) {
                    mutableState.update {
                        it.copy(songs = it.songs + result.value.songs, setVideoIds = it.setVideoIds + result.value.setVideoIds, continuation = result.value.continuation)
                    }
                }
            },
        )

    init {
        // 前に選んだ並べ方を明示して読む（サーバーが覚えている並べ方は読めないので、画面の表示と揃える）
        loads.launch {
            val order = orders.get(playlistId)
            mutableState.update { it.copy(order = order) }
            loadFirstPage(order)
        }
    }

    /**
     * 並べ方を変える。最初のページから読み直す（読み込んだ曲・絞り込みの途中の読み込みは捨てる。絞り込みの語は残す）。
     * 自分のプレイリストでは、選んだ並べ方をサーバーが覚える（公式アプリの並びも変わる）。
     */
    fun setOrder(order: PlaylistOrder) {
        if (order == state.value.order) return
        viewModelScope.launch { orders.set(playlistId, order) }
        loads.cancel()
        loads = newLoads()
        pages = NextPageLoader()
        mutableState.update { PlaylistUiState(title = it.title, editable = it.editable, query = it.query, sortable = it.sortable, order = order) }
        loadFirstPage(order)
    }

    private fun loadFirstPage(order: PlaylistOrder) {
        loads.launch {
            when (val result = music.playlist(playlistId, order = order)) {
                is CallResult.Ok -> {
                    Log.i(WaveApplication.LOG_TAG, "playlist songs=${result.value.songs.size} continuation=${result.value.continuation != null} sortable=${result.value.sortable} order=${order.encode()}" + if (result.value.songs.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                    mutableState.update {
                        it.copy(
                            title = result.value.title ?: it.title,
                            songs = result.value.songs,
                            setVideoIds = result.value.setVideoIds,
                            editable = result.value.editable,
                            continuation = result.value.continuation,
                            loading = false,
                            // 並べ替えた応答にもメニューは付く。カスタムで開いたときの値を、並べ替えた後も残す
                            sortable = it.sortable || result.value.sortable,
                        )
                    }
                }
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, failure = result.kind) }
            }
            // 絞り込みを入れて開いたら、その場で残りのページも読む。カスタムの降順は全部読んでから逆にする
            if (state.value.query.isNotBlank() || order.reversedLocally) loadAll()
        }
    }

    /**
     * 中を探す。読み込んだぶんだけでは足りないので、絞り込みを始めたら残りのページも読む（上限 MAX_PAGES）。
     */
    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        if (query.isNotBlank()) loadAll()
    }

    private fun loadAll() {
        if (state.value.loadingAll) return
        if (state.value.continuation == null) {
            // 1ページで全部だった。カスタムの降順なら、ここで逆にする
            if (state.value.order.reversedLocally) mutableState.update { it.copy(songs = it.songs.reversed(), setVideoIds = it.setVideoIds.reversed()) }
            return
        }
        mutableState.update { it.copy(loadingAll = true) }
        loads.launch {
            var read = 0
            while (state.value.continuation != null) {
                if (read >= MAX_PAGES) {
                    mutableState.update { it.copy(incomplete = true) }
                    break
                }
                read++
                when (appendNextPage()) {
                    null -> break
                    is CallResult.Failed -> {
                        mutableState.update { it.copy(incomplete = true) }
                        break
                    }
                    is CallResult.Ok -> Unit
                }
            }
            mutableState.update {
                if (it.order.reversedLocally) {
                    it.copy(loadingAll = false, songs = it.songs.reversed(), setVideoIds = it.setVideoIds.reversed(), continuation = null)
                } else {
                    it.copy(loadingAll = false)
                }
            }
            Log.i(WaveApplication.LOG_TAG, "playlist load all songs=${state.value.songs.size} incomplete=${state.value.incomplete}")
        }
    }

    fun loadMore() {
        val current = state.value
        if (current.continuation == null || current.loading) return
        mutableState.update { it.copy(loading = true) }
        loads.launch {
            when (appendNextPage()) {
                // 続きを読めなかった: この先は読まない。全曲は見ていないので、中を探している途中なら「全部は読めなかった」と出す（レビュー 2026-09-26 低）
                is CallResult.Failed -> mutableState.update { it.copy(loading = false, continuation = null, incomplete = true) }
                else -> mutableState.update { it.copy(loading = false) }
            }
        }
    }
}
