package xyz.mooma0750.wave

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.artist.AlbumViewModel
import xyz.mooma0750.wave.artist.ArtistViewModel
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.debug.DebugActions
import xyz.mooma0750.wave.home.HomeFeedViewModel
import xyz.mooma0750.wave.library.HistoryViewModel
import xyz.mooma0750.wave.library.LibraryTabViewModel
import xyz.mooma0750.wave.library.LibraryViewModel
import xyz.mooma0750.wave.library.PlaylistViewModel
import xyz.mooma0750.wave.navigation.PlayerActions
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.search.MoodCategoryViewModel
import xyz.mooma0750.wave.search.MoodsViewModel
import xyz.mooma0750.wave.search.SearchViewModel
import xyz.mooma0750.wave.settings.rememberAccountInfo
import xyz.mooma0750.wave.wear.AddToPlaylistScreen
import xyz.mooma0750.wave.wear.AlbumScreen
import xyz.mooma0750.wave.wear.ArtistScreen
import xyz.mooma0750.wave.wear.BrowseScreen
import xyz.mooma0750.wave.wear.CenterText
import xyz.mooma0750.wave.wear.FeedScreen
import xyz.mooma0750.wave.wear.FullScreenLoading
import xyz.mooma0750.wave.wear.HistoryScreen
import xyz.mooma0750.wave.wear.HomeMenu
import xyz.mooma0750.wave.wear.LibraryScreen
import xyz.mooma0750.wave.wear.LoginScreen
import xyz.mooma0750.wave.wear.LyricsScreen
import xyz.mooma0750.wave.wear.MoodScreen
import xyz.mooma0750.wave.wear.NowPlayingScreen
import xyz.mooma0750.wave.wear.PlaylistScreen
import xyz.mooma0750.wave.wear.QueueScreen
import xyz.mooma0750.wave.wear.ReleasesScreen
import xyz.mooma0750.wave.wear.SearchScreen
import xyz.mooma0750.wave.wear.SettingsScreen
import xyz.mooma0750.wave.wear.SongPage
import xyz.mooma0750.wave.wear.VolumeScreen
import xyz.mooma0750.wave.wear.WaveWearTheme
import xyz.mooma0750.wave.wear.WearNavHost
import xyz.mooma0750.wave.wear.WearNavigator
import xyz.mooma0750.wave.wear.WearScreen

/** 時計の画面の入口。ログインはスマホから受け取る（ADR-0011）。中身はスマホと同じ部品（ViewModel・再生サービス）を使う。 */
class MainActivity : ComponentActivity() {
    private val nav = WearNavigator()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DebugActions.handle(intent, (application as WaveApplication).container)
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: android.content.Intent?) {
        // 再生中の通知・進行中の表示から開いた: 再生画面へ
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) nav.openNowPlaying()
        val album = intent?.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ALBUM)
        val artist = intent?.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ARTIST)
        when {
            album != null -> nav.push(WearScreen.Album(album, intent.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ALBUM_TITLE).orEmpty()))
            artist != null -> nav.push(WearScreen.Artist(artist, intent.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ARTIST_NAME).orEmpty()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WaveApplication).container
        DebugActions.handle(intent, container)
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            WaveWearTheme {
                AppScaffold {
                    val authState by container.authManager.state.collectAsStateWithLifecycle()
                    when (val state = authState) {
                        AuthState.Restoring, AuthState.LoggingOut -> FullScreenLoading()
                        AuthState.LoggedOut, is AuthState.Unreadable -> LoginScreen(container.watchLogin, unreadable = state is AuthState.Unreadable)
                        is AuthState.LoggedIn -> LoggedIn(container, nav, state.obtainedAtEpochMs)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
    }
}

@Composable
private fun LoggedIn(
    container: AppContainer,
    nav: WearNavigator,
    obtainedAtEpochMs: Long,
) {
    val loginGeneration by container.loginGeneration.collectAsStateWithLifecycle()
    val playlistsVersion by container.playlistsVersion.collectAsStateWithLifecycle()
    val likedSongsVersion by container.likedSongsVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val player = remember { PlayerActions(scope, container.player) }
    // 曲を選んで再生したら、再生画面へ（スマホのように下の小さな再生欄が無いので）
    val play =
        remember {
            object {
                fun song(song: xyz.mooma0750.wave.core.music.Song) {
                    player.playSong(song)
                    nav.openNowPlaying()
                }

                fun from(
                    songs: List<xyz.mooma0750.wave.core.music.Song>,
                    index: Int,
                    playlistId: String? = null,
                    continuation: String? = null,
                    shuffle: Boolean = false,
                ) {
                    player.playFrom(songs, index, playlistId, continuation, shuffle)
                    nav.openNowPlaying()
                }
            }
        }
    val actions =
        remember {
            SongActions(
                playNext = { song -> scope.launch { container.player.addNext(song) } },
                addToQueue = { song -> scope.launch { container.player.addToEnd(song) } },
                openArtist = { artist -> artist.browseId?.let { nav.push(WearScreen.Artist(it, artist.name)) } },
                openAlbum = { album -> album.browseId?.let { nav.push(WearScreen.Album(it, album.title)) } },
                addToPlaylist = { song -> nav.push(WearScreen.AddToPlaylist(song)) },
                startRadio = { song -> play.song(song) },
                openSong = { song -> nav.push(WearScreen.SongPage(song)) },
            )
        }
    LaunchedEffect(loginGeneration) {
        container.loadLikedIds()
        container.releases.load()
    }
    val likedIds by container.likedIds.collectAsStateWithLifecycle()
    val newReleases by container.releases.recent.collectAsStateWithLifecycle()
    val account = rememberAccountInfo(container, obtainedAtEpochMs)
    val like by container.player.likeState.collectAsState()
    // 再生が始まった曲がどのプレイリストに入っているかを先に調べておく（スマホと同じ）
    LaunchedEffect(like.videoId, playlistsVersion) { like.videoId?.let(container::prefetchMembership) }
    LaunchedEffect(nav.current) { Log.i(WaveApplication.LOG_TAG, "wear nav screen=${nav.current::class.simpleName} depth=${nav.stack.size}") }
    // 確認用: adb から画面を開く（スマホと同じ口。時計の画面に置き換える）
    LaunchedEffect(container) {
        DebugActions.navigation.collect { screen ->
            when (screen) {
                is xyz.mooma0750.wave.navigation.Screen.Artist -> WearScreen.Artist(screen.browseId, screen.name)
                is xyz.mooma0750.wave.navigation.Screen.Album -> WearScreen.Album(screen.browseId, screen.title)
                is xyz.mooma0750.wave.navigation.Screen.Playlist -> WearScreen.Playlist(screen.summary, screen.initialQuery)
                is xyz.mooma0750.wave.navigation.Screen.Song -> WearScreen.SongPage(screen.song)
                is xyz.mooma0750.wave.navigation.Screen.Mood -> WearScreen.Mood(screen.title, screen.params)
                xyz.mooma0750.wave.navigation.Screen.Search -> WearScreen.Search
                xyz.mooma0750.wave.navigation.Screen.History -> WearScreen.History
                xyz.mooma0750.wave.navigation.Screen.Settings -> WearScreen.Settings
                xyz.mooma0750.wave.navigation.Screen.Connect -> WearScreen.Devices
            }.let(nav::push)
        }
    }
    val likedTitle = stringResource(R.string.main_liked_songs)

    WearNavHost(nav) { screen ->
        when (screen) {
            WearScreen.Home -> HomeMenu(container.player, hasDevices = true, onOpen = nav::push)
            WearScreen.NowPlaying ->
                NowPlayingScreen(
                    container.player,
                    container.playlistMembership,
                    onOpenQueue = { nav.push(WearScreen.Queue) },
                    onOpenMenu = { nav.push(WearScreen.SongPage(it)) },
                    onAddToPlaylist = { nav.push(WearScreen.AddToPlaylist(it)) },
                )
            WearScreen.Queue -> QueueScreen(container.player, onOpenSong = { song, itemId -> nav.push(WearScreen.SongPage(song, queueItemId = itemId)) })
            WearScreen.Lyrics -> LyricsScreen(container.player, container.lyrics)
            WearScreen.Volume -> VolumeScreen()
            WearScreen.Search ->
                SearchScreen(
                    viewModel(key = "search-$loginGeneration") { SearchViewModel(container.music, container.searchHistory) },
                    container.searchHistory,
                    likedIds,
                    actions,
                    onPlaySong = play::song,
                    onOpenBrowse = { nav.push(WearScreen.Browse) },
                )
            WearScreen.Browse -> BrowseScreen(viewModel(key = "moods-$loginGeneration") { MoodsViewModel(container.music) }) { title, params -> nav.push(WearScreen.Mood(title, params)) }
            is WearScreen.Mood ->
                MoodScreen(screen.title, viewModel(key = "mood-$loginGeneration-${screen.params}") { MoodCategoryViewModel(container.music, screen.params) }, actions) { nav.push(WearScreen.Playlist(it)) }
            WearScreen.Feed ->
                FeedScreen(
                    viewModel(key = "home-feed-$loginGeneration") { HomeFeedViewModel(container.music) },
                    actions,
                    onOpenPlaylist = { nav.push(WearScreen.Playlist(it)) },
                    newReleases = newReleases,
                    onOpenReleases = { nav.push(WearScreen.Releases) },
                )
            WearScreen.Releases -> ReleasesScreen(newReleases, actions)
            WearScreen.Library ->
                LibraryScreen(
                    viewModel(key = "library-tab-$loginGeneration-$playlistsVersion") { LibraryTabViewModel(container.music, container.libraryCache) },
                    onOpenPlaylist = { nav.push(WearScreen.Playlist(it)) },
                    onOpenArtist = { id, name -> nav.push(WearScreen.Artist(id, name)) },
                    onOpenAlbum = { id, title -> nav.push(WearScreen.Album(id, title)) },
                    onCreatePlaylist = { container.playlistEditor.create(it, null) },
                )
            WearScreen.History ->
                HistoryScreen(viewModel(key = "history-$loginGeneration") { HistoryViewModel(container.music) }, likedIds, actions) { songs, index -> play.from(songs, index) }
            is WearScreen.Playlist -> {
                val playlist = screen.summary
                val version = if (playlist.playlistId == MusicRepository.LIKED_SONGS) likedSongsVersion else 0
                PlaylistScreen(
                    playlist,
                    viewModel(key = "playlist-$loginGeneration-${playlist.playlistId}-$version-$playlistsVersion-${screen.initialQuery}") {
                        PlaylistViewModel(container.music, playlist.playlistId, playlist.title, screen.initialQuery, container.settings.playlistOrders)
                    },
                    likedIds,
                    actions,
                    onPlay = { songs, index, continuation, shuffle -> play.from(songs, index, playlist.playlistId, continuation, shuffle) },
                    onQueue = player::queue,
                    onSongMenu = { song, setVideoId -> nav.push(WearScreen.SongPage(song, playlistId = setVideoId?.let { playlist.playlistId }, setVideoId = setVideoId)) },
                )
            }
            is WearScreen.Artist ->
                ArtistScreen(
                    screen.name,
                    viewModel(key = "artist-$loginGeneration-${screen.browseId}-$likedSongsVersion") {
                        ArtistViewModel(container.music, screen.browseId, container.likedSongs, likedSongsVersion, container.channelPosts)
                    },
                    likedIds,
                    actions,
                    newReleases.filter { it.artistId == screen.browseId },
                    onPlay = { request -> play.from(request.songs, request.index, request.playlistId, request.continuation, request.shuffle) },
                    onOpenAllSongs = { id, name -> nav.push(WearScreen.Playlist(PlaylistSummary(id, name, null, null))) },
                    onOpenLiked = { name -> nav.push(WearScreen.Playlist(PlaylistSummary(MusicRepository.LIKED_SONGS, likedTitle, null, null), initialQuery = name)) },
                    onOpenAlbum = { id, title -> nav.push(WearScreen.Album(id, title)) },
                )
            is WearScreen.Album ->
                AlbumScreen(
                    screen.title,
                    viewModel(key = "album-$loginGeneration-${screen.browseId}") { AlbumViewModel(container.music, screen.browseId) },
                    likedIds,
                    actions,
                    onPlay = { page, index, shuffle -> play.from(page.songs, index, page.playlistId, shuffle = shuffle) },
                    onQueue = { page, next -> player.queue(page.songs, next) },
                )
            is WearScreen.SongPage ->
                SongPage(
                    screen.song,
                    queueItemId = screen.queueItemId,
                    isCurrent = like.videoId == screen.song.videoId,
                    connection = container.player,
                    actions = actions,
                    onPlay = play::song,
                    onLyrics = { nav.push(WearScreen.Lyrics) },
                    onVolume = { nav.push(WearScreen.Volume) },
                    onRemoveFromPlaylist =
                        screen.setVideoId?.let { setVideoId ->
                            { container.playlistEditor.remove(screen.playlistId!!, screen.song, setVideoId) }
                        },
                    onDone = nav::back,
                )
            is WearScreen.AddToPlaylist -> {
                val song = screen.song
                val likedNow = if (like.videoId == song.videoId) like.status?.let { it == LikeStatus.Like } else null
                AddToPlaylistScreen(
                    song,
                    viewModel(key = "library-$loginGeneration-${song.videoId}") { LibraryViewModel(container.music, container.libraryCache) },
                    container.playlistMembership,
                    liked = likedNow,
                    onToggleLiked = { likedNow?.let { now -> scope.launch { container.player.setLiked(song.videoId, !now) } } },
                    onPick = { playlist, onResult -> container.playlistEditor.add(playlist, song, onResult) },
                    onUnpick = { playlist, onResult -> container.playlistEditor.removeSong(playlist, song, onResult) },
                    onCreate = { title ->
                        container.playlistEditor.create(title, song)
                        nav.back()
                    },
                )
            }
            WearScreen.Settings -> SettingsScreen(container, account, onOpenDevices = { nav.push(WearScreen.Devices) })
            WearScreen.Devices -> {
                val (playerState, _) = xyz.mooma0750.wave.nowplaying.rememberPlayerUiState(container.player)
                xyz.mooma0750.wave.wear.DevicesScreen(container.watchRemote, watchHasQueue = playerState.queue.isNotEmpty())
            }
            WearScreen.Ai -> CenterText(stringResource(R.string.wear_coming_soon))
        }
    }
}
