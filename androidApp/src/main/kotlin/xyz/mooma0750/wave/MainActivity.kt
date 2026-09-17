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
import androidx.compose.material3.MaterialTheme
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
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.artist.AlbumScreen
import xyz.mooma0750.wave.artist.AlbumViewModel
import xyz.mooma0750.wave.artist.ArtistScreen
import xyz.mooma0750.wave.artist.ArtistViewModel
import xyz.mooma0750.wave.debug.DebugActions
import xyz.mooma0750.wave.navigation.Screen
import xyz.mooma0750.wave.navigation.SongActions
import xyz.mooma0750.wave.home.HomeScreen
import xyz.mooma0750.wave.login.LoginScreen
import xyz.mooma0750.wave.login.LoginViewModel
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.library.LibraryViewModel
import xyz.mooma0750.wave.library.PlaylistScreen
import xyz.mooma0750.wave.library.PlaylistViewModel
import xyz.mooma0750.wave.nowplaying.MiniPlayer
import xyz.mooma0750.wave.search.SearchScreen
import xyz.mooma0750.wave.search.SearchViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DebugActions.handle(intent, (application as WaveApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        DebugActions.handle(intent, container)
        setContent {
            MaterialTheme {
                val authState by container.authManager.state.collectAsStateWithLifecycle()
                // ログインが完了するたびに増える。ログイン画面の ViewModel をログインごとに作り直す（完了済みの状態を持ち越さない）
                val loginGeneration by container.loginGeneration.collectAsStateWithLifecycle()
                // 失効したときに、ログイン済みのまま再ログイン画面を出す。プロセスが復元されたら出さない（保存しない）
                var reLogin by remember { mutableStateOf(false) }
                // 仮の画面遷移（Phase 4 で作り直す）。ホームの上に画面を積む
                val stack = remember { mutableStateListOf<Screen>() }
                val scope = rememberCoroutineScope()
                val songActions =
                    remember {
                        SongActions(
                            playNext = { song -> scope.launch { container.player.addNext(song) } },
                            addToQueue = { song -> scope.launch { container.player.addToEnd(song) } },
                            openArtist = { artist -> artist.browseId?.let { stack += Screen.Artist(it, artist.name) } },
                            openAlbum = { album -> album.browseId?.let { stack += Screen.Album(it, album.title) } },
                        )
                    }
                val miniPlayer: @Composable () -> Unit = { MiniPlayer(container.player, songActions) }
                LaunchedEffect(container) {
                    DebugActions.navigation.collect { stack += it }
                }

                when (val state = authState) {
                    AuthState.Restoring -> Progress()
                    AuthState.LoggingOut -> Progress("ログアウトしています…")
                    is AuthState.LoggedIn -> {
                        // ログインし直したら、前のログインで開いた画面を閉じる（最初の表示では閉じない）
                        var shownGeneration by remember { mutableStateOf(loginGeneration) }
                        LaunchedEffect(loginGeneration) {
                            reLogin = false
                            if (shownGeneration != loginGeneration) stack.clear()
                            shownGeneration = loginGeneration
                        }
                        if (stack.isNotEmpty()) BackHandler { stack.removeAt(stack.lastIndex) }
                        val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        if (reLogin) {
                            BackHandler { reLogin = false }
                            LoginScreen(
                                viewModel(key = "relogin-$loginGeneration") { LoginViewModel(container) },
                                onCancel = { reLogin = false },
                            )
                        } else {
                            when (val screen = stack.lastOrNull()) {
                                null -> {
                                    val library = viewModel(key = "library-$loginGeneration") { LibraryViewModel(container.music) }
                                    val libraryState by library.state.collectAsStateWithLifecycle()
                                    HomeScreen(
                                        container,
                                        obtainedAtEpochMs = state.obtainedAtEpochMs,
                                        onReLogin = { reLogin = true },
                                        onSearch = { stack += Screen.Search },
                                        library = libraryState,
                                        onOpenPlaylist = { stack += Screen.Playlist(it) },
                                        songActions = songActions,
                                    )
                                }
                                Screen.Search ->
                                    SearchScreen(
                                        viewModel(key = "search-$loginGeneration") { SearchViewModel(container.music) },
                                        onSongClick = { song -> scope.launch { container.player.playSong(song) } },
                                        actions = songActions,
                                        onBack = pop,
                                        bottomBar = miniPlayer,
                                    )
                                is Screen.Playlist -> {
                                    val playlist = screen.summary
                                    // 高く評価した曲は、評価を変えたら読み直す
                                    val likedVersion by container.likedSongsVersion.collectAsStateWithLifecycle()
                                    val version = if (playlist.playlistId == xyz.mooma0750.wave.core.music.MusicRepository.LIKED_SONGS) likedVersion else 0
                                    PlaylistScreen(
                                        viewModel(key = "playlist-$loginGeneration-${playlist.playlistId}-$version") { PlaylistViewModel(container.music, playlist.playlistId, playlist.title) },
                                        onPlay = { songs, index, continuation -> scope.launch { container.player.playFrom(songs, index, playlist.playlistId, continuation) } },
                                        onShufflePlay = { songs, continuation ->
                                            scope.launch { container.player.playFrom(songs, 0, playlist.playlistId, continuation, shuffle = true) }
                                        },
                                        actions = songActions,
                                        onBack = pop,
                                        bottomBar = miniPlayer,
                                    )
                                }
                                is Screen.Artist ->
                                    ArtistScreen(
                                        viewModel(key = "artist-$loginGeneration-${screen.browseId}") { ArtistViewModel(container.music, screen.browseId) },
                                        title = screen.name,
                                        actions = songActions,
                                        // 曲の棚は5曲だけなので、最後まで行ったら最後の曲からラジオにつなぐ
                                        onPlay = { songs, index -> scope.launch { container.player.playFrom(songs, index, playlistId = null, continuation = null) } },
                                        onOpenAllSongs = { id, name -> stack += Screen.Playlist(PlaylistSummary(id, name, null, null)) },
                                        onOpenAlbum = { album -> stack += Screen.Album(album.browseId, album.title) },
                                        onBack = pop,
                                        bottomBar = miniPlayer,
                                    )
                                is Screen.Album ->
                                    AlbumScreen(
                                        viewModel(key = "album-$loginGeneration-${screen.browseId}") { AlbumViewModel(container.music, screen.browseId) },
                                        title = screen.title,
                                        actions = songActions,
                                        onPlay = { page, index, shuffle ->
                                            scope.launch { container.player.playFrom(page.songs, index, page.playlistId, continuation = null, shuffle = shuffle) }
                                        },
                                        onBack = pop,
                                        bottomBar = miniPlayer,
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
