package xyz.mooma0750.wave.artist

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumPage
import xyz.mooma0750.wave.core.music.ArtistPage
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.library.LikedByArtist
import xyz.mooma0750.wave.library.LikedSongsCache

/** 1回だけ読むページの状態。 */
data class PageUiState<T>(
    val page: T? = null,
    val failure: FailureKind? = null,
)

/** アーティストのページに出す、そのアーティストの高評価曲。 */
data class ArtistLikedState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    /** 高く評価した曲を最後まで読めなかった（この先にもあるかもしれない）。 */
    val incomplete: Boolean = false,
)

class ArtistViewModel(
    private val music: MusicRepository,
    private val browseId: String,
    private val liked: LikedSongsCache,
    private val likedSongsVersion: Int,
    private val channelPosts: xyz.mooma0750.wave.core.music.ChannelPostsClient? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PageUiState<ArtistPage>())
    val state: StateFlow<PageUiState<ArtistPage>> = mutableState.asStateFlow()

    private val mutableLiked = MutableStateFlow(ArtistLikedState())
    val likedSongs: StateFlow<ArtistLikedState> = mutableLiked.asStateFlow()

    private val mutableLivePosts = MutableStateFlow<List<xyz.mooma0750.wave.core.music.ArtistPost>>(emptyList())

    /** チャンネルの投稿のうち、ライブ・ツアーの告知らしいもの。 */
    val livePosts: StateFlow<List<xyz.mooma0750.wave.core.music.ArtistPost>> = mutableLivePosts.asStateFlow()

    private val mutableSubscribing = MutableStateFlow(false)

    /** 登録・解除を送っている間。 */
    val subscribing: StateFlow<Boolean> = mutableSubscribing.asStateFlow()

    /**
     * 登録（サブスクライブ）の切り替え。楽観的に表示を変えず、書けたら変える（分からなければページを読み直す）。
     * 登録したアーティストの新作は、裏の確かめ（ReleaseWatcher）が通知で知らせる。
     */
    fun toggleSubscribed(failedMessage: String) {
        val page = state.value.page ?: return
        val channelId = page.channelId ?: return
        if (mutableSubscribing.value) return
        val target = page.subscribed != true
        mutableSubscribing.value = true
        viewModelScope.launch {
            val result = music.setSubscribed(channelId, target, music.currentLoginGeneration())
            Log.i(WaveApplication.LOG_TAG, "artist subscribe result=${result::class.simpleName} subscribe=$target")
            when (result) {
                is xyz.mooma0750.wave.core.session.WriteResult.Done -> mutableState.value = state.value.copy(page = page.copy(subscribed = target))
                is xyz.mooma0750.wave.core.session.WriteResult.Failed -> xyz.mooma0750.wave.ui.Notices.show(failedMessage)
                xyz.mooma0750.wave.core.session.WriteResult.Unknown ->
                    (music.artist(browseId) as? CallResult.Ok)?.value?.let { mutableState.value = state.value.copy(page = it) }
            }
            mutableSubscribing.value = false
        }
    }

    init {
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.artist(browseId)) {
                    is CallResult.Ok -> {
                        val page = result.value
                        Log.i(WaveApplication.LOG_TAG, "artist songs=${page.topSongs.size} all=${page.allSongsPlaylistId != null} shelves=${page.albumShelves.map { it.albums.size }}")
                        PageUiState(page = page)
                    }
                    is CallResult.Failed -> {
                        Log.i(WaveApplication.LOG_TAG, "artist failed ${result.kind}" + if (result.kind == FailureKind.Parse) " renderers=${music.lastRendererNames}" else "")
                        PageUiState(failure = result.kind)
                    }
                }
            launch { loadAllSongs() }
            loadLiked()
            loadLivePosts()
        }
    }

    /** 「すべての曲」の一覧の最初のページ（再生の文脈にする）。読めなければ null。 */
    @Volatile
    private var allSongs: xyz.mooma0750.wave.core.music.PlaylistPage? = null

    private suspend fun loadAllSongs() {
        val id = state.value.page?.allSongsPlaylistId ?: return
        val result = music.playlist(id)
        allSongs = (result as? CallResult.Ok)?.value?.takeIf { it.songs.isNotEmpty() }
        Log.i(WaveApplication.LOG_TAG, "artist all songs ok=${result is CallResult.Ok} songs=${allSongs?.songs?.size} more=${allSongs?.continuation != null}")
    }

    /**
     * 再生の頼み方。「すべての曲」の一覧が読めていれば、それを文脈にして流す（人気の5曲の後も、このアーティストの曲が続く）。
     * 読めていなければ人気の曲だけを流し、最後の曲からラジオにつなぐ（ほかのアーティストも混ざる）。
     * @param song 押した曲。null なら先頭（再生ボタン）
     */
    fun playRequest(
        song: Song?,
        shuffle: Boolean,
    ): ArtistPlay? {
        val page = state.value.page ?: return null
        val all = allSongs
        if (all != null && page.allSongsPlaylistId != null) {
            val index = song?.let { s -> all.songs.indexOfFirst { it.videoId == s.videoId } } ?: 0
            if (index >= 0) return ArtistPlay(all.songs, index, page.allSongsPlaylistId, all.continuation, shuffle && song == null)
        }
        if (page.topSongs.isEmpty()) return null
        val index = song?.let { page.topSongs.indexOf(it).coerceAtLeast(0) } ?: 0
        return ArtistPlay(page.topSongs, index, null, null, shuffle && song == null)
    }

    private suspend fun loadLivePosts() {
        val page = state.value.page ?: return
        val channelId = page.channelId ?: browseId.takeIf { it.startsWith("UC") } ?: return
        val result = channelPosts?.posts(channelId)
        mutableLivePosts.value = xyz.mooma0750.wave.core.music.LiveNews.of(result.orEmpty()).take(MAX_LIVE_POSTS)
        Log.i(WaveApplication.LOG_TAG, "artist posts ok=${result != null} posts=${result?.size} live=${mutableLivePosts.value.size}")
    }

    private companion object {
        const val MAX_LIVE_POSTS = 6
    }

    private suspend fun loadLiked() {
        val name = state.value.page?.name.orEmpty()
        val result = liked.songs(likedSongsVersion)
        val songs = LikedByArtist.filter(result.songs, browseId, name)
        Log.i(WaveApplication.LOG_TAG, "artist liked songs=${songs.size} of=${result.songs.size} complete=${result.complete}")
        mutableLiked.value = ArtistLikedState(songs = songs, loading = false, incomplete = !result.complete)
    }
}

/** アーティストのページからの再生（playlistId が null なら、終わったらラジオにつなぐ）。 */
data class ArtistPlay(
    val songs: List<Song>,
    val index: Int,
    val playlistId: String?,
    val continuation: String?,
    val shuffle: Boolean,
)

class AlbumViewModel(
    music: MusicRepository,
    browseId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PageUiState<AlbumPage>())
    val state: StateFlow<PageUiState<AlbumPage>> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value =
                when (val result = music.album(browseId)) {
                    is CallResult.Ok -> {
                        val page = result.value
                        Log.i(WaveApplication.LOG_TAG, "album songs=${page.songs.size} playlist=${page.playlistId != null}" + if (page.songs.isEmpty()) " renderers=${music.lastRendererNames}" else "")
                        if (page.playlistId == null) Log.i(WaveApplication.LOG_TAG, "album playlistId paths=${music.lastKeyPaths}")
                        PageUiState(page = page)
                    }
                    is CallResult.Failed -> {
                        Log.i(WaveApplication.LOG_TAG, "album failed ${result.kind}" + if (result.kind == FailureKind.Parse) " renderers=${music.lastRendererNames}" else "")
                        PageUiState(failure = result.kind)
                    }
                }
        }
    }
}
