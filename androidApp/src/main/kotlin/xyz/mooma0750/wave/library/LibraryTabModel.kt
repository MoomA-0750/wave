package xyz.mooma0750.wave.library

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
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.FeedLibraryState
import xyz.mooma0750.wave.core.music.PodcastSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

/** ライブラリの1件（Spotify の「マイライブラリ」の行）。 */
sealed interface LibraryEntry {
    val key: String
    val title: String

    data class Playlist(
        val playlist: PlaylistSummary,
    ) : LibraryEntry {
        override val key get() = "p-${playlist.playlistId}"
        override val title get() = playlist.title
    }

    data class Artist(
        val artist: ArtistSummary,
    ) : LibraryEntry {
        override val key get() = "ar-${artist.browseId}"
        override val title get() = artist.name
    }

    data class Album(
        val album: AlbumSummary,
    ) : LibraryEntry {
        override val key get() = "al-${album.browseId}"
        override val title get() = album.title
    }

    /** 保存したポッドキャストの番組。 */
    data class Podcast(
        val podcast: PodcastSummary,
    ) : LibraryEntry {
        override val key get() = "pod-${podcast.browseId}"
        override val title get() = podcast.title
    }
}

/** 絞り込み（Spotify のチップ）。 */
enum class LibraryFilter { Playlists, Artists, Albums, Podcasts }

/** 並べ方（Spotify の「最近」「追加順」「アルファベット順」）。 */
enum class LibrarySort { Recents, Added, Alphabetical }

data class LibraryTabUiState(
    val entries: List<LibraryEntry> = emptyList(),
    /** 最近再生した順（ライブラリの最近のアクティビティ）。並べ方の「最近」に使う。 */
    val recentKeys: List<String> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureKind? = null,
)

object LibraryOrder {
    /**
     * 絞り込みと並べ方を当てた一覧。「最近」は最近再生したものを先に、残りは追加順のまま。
     * query は「マイライブラリを検索」の語（種類のチップと一緒に効く）。
     */
    fun arrange(
        state: LibraryTabUiState,
        filter: LibraryFilter?,
        sort: LibrarySort,
        query: String = "",
    ): List<LibraryEntry> {
        val filtered =
            state.entries.filter {
                val kind =
                    when (filter) {
                        null -> true
                        LibraryFilter.Playlists -> it is LibraryEntry.Playlist
                        LibraryFilter.Artists -> it is LibraryEntry.Artist
                        LibraryFilter.Albums -> it is LibraryEntry.Album
                        LibraryFilter.Podcasts -> it is LibraryEntry.Podcast
                    }
                kind && matches(it.title, subtitleOf(it), query)
            }
        return when (sort) {
            LibrarySort.Added -> filtered
            LibrarySort.Alphabetical -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.Recents -> {
                val rank = state.recentKeys.withIndex().associate { (index, key) -> key to index }
                filtered.withIndex().sortedWith(compareBy({ rank[it.value.key] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
            }
        }
    }

    /**
     * 「マイライブラリを検索」に当たるか。名前と補足（作った人・アーティスト）のどれかに、入力した語がすべて含まれていれば残す
     * （空白区切りで AND、大文字小文字は無視。プレイリストの中を探すのと同じ）。入力が空なら全部。
     */
    fun matches(
        title: String,
        subtitle: String?,
        query: String,
    ): Boolean {
        val terms = query.lowercase().split(' ', '\u3000').filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val haystack = "${title.lowercase()} ${subtitle.orEmpty().lowercase()}"
        return terms.all { it in haystack }
    }

    /** 検索に使う補足（サーバーから来た文言だけ。種類の名前は含めない）。 */
    private fun subtitleOf(entry: LibraryEntry): String? =
        when (entry) {
            is LibraryEntry.Playlist -> entry.playlist.subtitle
            is LibraryEntry.Artist -> null
            is LibraryEntry.Album -> entry.album.subtitle
            is LibraryEntry.Podcast -> entry.podcast.author
        }

    fun keyOf(item: HomeItem): String? =
        when (item) {
            is HomeItem.PlaylistItem -> "p-${item.playlist.playlistId}"
            is HomeItem.ArtistItem -> "ar-${item.artist.browseId}"
            is HomeItem.AlbumItem -> "al-${item.album.browseId}"
            is HomeItem.SongItem -> null
        }
}

class LibraryTabViewModel(
    private val music: MusicRepository,
    private val cache: LibraryCache? = null,
    /** 保存したポッドキャストも読む（時計は読まない）。 */
    private val withPodcasts: Boolean = true,
    /** RSS で登録した番組（ADR-0012）。YouTube のポッドキャストの後ろに並べる。 */
    private val feeds: StateFlow<FeedLibraryState>? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryTabUiState())
    val state: StateFlow<LibraryTabUiState> =
        if (feeds == null) {
            mutableState.asStateFlow()
        } else {
            combine(mutableState, feeds) { tab, library -> tab.withFeeds(library) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value.withFeeds(feeds.value))
        }

    private fun LibraryTabUiState.withFeeds(library: FeedLibraryState): LibraryTabUiState =
        if (library.subscriptions.isEmpty()) this else copy(entries = (entries + library.subscriptions.map { LibraryEntry.Podcast(it.summary) }).distinctBy { it.key })

    init {
        refresh()
    }

    fun refresh() {
        // 前に読んだ一覧があれば、まずそれを出す（読み直したら差し替える。途中のページでは差し替えない）
        val cached = cache?.tab
        mutableState.value = cached ?: LibraryTabUiState()
        viewModelScope.launch {
            // ページが届くたびに一覧に足す（全部そろうのを待たずに出す）
            val parts = MutableStateFlow(Triple(emptyList<PlaylistSummary>(), emptyList<ArtistSummary>(), emptyList<AlbumSummary>()))
            val podcastPart = MutableStateFlow(emptyList<PodcastSummary>())
            fun entriesOf(value: Triple<List<PlaylistSummary>, List<ArtistSummary>, List<AlbumSummary>>): List<LibraryEntry> {
                val (playlists, artists, albums) = value
                return (playlists.map(LibraryEntry::Playlist) + artists.map(LibraryEntry::Artist) + albums.map(LibraryEntry::Album) + podcastPart.value.map(LibraryEntry::Podcast)).distinctBy { it.key }
            }
            fun publish() {
                val entries = entriesOf(parts.value)
                if (cached == null) mutableState.update { it.copy(entries = entries, loading = entries.isEmpty() && it.loading) }
            }
            fun publishFresh(value: Triple<List<PlaylistSummary>, List<ArtistSummary>, List<AlbumSummary>>) {
                mutableState.update { it.copy(entries = entriesOf(value)) }
            }
            val recent = async { music.recentActivity() }
            val artists = async { music.libraryArtists { page -> parts.update { it.copy(second = page) }.also { publish() } } }
            val albums = async { music.libraryAlbums { page -> parts.update { it.copy(third = page) }.also { publish() } } }
            val podcasts = async { if (withPodcasts) music.libraryPodcasts { page -> podcastPart.value = page.also { publish() } } else CallResult.Ok(emptyList()) }
            val playlists = music.libraryPlaylists { page -> parts.update { it.copy(first = page) }.also { publish() } }
            val artistResult = artists.await()
            val albumResult = albums.await()
            val podcastResult = podcasts.await()
            // 件数だけを記録する
            Log.i(
                WaveApplication.LOG_TAG,
                "library tab playlists=${(playlists as? CallResult.Ok)?.value?.size} artists=${(artistResult as? CallResult.Ok)?.value?.size} albums=${(albumResult as? CallResult.Ok)?.value?.size} " +
                    "podcasts=${(podcastResult as? CallResult.Ok)?.value?.size}" + if (podcastResult is CallResult.Ok && podcastResult.value.isEmpty()) " renderers=${music.lastRendererNames}" else "",
            )
            val recentKeys = (recent.await() as? CallResult.Ok)?.value.orEmpty().mapNotNull(LibraryOrder::keyOf)
            val failed = playlists is CallResult.Failed || artistResult is CallResult.Failed || albumResult is CallResult.Failed || podcastResult is CallResult.Failed
            if (cached != null && !failed) publishFresh(parts.value)
            mutableState.update {
                it.copy(
                    recentKeys = recentKeys.ifEmpty { it.recentKeys },
                    loading = false,
                    failure = (playlists as? CallResult.Failed)?.kind.takeIf { _ -> it.entries.isEmpty() },
                )
            }
            if (!failed) cache?.tab = mutableState.value
        }
    }
}
