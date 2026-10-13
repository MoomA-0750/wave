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
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.FeedFailure
import xyz.mooma0750.wave.core.music.FeedLibrary
import xyz.mooma0750.wave.core.music.FeedResult
import xyz.mooma0750.wave.core.music.FeedShow
import xyz.mooma0750.wave.core.music.NewEpisodes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import xyz.mooma0750.wave.core.music.PodcastFeedClient
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
    /** RSS の番組（`rss:…`）を読む（ADR-0012）。 */
    private val feeds: PodcastFeedClient? = null,
    private val feedLibrary: FeedLibrary? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PodcastUiState())
    val state: StateFlow<PodcastUiState> = mutableState.asStateFlow()

    private val feedUrl = Feed.feedUrlOf(browseId)

    /** 読んだ RSS の番組（聴いたところが変わったら一覧を作り直す）。 */
    private var feedShow: FeedShow? = null

    /** RSS の番組で、読んだ項目の数（続きを読むたびに増える）。 */
    private var feedLoaded = FeedShow.PAGE_SIZE

    init {
        viewModelScope.launch { if (feedUrl != null) loadFeed(feedUrl) else load() }
        // 聴いたところ・登録が変わったら、RSS の番組の表示を合わせる
        if (feedUrl != null && feedLibrary != null) {
            viewModelScope.launch {
                feedLibrary.state.collect { library ->
                    val show = feedShow ?: return@collect
                    // 読んだところまで（続きで読んだ分も）を、聴いたところを入れて作り直す
                    mutableState.update { state ->
                        val page = show.toPage(saved = library.isSubscribed(show.feedUrl), count = feedLoaded) { library.positions[it] }
                        state.copy(page = page, episodes = page.episodes)
                    }
                }
            }
        }
    }

    private suspend fun loadFeed(url: String) {
        val client = feeds ?: return
        when (val result = client.show(url)) {
            is FeedResult.Ok -> {
                val show = result.show
                feedShow = show
                feedLibrary?.refresh(show)
                val library = feedLibrary?.state?.value
                val page = show.toPage(saved = library?.isSubscribed(url) ?: false) { library?.positions?.get(it) }
                Log.i(WaveApplication.LOG_TAG, "podcast feed episodes=${page.episodes.size} saved=${page.saved}")
                mutableState.value = PodcastUiState(page = page, episodes = page.episodes, continuation = page.continuation)
            }
            is FeedResult.Failed -> {
                Log.i(WaveApplication.LOG_TAG, "podcast feed failed ${result.failure}")
                mutableState.value =
                    PodcastUiState(
                        failure =
                            when (result.failure) {
                                FeedFailure.Network -> FailureKind.Network
                                is FeedFailure.Http -> FailureKind.Http
                                else -> FailureKind.Parse
                            },
                    )
            }
        }
    }

    private suspend fun load() {
        when (val result = music.podcast(browseId)) {
            is CallResult.Ok -> {
                val page = result.value
                Log.i(WaveApplication.LOG_TAG, "podcast episodes=${page.episodes.size} more=${page.continuation != null} saved=${page.saved}")
                mutableState.value = PodcastUiState(page = page, episodes = page.episodes, continuation = page.continuation)
                if (browseId == MusicRepository.NEW_EPISODES_BROWSE) mergeFeedEpisodes()
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
                if (browseId == MusicRepository.NEW_EPISODES_BROWSE && mutableState.value.page != null) mergeFeedEpisodes()
            }
        }
    }

    /**
     * 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）。番組のフィードは4つずつ並べて読む。
     * 読めなかった番組は飛ばす。混ぜた後の続き（YouTube の続き）はそのまま後ろに付く。
     */
    private suspend fun mergeFeedEpisodes() {
        val client = feeds ?: return
        val library = feedLibrary ?: return
        val subscriptions = library.state.value.subscriptions
        if (subscriptions.isEmpty()) return
        val shows =
            kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                subscriptions.map { subscription ->
                    async { gate.withPermit { (client.show(subscription.feedUrl) as? FeedResult.Ok)?.show } }
                }.awaitAll().filterNotNull()
            }
        val positions = library.state.value.positions
        val now = System.currentTimeMillis() / 1000
        Log.i(WaveApplication.LOG_TAG, "podcast new episodes feeds=${shows.size}/${subscriptions.size}")
        mutableState.update { state ->
            val merged = NewEpisodes.merge(state.episodes, shows, now) { positions[it] }
            state.copy(episodes = merged, page = state.page?.copy(episodes = merged))
        }
    }

    fun loadMore() {
        val current = state.value
        val page = current.page ?: return
        val token = current.continuation ?: return
        if (current.loadingMore) return
        mutableState.update { it.copy(loadingMore = true) }
        // RSS の番組は、読んだフィードの続きの項目を読む
        feedShow?.let { show ->
            viewModelScope.launch {
                val positions = feedLibrary?.state?.value?.positions.orEmpty()
                // 読んだ項目は FeedShow が覚えている（覚え場所はスレッドをまたがないので、画面のスレッドで読む。30件で数十 ms）
                val more = show.more(token) { positions[it] }
                feedLoaded = token.removePrefix(FeedShow.CONTINUATION_PREFIX).toIntOrNull()?.plus(FeedShow.PAGE_SIZE) ?: feedLoaded
                mutableState.update { it.copy(episodes = it.episodes + more.items, continuation = more.continuation, loadingMore = false) }
            }
            return
        }
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
        // RSS の番組は、この端末の登録を切り替える
        val show = feedShow
        if (feedUrl != null) {
            if (show == null || feedLibrary == null) return
            viewModelScope.launch { if (target) feedLibrary.subscribe(show.subscription) else feedLibrary.unsubscribe(show.feedUrl) }
            return
        }
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
