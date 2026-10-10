package xyz.mooma0750.wave.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.HomeChip
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.HomeShelf
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

object HomeShelves {
    /** 先頭の「新作」と、アルバムが3枚以上重なる別の棚があるか。 */
    fun duplicatesNewReleases(shelves: List<HomeShelf>): Boolean {
        val ours = shelves.firstOrNull { it.title == HomeShelves.NEW_RELEASES_TITLE && it.strapline == null } ?: return false
        val ids = ours.items.mapNotNull { (it as? HomeItem.AlbumItem)?.album?.browseId }.toSet()
        return shelves.any { shelf -> shelf !== ours && shelf.items.count { (it as? HomeItem.AlbumItem)?.album?.browseId in ids } >= 3 }
    }

    /** アプリが自分で作る棚の見出しの目印。画面で端末の言語の見出しに置き換える（YouTube から来る見出しとは重ならない）。 */
    const val NEW_RELEASES_TITLE = "wave:new-releases"
}

data class HomeFeedUiState(
    /** 上の格子に出す、最近聴いたもの（Spotify のホームの先頭の8枚）。 */
    val recent: List<HomeItem> = emptyList(),
    /** 新作（アルバム・シングル）を先頭に、ホームの棚を続ける。 */
    val shelves: List<HomeShelf> = emptyList(),
    /** 上の絞り込み（気分）。選んでいなければ selected は null（すべて）。 */
    val chips: List<HomeChip> = emptyList(),
    val selected: HomeChip? = null,
    val continuation: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failure: FailureKind? = null,
)

/** ホームのおすすめ（YouTube Music のホームの棚と、新作）。 */
class HomeFeedViewModel(
    private val music: MusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeFeedUiState())
    val state: StateFlow<HomeFeedUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    private var job: kotlinx.coroutines.Job? = null

    /** 絞り込みを選ぶ。同じものをもう一度押したら外す（Spotify のチップと同じ）。 */
    fun select(chip: HomeChip?) {
        val next = chip.takeIf { it != state.value.selected }
        load(next)
    }

    fun refresh() = load(state.value.selected)

    private fun load(chip: HomeChip?) {
        val chips = state.value.chips
        mutableState.value = HomeFeedUiState(chips = chips, selected = chip)
        job?.cancel()
        job =
            viewModelScope.launch {
            // 絞り込み中は、ホームの棚だけ（最近聴いたもの・新作は「すべて」のときだけ）
            val started = android.os.SystemClock.elapsedRealtime()
            fun took() = android.os.SystemClock.elapsedRealtime() - started
            val newReleases = async { (if (chip == null) music.newReleases() else CallResult.Ok(emptyList())).also { Log.i(WaveApplication.LOG_TAG, "home timing newReleases ms=${took()}") } }
            val recent = async { (if (chip == null) music.recentActivity() else CallResult.Ok(emptyList())).also { Log.i(WaveApplication.LOG_TAG, "home timing recent ms=${took()}") } }
            val home = music.home(params = chip?.params).also { Log.i(WaveApplication.LOG_TAG, "home timing home ms=${took()}") }
            val releases = (newReleases.await() as? CallResult.Ok)?.value.orEmpty()
            val recentItems = (recent.await() as? CallResult.Ok)?.value.orEmpty()
            Log.i(WaveApplication.LOG_TAG, "home recent items=${recentItems.size} kinds=${recentItems.groupingBy { it::class.simpleName.orEmpty() }.eachCount()}" + if (recentItems.isEmpty()) " renderers=${music.lastRendererNames}" else "")
            // 最近聴いたものは上の格子だけに出す（棚にも出すと同じものが2度並ぶ）
            val releaseShelf = listOfNotNull(releases.takeIf { it.isNotEmpty() }?.let { albums -> HomeShelf(HomeShelves.NEW_RELEASES_TITLE, null, albums.take(NEW_RELEASES_COUNT).map(HomeItem::AlbumItem)) })
            when (home) {
                is CallResult.Ok -> {
                    // 棚の数と項目の種類の数だけを記録する（見出し・中身は記録しない）
                    Log.i(WaveApplication.LOG_TAG, "home shelves=${home.value.shelves.size} kinds=${kindCounts(home.value.shelves)} newReleases=${releases.size} continuation=${home.value.continuation != null}" + if (home.value.shelves.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                    var shelves = releaseShelf + home.value.shelves
                    var continuation = home.value.continuation
                    // ログイン中の最初のページは棚が2つほどしかない（続きに「もう一度聴く」なども来る）ので、数ページ先まで読んでおく
                    var pages = 1
                    while (shelves.size < MIN_SHELVES && continuation != null && pages < MAX_INITIAL_PAGES) {
                        val next = music.home(continuation) as? CallResult.Ok ?: break
                        shelves = shelves + next.value.shelves
                        continuation = next.value.continuation
                        pages++
                    }
                    // YouTube Music のホームに新作の棚が来ていれば、こちらの「新作」は出さない（同じものが2つ並ぶ）
                    if (HomeShelves.duplicatesNewReleases(shelves)) shelves = shelves.filterNot { it.title == HomeShelves.NEW_RELEASES_TITLE && it.strapline == null }
                    Log.i(WaveApplication.LOG_TAG, "home initial pages=$pages shelves=${shelves.size} kinds=${kindCounts(shelves)}")
                    mutableState.value =
                        HomeFeedUiState(recentItems.take(RECENT_GRID), shelves, home.value.chips.ifEmpty { chips }, chip, continuation, loading = false)
                }
                is CallResult.Failed ->
                    mutableState.value =
                        HomeFeedUiState(recentItems.take(RECENT_GRID), releaseShelf, chips, chip, null, loading = false, failure = home.kind.takeIf { releaseShelf.isEmpty() })
            }
        }
    }

    fun loadMore() {
        val token = state.value.continuation ?: return
        if (state.value.loadingMore) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = music.home(token)
            mutableState.update { current ->
                when (next) {
                    is CallResult.Ok -> current.copy(shelves = current.shelves + next.value.shelves, continuation = next.value.continuation, loadingMore = false)
                    is CallResult.Failed -> current.copy(continuation = null, loadingMore = false)
                }
            }
        }
    }

    private fun kindCounts(shelves: List<HomeShelf>): Map<String, Int> =
        shelves.flatMap { it.items }.groupingBy { it::class.simpleName.orEmpty() }.eachCount()

    private companion object {
        const val RECENT_GRID = 8
        const val NEW_RELEASES_COUNT = 20
        const val MIN_SHELVES = 8
        const val MAX_INITIAL_PAGES = 4
    }
}
