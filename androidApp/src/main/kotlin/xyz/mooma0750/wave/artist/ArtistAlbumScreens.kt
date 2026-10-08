package xyz.mooma0750.wave.artist

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AlbumPage
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistPage
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.library.LikedByArtist
import xyz.mooma0750.wave.library.LikedSongsCache
import xyz.mooma0750.wave.library.QueueMenu
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.search.SongMenuButton
import xyz.mooma0750.wave.search.SongRow
import xyz.mooma0750.wave.search.failureMessage
import xyz.mooma0750.wave.ui.BackButton
import xyz.mooma0750.wave.ui.ButtonStyle
import xyz.mooma0750.wave.ui.CollectionScreen
import xyz.mooma0750.wave.ui.DetailText
import xyz.mooma0750.wave.ui.totalLength
import xyz.mooma0750.wave.ui.IconTextButton
import xyz.mooma0750.wave.ui.MenuIcon
import xyz.mooma0750.wave.ui.PlayControls

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
            loadLiked()
            loadLivePosts()
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel,
    title: String,
    actions: SongActions,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onOpenAllSongs: (playlistId: String, artistName: String) -> Unit,
    /** 「高く評価した曲」をこのアーティストで絞った一覧を開く。 */
    onOpenLiked: (artistName: String) -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    /** このアーティストの最近の新作。 */
    newReleases: List<xyz.mooma0750.wave.core.music.NewRelease> = emptyList(),
    onPlaySong: (Song) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    val liked by viewModel.likedSongs.collectAsStateWithLifecycle()
    var shuffle by rememberSaveable { mutableStateOf(false) }
    val topSongs = page?.topSongs.orEmpty()
    CollectionScreen(
        title = page?.name ?: title,
        coverUrl = page?.thumbnailUrl,
        onBack = onBack,
        hero = true,
        play =
            PlayControls(shuffle, { shuffle = it }, enabled = topSongs.isNotEmpty()) {
                onPlay(if (shuffle) topSongs.shuffled() else topSongs, 0)
            },
    ) {
        when {
            state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
            page == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            else -> {
                // 登録（Spotify の「フォロー」）と、ライブ・チケットの情報を探す
                item(key = "subscribe") {
                    val subscribing by viewModel.subscribing.collectAsStateWithLifecycle()
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page.channelId != null) {
                            val failed = stringResource(R.string.artist_subscribe_failed)
                            androidx.compose.material3.OutlinedButton(onClick = { viewModel.toggleSubscribed(failed) }, enabled = !subscribing && page.subscribed != null) {
                                Text(if (page.subscribed == true) stringResource(R.string.artist_subscribed) else stringResource(R.string.artist_subscribe))
                            }
                        }
                        androidx.compose.material3.OutlinedButton(onClick = {
                            val query = android.net.Uri.encode(context.getString(R.string.artist_live_query, page.name))
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$query")))
                        }) { Text(stringResource(R.string.artist_live_info)) }
                    }
                }
                // 最近の新作（登録したアーティストの見張りで見つけたもの）
                if (newReleases.isNotEmpty()) {
                    item(key = "new-releases") { xyz.mooma0750.wave.releases.NewReleaseRow(newReleases, stringResource(R.string.releases_artist_title), actions, onPlaySong, showArtist = false) }
                }
                // ライブ・ツアーの告知（アーティストの YouTube のチャンネルの投稿から）
                item(key = "live-posts") {
                    val posts by viewModel.livePosts.collectAsStateWithLifecycle()
                    if (posts.isNotEmpty()) LivePostsRow(posts)
                }
                // Spotify の「人気」: 番号付きの曲
                item { SectionTitle(stringResource(R.string.artist_popular)) }
                itemsIndexed(page.topSongs, key = { index, song -> "song-$index-${song.videoId}" }) { index, song ->
                    SongRow(song, onClick = { onPlay(page.topSongs, index) }, number = index + 1) { SongMenuButton(song, actions) }
                }
                page.allSongsPlaylistId?.let { id ->
                    item { TextButton(onClick = { onOpenAllSongs(id, page.name) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.artist_see_all_songs)) } }
                }
                // Spotify の「高く評価した曲」の行（このアーティストの、自分が高く評価した曲）
                if (liked.loading || liked.songs.isNotEmpty()) {
                    item(key = "liked") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.artist_liked_songs)) },
                            supportingContent = {
                                Text(
                                    when {
                                        liked.loading -> stringResource(R.string.artist_checking)
                                        liked.incomplete -> pluralStringResource(R.plurals.artist_liked_count_more, liked.songs.size, liked.songs.size, page.name)
                                        else -> pluralStringResource(R.plurals.artist_liked_count, liked.songs.size, liked.songs.size, page.name)
                                    },
                                )
                            },
                            leadingContent = {
                                Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    MenuIcon(R.drawable.ic_favorite)
                                }
                            },
                            modifier = Modifier.clickable(enabled = liked.songs.isNotEmpty()) { onOpenLiked(page.name) },
                        )
                    }
                }
                page.albumShelves.forEachIndexed { shelfIndex, shelf ->
                    item(key = "shelf-$shelfIndex") {
                        Column(Modifier.padding(top = 8.dp)) {
                            SectionTitle(shelf.title)
                            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(shelf.albums, key = { it.browseId }) { album -> AlbumCard(album, onOpenAlbum) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun AlbumCard(
    album: AlbumSummary,
    onOpen: (AlbumSummary) -> Unit,
) {
    Column(Modifier.width(140.dp).clickable { onOpen(album) }) {
        coil3.compose.AsyncImage(
            model = album.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        album.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: AlbumViewModel,
    title: String,
    actions: SongActions,
    onPlay: (page: AlbumPage, index: Int, shuffle: Boolean) -> Unit,
    /** アルバムを丸ごとキューに入れる。next = 次に再生（false は最後に追加）。 */
    onQueue: (page: AlbumPage, next: Boolean) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    var shuffle by rememberSaveable { mutableStateOf(false) }
    CollectionScreen(
        title = page?.title ?: title,
        coverUrl = page?.thumbnailUrl,
        onBack = onBack,
        details = {
            page?.let { album ->
                // アーティスト（押すとアーティストのページ）、その下に「アルバム • 年 • 曲数, 長さ」（Windows と同じ並び）
                val artist = album.artists.firstOrNull()
                Text(
                    album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.then(if (artist?.browseId != null) Modifier.clickable { actions.openArtist(artist) } else Modifier),
                )
                val count = pluralStringResource(R.plurals.library_song_count, album.songs.size, album.songs.size)
                DetailText(listOfNotNull(album.subtitle, "$count, ${totalLength(album.songs.sumOf { it.durationSeconds ?: 0 })}").joinToString(" • "))
            }
        },
        actions = { QueueMenu(enabled = page?.songs?.isNotEmpty() == true, onQueue = { next -> page?.let { onQueue(it, next) } }) },
        play = PlayControls(shuffle, { shuffle = it }, enabled = page?.songs?.isNotEmpty() == true) { page?.let { onPlay(it, 0, shuffle) } },
    ) {
        when {
            state.failure != null -> item { Text(failureMessage(state.failure), Modifier.padding(16.dp)) }
            page == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            page.songs.isEmpty() -> item { Text(stringResource(R.string.artist_no_songs), Modifier.padding(16.dp)) }
            else ->
                itemsIndexed(page.songs, key = { index, song -> "$index-${song.videoId}" }) { index, song ->
                    SongRow(song, onClick = { onPlay(page, index, false) }, showArtwork = false) { SongMenuButton(song, actions) }
                }
        }
    }
}

/** ライブ・ツアーの告知の投稿を横に並べる。押すと YouTube の投稿を開く。 */
@Composable
private fun LivePostsRow(posts: List<xyz.mooma0750.wave.core.music.ArtistPost>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(top = 8.dp)) {
        SectionTitle(stringResource(R.string.artist_live_news))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(posts, key = { it.postId }) { post ->
                androidx.compose.material3.OutlinedCard(
                    onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(post.url))) },
                    modifier = Modifier.width(260.dp),
                ) {
                    post.imageUrl?.let { url ->
                        coil3.compose.AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    Column(Modifier.padding(12.dp)) {
                        Text(post.text, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        post.publishedText?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
