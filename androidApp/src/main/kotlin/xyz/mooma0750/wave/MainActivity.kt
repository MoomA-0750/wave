package xyz.mooma0750.wave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import xyz.mooma0750.wave.ui.WaveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.artist.AlbumScreen
import xyz.mooma0750.wave.artist.AlbumViewModel
import xyz.mooma0750.wave.artist.ArtistScreen
import xyz.mooma0750.wave.artist.ArtistViewModel
import xyz.mooma0750.wave.connect.ConnectScreen
import xyz.mooma0750.wave.connect2.ConnectNotices
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.debug.DebugActions
import xyz.mooma0750.wave.home.HomeFeedViewModel
import xyz.mooma0750.wave.home.HomeScreen
import xyz.mooma0750.wave.library.AddToPlaylistDialog
import xyz.mooma0750.wave.library.HistoryScreen
import xyz.mooma0750.wave.library.HistoryViewModel
import xyz.mooma0750.wave.library.LibraryViewModel
import xyz.mooma0750.wave.library.PlaylistScreen
import xyz.mooma0750.wave.library.PlaylistViewModel
import xyz.mooma0750.wave.login.LoginScreen
import xyz.mooma0750.wave.login.LoginViewModel
import xyz.mooma0750.wave.navigation.AppNavigator
import xyz.mooma0750.wave.navigation.AppShell
import xyz.mooma0750.wave.navigation.PlayerActions
import xyz.mooma0750.wave.navigation.Tab
import xyz.mooma0750.wave.library.LibraryTabScreen
import xyz.mooma0750.wave.library.LibraryTabViewModel
import xyz.mooma0750.wave.search.MoodCategoryScreen
import xyz.mooma0750.wave.search.MoodCategoryViewModel
import xyz.mooma0750.wave.search.MoodsViewModel
import xyz.mooma0750.wave.search.SearchBrowseScreen
import xyz.mooma0750.wave.navigation.Screen
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.nowplaying.MiniPlayer
import xyz.mooma0750.wave.nowplaying.NowPlayingScreen
import xyz.mooma0750.wave.search.SearchHistoryEntry
import xyz.mooma0750.wave.search.SearchScreen
import xyz.mooma0750.wave.search.SearchViewModel
import xyz.mooma0750.wave.settings.SettingsScreen
import xyz.mooma0750.wave.settings.rememberAccountInfo
import androidx.compose.ui.res.stringResource
import xyz.mooma0750.wave.R

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DebugActions.handle(intent, (application as WaveApplication).container)
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i(WaveApplication.LOG_TAG, "notification permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        DebugActions.handle(intent, container)
        // Android 13 以降は、許可が無いと再生中の通知（通知センター・ロック画面・時計）が出ない
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            WaveTheme {
                val authState by container.authManager.state.collectAsStateWithLifecycle()
                // ログインが完了するたびに増える。ログイン画面の ViewModel をログインごとに作り直す（完了済みの状態を持ち越さない）
                val loginGeneration by container.loginGeneration.collectAsStateWithLifecycle()
                // 失効したときに、ログイン済みのまま再ログイン画面を出す。プロセスが復元されたら出さない（保存しない）
                var reLogin by remember { mutableStateOf(false) }
                // 下のタブと、タブごとの画面の積み重ね（Spotify と同じ）
                val nav = remember { AppNavigator() }
                // プレイリストに追加する曲（ダイアログを出す）
                var addTarget by remember { mutableStateOf<Song?>(null) }
                val playlistsVersion by container.playlistsVersion.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val songActions =
                    remember {
                        SongActions(
                            playNext = { song -> scope.launch { container.player.addNext(song) } },
                            addToQueue = { song -> scope.launch { container.player.addToEnd(song) } },
                            openArtist = { artist -> artist.browseId?.let { nav.push(Screen.Artist(it, artist.name)) } },
                            openAlbum = { album -> album.browseId?.let { nav.push(Screen.Album(it, album.title)) } },
                            addToPlaylist = { song -> addTarget = song },
                        )
                    }
                val playerActions = remember { PlayerActions(scope, container.player) }
                LaunchedEffect(container) {
                    DebugActions.navigation.collect { nav.push(it) }
                }
                // 端末の連携の結果（引き取り・解除など）は、どの画面にいても短く知らせる（仮の表示）
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(container) {
                    container.connect.events.collect { message -> xyz.mooma0750.wave.ui.Notices.show(message) }
                }
                LaunchedEffect(container) {
                    container.connectV2?.events?.collect { event ->
                        ConnectNotices.toast(event, context.resources)?.let(xyz.mooma0750.wave.ui.Notices::show)
                    }
                }

                when (val state = authState) {
                    AuthState.Restoring -> Progress()
                    AuthState.LoggingOut -> Progress(stringResource(R.string.main_logging_out))
                    is AuthState.LoggedIn -> {
                        // ログインし直したら、前のログインで開いた画面を閉じる（最初の表示では閉じない）
                        var shownGeneration by remember { mutableStateOf(loginGeneration) }
                        LaunchedEffect(loginGeneration) {
                            reLogin = false
                            if (shownGeneration != loginGeneration) {
                                nav.clearAll()
                                addTarget = null
                            }
                            shownGeneration = loginGeneration
                        }
                        BackHandler(enabled = nav.canGoBack) { nav.back() }
                        val account = rememberAccountInfo(container, state.obtainedAtEpochMs)
                        // どの画面にいるかだけを記録する（実機の確認用。中身や ID は出さない）
                        LaunchedEffect(nav.tab, nav.current) {
                            android.util.Log.i(WaveApplication.LOG_TAG, "nav tab=${nav.tab} screen=${nav.current?.let { it::class.simpleName } ?: "root"}")
                        }
                        val pop: () -> Unit = nav::back
                        if (reLogin) {
                            BackHandler { reLogin = false }
                            LoginScreen(
                                viewModel(key = "relogin-$loginGeneration") { LoginViewModel(container) },
                                onCancel = { reLogin = false },
                            )
                        } else {
                            AppShell(
                                nav,
                                miniPlayer = { open -> MiniPlayer(container.player, container.connectV2, onOpen = open, onOpenDevices = { nav.push(Screen.Connect) }) },
                                nowPlaying = { close ->
                                    NowPlayingScreen(
                                        connect = container.connectV2,
                                        connection = container.player,
                                        lyrics = container.lyrics,
                                        videos = container.musicVideos,
                                        actions = songActions.closing(close),
                                        onClose = close,
                                        onOpenDevices = {
                                            close()
                                            nav.push(Screen.Connect)
                                        },
                                    )
                                },
                            ) {
                            when (val screen = nav.current) {
                                null -> when (nav.tab) {
                                Tab.Search ->
                                    SearchBrowseScreen(
                                        viewModel(key = "moods-$loginGeneration") { MoodsViewModel(container.music) },
                                        onOpenSearch = { nav.push(Screen.Search) },
                                        onOpenCategory = { nav.push(Screen.Mood(it.title, it.params)) },
                                    )
                                Tab.Library ->
                                    LibraryTabScreen(
                                        viewModel(key = "library-tab-$loginGeneration-$playlistsVersion") { LibraryTabViewModel(container.music) },
                                        account = account,
                                        onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
                                        onOpenArtist = { nav.push(Screen.Artist(it.browseId, it.name)) },
                                        onOpenAlbum = { nav.push(Screen.Album(it.browseId, it.title)) },
                                        onCreatePlaylist = { container.playlistEditor.create(it, null) },
                                    )
                                Tab.Home ->
                                    HomeScreen(
                                        account = account,
                                        feed = viewModel(key = "home-feed-$loginGeneration") { HomeFeedViewModel(container.music) },
                                        onPlaySong = playerActions::playSong,
                                        onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
                                        songActions = songActions,
                                        onOpenHistory = { nav.push(Screen.History) },
                                        onOpenSettings = { nav.push(Screen.Settings) },
                                        onConnect = { nav.push(Screen.Connect) },
                                        showConnect = container.connectConfigured || container.connectV2 != null,
                                    )
                                }
                                is Screen.Mood ->
                                    MoodCategoryScreen(
                                        screen.title,
                                        viewModel(key = "mood-$loginGeneration-${screen.params}") { MoodCategoryViewModel(container.music, screen.params) },
                                        onPlaySong = playerActions::playSong,
                                        onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
                                        actions = songActions,
                                        onBack = pop,
                                    )
                                Screen.Settings ->
                                    SettingsScreen(container, account, onReLogin = { reLogin = true }, onConnect = { nav.push(Screen.Connect) }, onBack = pop)
                                Screen.Connect ->
                                    ConnectScreen(container, onBack = pop)
                                Screen.History ->
                                    HistoryScreen(
                                        viewModel(key = "history-$loginGeneration") { HistoryViewModel(container.music) },
                                        onPlay = { songs, index -> playerActions.playFrom(songs, index) },
                                        actions = songActions,
                                        onBack = pop,
                                    )
                                Screen.Search -> {
                                    // 検索から開いたアーティスト・アルバムも履歴に残す（Spotify と同じで、あとから辿れる）
                                    val searchActions =
                                        remember(songActions) {
                                            songActions.copy(
                                                openArtist = { artist ->
                                                    artist.browseId?.let { container.searchHistory.add(SearchHistoryEntry.Artist(artist.name, it)) }
                                                    songActions.openArtist(artist)
                                                },
                                                openAlbum = { album ->
                                                    album.browseId?.let { container.searchHistory.add(SearchHistoryEntry.Album(album.title, it)) }
                                                    songActions.openAlbum(album)
                                                },
                                            )
                                        }
                                    SearchScreen(
                                        viewModel(key = "search-$loginGeneration") { SearchViewModel(container.music, container.searchHistory) },
                                        onSongClick = playerActions::playSong,
                                        actions = searchActions,
                                        onBack = pop,
                                        history = container.searchHistory,
                                    )
                                }
                                is Screen.Playlist -> {
                                    val playlist = screen.summary
                                    // 高く評価した曲は、評価を変えたら読み直す
                                    val likedVersion by container.likedSongsVersion.collectAsStateWithLifecycle()
                                    val version = if (playlist.playlistId == MusicRepository.LIKED_SONGS) likedVersion else 0
                                    PlaylistScreen(
                                        summary = playlist,
                                        viewModel = viewModel(key = "playlist-$loginGeneration-${playlist.playlistId}-$version-$playlistsVersion-${screen.initialQuery}") {
                                            PlaylistViewModel(container.music, playlist.playlistId, playlist.title, screen.initialQuery, container.settings.playlistOrders)
                                        },
                                        onPlay = { songs, index, continuation -> playerActions.playFrom(songs, index, playlist.playlistId, continuation) },
                                        onShufflePlay = { songs, continuation -> playerActions.playFrom(songs, 0, playlist.playlistId, continuation, shuffle = true) },
                                        onQueue = playerActions::queue,
                                        actions = songActions,
                                        onRemove = { song, setVideoId -> container.playlistEditor.remove(playlist.playlistId, song, setVideoId) },
                                        onBack = pop,
                                    )
                                }
                                is Screen.Artist -> {
                                    val likedSongsVersion by container.likedSongsVersion.collectAsStateWithLifecycle()
                                    val likedSongsTitle = stringResource(R.string.main_liked_songs)
                                    ArtistScreen(
                                        viewModel(key = "artist-$loginGeneration-${screen.browseId}-$likedSongsVersion") {
                                            ArtistViewModel(container.music, screen.browseId, container.likedSongs, likedSongsVersion)
                                        },
                                        title = screen.name,
                                        actions = songActions,
                                        // 曲の棚は5曲だけなので、最後まで行ったら最後の曲からラジオにつなぐ
                                        onPlay = { songs, index -> playerActions.playFrom(songs, index) },
                                        onOpenAllSongs = { id, name -> nav.push(Screen.Playlist(PlaylistSummary(id, name, null, null))) },
                                        // 「高く評価した曲」を、そのアーティストで絞った状態で開く
                                        onOpenLiked = { name ->
                                            nav.push(Screen.Playlist(PlaylistSummary(MusicRepository.LIKED_SONGS, likedSongsTitle, null, null), initialQuery = name))
                                        },
                                        onOpenAlbum = { album -> nav.push(Screen.Album(album.browseId, album.title)) },
                                        onBack = pop,
                                    )
                                }
                                is Screen.Album ->
                                    AlbumScreen(
                                        viewModel(key = "album-$loginGeneration-${screen.browseId}") { AlbumViewModel(container.music, screen.browseId) },
                                        title = screen.title,
                                        actions = songActions,
                                        onPlay = { page, index, shuffle -> playerActions.playFrom(page.songs, index, page.playlistId, shuffle = shuffle) },
                                        onQueue = { page, next -> playerActions.queue(page.songs, next) },
                                        onBack = pop,
                                    )
                            }
                            }
                            addTarget?.let { song ->
                                AddToPlaylistDialog(
                                    viewModel(key = "library-$loginGeneration-$playlistsVersion") { LibraryViewModel(container.music) },
                                    onDismiss = { addTarget = null },
                                    onPick = { playlist ->
                                        container.playlistEditor.add(playlist, song)
                                        addTarget = null
                                    },
                                    onCreate = { title ->
                                        container.playlistEditor.create(title, song)
                                        addTarget = null
                                    },
                                    song = song,
                                    membership = container.playlistMembership,
                                    playlistsVersion = playlistsVersion,
                                )
                            }
                        }
                    }
                    AuthState.LoggedOut, is AuthState.Unreadable ->
                        LoginScreen(
                            viewModel(key = "login-$loginGeneration") { LoginViewModel(container) },
                            savedLoginUnreadable = state is AuthState.Unreadable,
                        )
                }
            }
        }
    }
}

@Composable
private fun Progress(message: String? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (message != null) Text(message)
    }
}
