package xyz.mooma0750.wave.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PodcastEpisode
import xyz.mooma0750.wave.core.music.PodcastSummary
import xyz.mooma0750.wave.core.music.SearchPage
import xyz.mooma0750.wave.core.music.SearchSuggestions
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

/** 検索の結果の分け方。All は種類ごとに少しずつ並べる。ライブ・ポッドキャスト・エピソードは選んだときに初めて読む（All には出さない）。 */
enum class SearchTab { All, Songs, Albums, Artists, Live, Podcasts, Episodes }

/** 種類ごとの結果（続きのページを持つ）。 */
data class ResultList<T>(
    val items: List<T> = emptyList(),
    val continuation: String? = null,
    val loadingMore: Boolean = false,
    val failure: FailureKind? = null,
    /** 最初のページを読み始めた（選んだときに読む種類で、2回読まないため）。 */
    val requested: Boolean = false,
    /** 一度読み終えた（ライブは絞り込みを選んだときに初めて読む）。 */
    val loaded: Boolean = false,
)

data class SearchUiState(
    val query: String = "",
    /** 今の結果を出した語。打ち込み中で query と違う間は候補を出す。 */
    val submitted: String? = null,
    val suggestions: SearchSuggestions? = null,
    val tab: SearchTab = SearchTab.All,
    val songs: ResultList<Song> = ResultList(),
    val albums: ResultList<AlbumSummary> = ResultList(),
    val artists: ResultList<ArtistSummary> = ResultList(),
    val podcasts: ResultList<PodcastSummary> = ResultList(),
    val episodes: ResultList<PodcastEpisode> = ResultList(),
    /** ライブ配信。「ライブ」を選んだときに読む。 */
    val lives: ResultList<Song> = ResultList(),
    val loading: Boolean = false,
) {
    /** 打ち込み中（まだ検索していない語）。 */
    val typing: Boolean get() = query.isNotBlank() && query.trim() != submitted

    /** 3種類とも失敗した。どれかが取れていれば、取れた分を出す。 */
    val failure: FailureKind?
        get() = if (songs.failure != null && albums.failure != null && artists.failure != null) songs.failure else null

    val empty: Boolean
        get() = submitted != null && !loading && failure == null && songs.items.isEmpty() && albums.items.isEmpty() && artists.items.isEmpty()

    /** 語がアーティストの名前そのものなら、アーティストを先に出す。 */
    val artistFirst: Boolean
        get() = SearchOrder.artistFirst(submitted, artists.items)
}

object SearchOrder {
    fun artistFirst(
        query: String?,
        artists: List<ArtistSummary>,
    ): Boolean {
        val first = artists.firstOrNull() ?: return false
        return query != null && normalize(first.name) == normalize(query)
    }

    /** 全角・半角（日本語の入力で打った「ＹＯＡＳＯＢＩ」）、大文字・小文字、空白の違いを無視する。 */
    private fun normalize(text: String): String =
        java.text.Normalizer
            .normalize(text, java.text.Normalizer.Form.NFKC)
            .lowercase()
            .filterNot { it.isWhitespace() }
}

class SearchViewModel(
    private val music: MusicRepository,
    private val history: SearchHistoryStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var suggestionJob: Job? = null

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query) }
        suggestionJob?.cancel()
        val input = query.trim()
        if (input.isEmpty() || input == state.value.submitted) {
            mutableState.update { it.copy(suggestions = null) }
            return
        }
        suggestionJob =
            viewModelScope.launch {
                // 打つたびに送らない（打ち終わるのを少し待つ）
                delay(SUGGESTION_DEBOUNCE_MS)
                val result = music.searchSuggestions(input)
                // 候補が来る間に語が変わっていたら捨てる（古い候補で上書きしない）
                if (state.value.query.trim() != input) return@launch
                when (result) {
                    is CallResult.Ok -> mutableState.update { it.copy(suggestions = result.value) }
                    // 候補が取れなくても検索はできるので、前の候補を消すだけにする
                    is CallResult.Failed -> mutableState.update { it.copy(suggestions = null) }
                }
            }
    }

    /** 検索をやめて、履歴の表示に戻る（時計の「消す」）。 */
    fun clear() {
        suggestionJob?.cancel()
        mutableState.value = SearchUiState()
    }

    fun search() {
        val query = state.value.query.trim().takeIf { it.isNotEmpty() } ?: return
        history.add(SearchHistoryEntry.Query(query))
        job?.cancel()
        suggestionJob?.cancel()
        mutableState.update {
            it.copy(
                query = query,
                submitted = query,
                suggestions = null,
                tab = SearchTab.All,
                songs = ResultList(),
                albums = ResultList(),
                artists = ResultList(),
                podcasts = ResultList(),
                episodes = ResultList(),
                lives = ResultList(),
                loading = true,
            )
        }
        job =
            viewModelScope.launch {
                val songs = async { music.searchSongs(query) }
                val albums = async { music.searchAlbums(query) }
                val artists = async { music.searchArtists(query) }
                val songResult = songs.await().toList { ResultList(it.songs, it.continuation) }
                val albumResult = albums.await().toList { it.asResultList() }
                val artistResult = artists.await().toList { it.asResultList() }
                // 件数だけを記録する（ログイン中の応答の形が匿名と違って0件になっていないかの確認用）
                Log.i(
                    WaveApplication.LOG_TAG,
                    "search songs=${songResult.items.size} albums=${albumResult.items.size} artists=${artistResult.items.size} " +
                        "failed=${listOfNotNull(songResult.failure, albumResult.failure, artistResult.failure).size}",
                )
                mutableState.update { it.copy(songs = songResult, albums = albumResult, artists = artistResult, loading = false) }
            }
    }

    /** 履歴や候補の語をそのまま探す。 */
    fun searchFor(query: String) {
        mutableState.update { it.copy(query = query) }
        search()
    }

    fun selectTab(tab: SearchTab) {
        mutableState.update { it.copy(tab = tab) }
        if (tab == SearchTab.Live) loadLives()
        // ポッドキャスト・エピソードは、初めて選んだときに読む
        val current = state.value
        val query = current.submitted ?: return
        when (tab) {
            SearchTab.Podcasts -> firstPage(current.podcasts, { s, list -> s.copy(podcasts = list) }) { music.searchPodcasts(query).toList { it.asResultList() } }
            SearchTab.Episodes -> firstPage(current.episodes, { s, list -> s.copy(episodes = list) }) { music.searchEpisodes(query).toList { it.asResultList() } }
            else -> Unit
        }
    }

    private fun <T> firstPage(
        list: ResultList<T>,
        put: (SearchUiState, ResultList<T>) -> SearchUiState,
        fetch: suspend () -> ResultList<T>,
    ) {
        if (list.requested) return
        mutableState.update { put(it, ResultList(loadingMore = true, requested = true)) }
        val submitted = state.value.submitted
        viewModelScope.launch {
            val first = fetch()
            Log.i(WaveApplication.LOG_TAG, "search podcast tab items=${first.items.size} failed=${first.failure != null}")
            mutableState.update { state -> if (state.submitted != submitted) state else put(state, first.copy(requested = true)) }
        }
    }

    /** ライブ配信を読む（まだ読んでいない語だけ）。 */
    private fun loadLives() {
        val current = state.value
        val query = current.submitted ?: return
        if (current.lives.loaded || current.lives.loadingMore) return
        mutableState.update { it.copy(lives = it.lives.copy(loadingMore = true)) }
        viewModelScope.launch {
            val result = music.searchLive(query).toList { ResultList(it.songs, it.continuation) }
            Log.i(WaveApplication.LOG_TAG, "search live=${result.items.size} failed=${result.failure != null}")
            mutableState.update { if (it.submitted != query) it else it.copy(lives = result.copy(loaded = true)) }
        }
    }

    fun loadMore(tab: SearchTab) {
        val current = state.value
        val query = current.submitted ?: return
        when (tab) {
            SearchTab.Songs ->
                more(current.songs, { s, list -> s.copy(songs = list) }) { token ->
                    music.searchSongs(query, token).toList { ResultList(it.songs, it.continuation) }
                }
            SearchTab.Albums ->
                more(current.albums, { s, list -> s.copy(albums = list) }) { token -> music.searchAlbums(query, token).toList { it.asResultList() } }
            SearchTab.Artists ->
                more(current.artists, { s, list -> s.copy(artists = list) }) { token -> music.searchArtists(query, token).toList { it.asResultList() } }
            SearchTab.Podcasts ->
                more(current.podcasts, { s, list -> s.copy(podcasts = list) }) { token -> music.searchPodcasts(query, token).toList { it.asResultList() } }
            SearchTab.Episodes ->
                more(current.episodes, { s, list -> s.copy(episodes = list) }) { token -> music.searchEpisodes(query, token).toList { it.asResultList() } }
            SearchTab.Live ->
                more(current.lives, { s, list -> s.copy(lives = list.copy(loaded = true)) }) { token -> music.searchLive(query, token).toList { ResultList(it.songs, it.continuation) } }
            SearchTab.All -> Unit
        }
    }

    private fun <T> more(
        list: ResultList<T>,
        put: (SearchUiState, ResultList<T>) -> SearchUiState,
        fetch: suspend (String) -> ResultList<T>,
    ) {
        val token = list.continuation ?: return
        if (list.loadingMore) return
        mutableState.update { put(it, list.copy(loadingMore = true)) }
        val submitted = state.value.submitted
        viewModelScope.launch {
            val next = fetch(token)
            mutableState.update { state ->
                if (state.submitted != submitted) return@update state
                // 続きが取れなければ、それ以上は読まない（取れた分は残す）
                put(state, ResultList(list.items + next.items.filter { it !in list.items }, next.continuation, loadingMore = false, requested = true))
            }
        }
    }

    private fun <T> SearchPage<T>.asResultList(): ResultList<T> = ResultList(items, continuation)

    private inline fun <V, T> CallResult<V>.toList(ok: (V) -> ResultList<T>): ResultList<T> =
        when (this) {
            is CallResult.Ok -> ok(value)
            is CallResult.Failed -> ResultList(failure = kind)
        }

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
