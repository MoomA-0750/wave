package xyz.mooma0750.wave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import xyz.mooma0750.wave.ui.WaveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.lifecycleScope
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
import xyz.mooma0750.wave.nowplaying.rememberHasPlayback
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
        openFromNotification(intent)
    }

    /** 新作の通知から開いた: そのアルバム（動画ならアーティストのページ）を開く。 */
    private val notificationNavigation = kotlinx.coroutines.channels.Channel<Screen>(capacity = 4)

    private fun openFromNotification(intent: android.content.Intent?) {
        // キューの通知から開いた: 再生画面のキューを出す
        if (intent?.getBooleanExtra(xyz.mooma0750.wave.playback.QueueNotice.EXTRA_OPEN_QUEUE, false) == true) {
            intent.removeExtra(xyz.mooma0750.wave.playback.QueueNotice.EXTRA_OPEN_QUEUE)
            xyz.mooma0750.wave.playback.QueueNotice.requestOpen()
            return
        }
        // ウィジェットから開いた: 再生画面を開く
        if (intent?.getBooleanExtra(xyz.mooma0750.wave.widget.WidgetHooks.EXTRA_OPEN_NOW_PLAYING, false) == true) {
            intent.removeExtra(xyz.mooma0750.wave.widget.WidgetHooks.EXTRA_OPEN_NOW_PLAYING)
            xyz.mooma0750.wave.widget.WidgetHooks.requestOpenNowPlaying()
            return
        }
        val album = intent?.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ALBUM)
        val artist = intent?.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ARTIST)
        when {
            album != null -> notificationNavigation.trySend(Screen.Album(album, intent.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ALBUM_TITLE).orEmpty()))
            artist != null -> notificationNavigation.trySend(Screen.Artist(artist, intent.getStringExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ARTIST_NAME).orEmpty()))
            else -> return
        }
        intent.removeExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ALBUM)
        intent.removeExtra(xyz.mooma0750.wave.releases.ReleaseWatcher.EXTRA_ARTIST)
    }

    // 映像の小窓（PiP）。再生画面で映像を出していれば、ホームへ戻ったときに小窓にする
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 からは setAutoEnterEnabled で自動になるが、それは Pixel のホームの上スワイプに頼っていて、
        // ほかのホームのアプリ・ホームのボタンでは入らないことがある。まだ入っていなければ、ここでも入る
        if (!isInPictureInPictureMode && xyz.mooma0750.wave.nowplaying.VideoPip.showing.value) {
            xyz.mooma0750.wave.nowplaying.VideoPip.enter(this)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        xyz.mooma0750.wave.nowplaying.VideoPip.onModeChanged(isInPictureInPictureMode)
        android.util.Log.i(WaveApplication.LOG_TAG, "video pip=$isInPictureInPictureMode")
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i(WaveApplication.LOG_TAG, "notification permission granted=$granted")
        }

    // Android 12 以前は、設定で固定した画面の言語をここで当てる（13 以降は OS が当てる）
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        DebugActions.handle(intent, container)
        if (savedInstanceState == null) openFromNotification(intent)
        // Android 13 以降は、許可が無いと再生中の通知（通知センター・ロック画面・時計）が出ない
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // 映像を出しているかが変わるたびに、ホームへ戻ったときに小窓にするかを伝える
        lifecycleScope.launch {
            xyz.mooma0750.wave.nowplaying.VideoPip.showing.collect {
                runCatching { setPictureInPictureParams(xyz.mooma0750.wave.nowplaying.VideoPip.params()) }
            }
        }
        setContent {
            WaveTheme {
                xyz.mooma0750.wave.watchlink.WatchLoginDialog(container.watchLogin)
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
                val playerActions = remember { PlayerActions(scope, container.player) }
                val songActions =
                    remember {
                        SongActions(
                            playNext = { song -> scope.launch { container.player.addNext(song) } },
                            addToQueue = { song -> scope.launch { container.player.addToEnd(song) } },
                            openArtist = { artist -> artist.browseId?.let { nav.push(Screen.Artist(it, artist.name)) } },
                            openAlbum = { album -> album.browseId?.let { nav.push(Screen.Album(it, album.title)) } },
                            addToPlaylist = { song -> addTarget = song },
                            // 1曲を選んで再生するのと同じ（その曲から始まるラジオになる）
                            startRadio = playerActions::playSong,
                            openSong = { song -> nav.push(Screen.Song(song)) },
                            // 再生中の曲は再生画面の✓と同じ道を通す（表示がそろう）。ほかの曲は直に書く
                            setLiked = { song, liked ->
                                if (container.player.likeState.value.videoId == song.videoId) {
                                    scope.launch { container.player.setLiked(song.videoId, liked) }
                                } else {
                                    container.playlistEditor.setLiked(song, liked)
                                }
                            },
                            // ダウンロード・ダウンロードを削除（ADR-0016 の 16）
                            setDownloaded = container.downloads?.let { downloads -> { song, on -> if (on) downloads.downloadSong(song) else downloads.removeSong(song.videoId) } },
                        )
                    }
                LaunchedEffect(container) {
                    DebugActions.navigation.collect { nav.push(it) }
                }
                LaunchedEffect(container) {
                    for (screen in notificationNavigation) nav.push(screen)
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                // 時計との間で再生を移した結果
                LaunchedEffect(container) {
                    container.watchRemote.result.drop(1).collect { result ->
                        val text =
                            when (result?.second) {
                                null -> null
                                xyz.mooma0750.wave.watchlink.WatchRemote.Result.Pulled -> context.getString(R.string.phone_remote_pulled)
                                xyz.mooma0750.wave.watchlink.WatchRemote.Result.Pushed -> context.getString(R.string.phone_remote_pushed)
                                xyz.mooma0750.wave.watchlink.WatchRemote.Result.NothingToPull -> context.getString(R.string.wear_remote_nothing)
                                xyz.mooma0750.wave.watchlink.WatchRemote.Result.Failed -> context.getString(R.string.phone_remote_failed)
                            }
                        text?.let(xyz.mooma0750.wave.ui.Notices::show)
                    }
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
                        // 一覧の曲に「高く評価した」のバッジを付けるため、評価した曲の ID を読んでおく
                        LaunchedEffect(loginGeneration) { container.loadLikedIds() }
                        val likedIds by container.likedIds.collectAsStateWithLifecycle()
                        // 取り終えた曲の ID（曲の行の印とメニューに使う）
                        val downloadsState = container.downloads?.state?.collectAsStateWithLifecycle()?.value
                        val downloadedIds = downloadsState?.complete ?: emptySet()
                        val collectionOnlyDownloads =
                            remember(downloadsState?.manifest) {
                                downloadsState?.manifest?.let { m ->
                                    val own = HashSet<String>().apply {
                                        m.singles.forEach { add(it.song.videoId) }
                                        m.smart.forEach { add(it.song.videoId) }
                                    }
                                    m.collections.flatMap { c -> c.songs.map { it.videoId } }.filterNot { it in own }.toSet()
                                } ?: emptySet()
                            }
                        // 登録したアーティストの新作（ホームとアーティストのページに出す）
                        LaunchedEffect(loginGeneration) { container.releases.load() }
                        val newReleases by container.releases.recent.collectAsStateWithLifecycle()
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
                        } else androidx.compose.runtime.CompositionLocalProvider(xyz.mooma0750.wave.ui.LocalLikedIds provides likedIds, xyz.mooma0750.wave.ui.LocalDownloadedIds provides downloadedIds, xyz.mooma0750.wave.ui.LocalCollectionOnlyDownloads provides collectionOnlyDownloads, xyz.mooma0750.wave.library.LocalPlaylistMembership provides container.playlistMembership) {
                            // 再生が始まった曲がどのプレイリストに入っているかを先に調べておく（追加の画面をすぐ出し、評価のボタンに✓を付ける）
                            val playingId = container.player.likeState.collectAsState().value.videoId
                            LaunchedEffect(playingId, playlistsVersion) { playingId?.let(container::prefetchMembership) }
                            // Claude に聞く（再生画面の ✨ からシートで開く。下のバーの「Claude」のタブと同じ会話）
                            var showAi by remember { mutableStateOf(false) }
                            if (showAi) {
                                xyz.mooma0750.wave.ai.AiChatSheet(
                                    container.aiChat,
                                    onPlay = playerActions::playSong,
                                    onOpenSettings = {
                                        showAi = false
                                        nav.push(Screen.Settings)
                                    },
                                    onDismiss = { showAi = false },
                                )
                            }
                            // Spotify と同じく、デバイスのボタンは端末を選ぶだけの画面を出す（設定はその中のリンクから）
                            var showDevices by remember { mutableStateOf(false) }
                            val openDevices: () -> Unit = { if (container.connectV2 != null) showDevices = true else nav.push(Screen.Connect) }
                            if (showDevices) {
                                container.connectV2?.let { manager ->
                                    xyz.mooma0750.wave.connect2.DevicePickerSheet(manager, onDismiss = { showDevices = false }, onOpenSettings = { nav.push(Screen.Connect) }, watch = container.watchRemote, phonePlaying = xyz.mooma0750.wave.nowplaying.rememberPlayerUiState(container.player).first.isPlaying)
                                }
                            }
                            // マイライブラリ（下のタブのライブラリと、デスクトップと同じ並びの左の欄）
                            val library: @Composable (Boolean) -> Unit = { showAvatar ->
                                LibraryTabScreen(
                                    viewModel(key = "library-tab-$loginGeneration-$playlistsVersion") { LibraryTabViewModel(container.music, container.libraryCache, feeds = container.feedLibrary.state) },
                                    account = account,
                                    onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
                                    onOpenArtist = { nav.push(Screen.Artist(it.browseId, it.name)) },
                                    onOpenAlbum = { nav.push(Screen.Album(it.browseId, it.title)) },
                                    onOpenPodcast = { nav.push(Screen.Podcast(it.browseId, it.title)) },
                                    onCreatePlaylist = { container.playlistEditor.create(it, null) },
                                    onOpenDownloads = container.downloads?.let { { nav.push(Screen.Downloads) } },
                                    settings = container.settings,
                                    showAvatar = showAvatar,
                                )
                            }
                            val searchViewModel = @Composable { viewModel(key = "search-$loginGeneration") { SearchViewModel(container.music, container.searchHistory, container.podcastFeeds, container.lyricsSearch) } }
                            // 縦横とも大きい画面では Mac・Windows と同じ並び（上に検索、左にマイライブラリ）
                            var desktopLayout by remember { mutableStateOf(false) }
                            val desktopParts =
                                    xyz.mooma0750.wave.navigation.DesktopParts(
                                        searchField = { modifier ->
                                            xyz.mooma0750.wave.search.DesktopSearchField(searchViewModel(), onOpen = { if (nav.current != Screen.Search) nav.push(Screen.Search) }, modifier)
                                        },
                                        // ホームの左上のアイコンの引き出しと同じ行き先を、メニューで出す（Windows のアカウントのメニューと同じ）
                                        account = { xyz.mooma0750.wave.home.AccountMenuButton(account, onOpenHistory = { nav.push(Screen.History) }, onConnect = { nav.push(Screen.Connect) }, showConnect = container.connectV2 != null, onOpenSettings = { nav.push(Screen.Settings) }) },
                                        library = { library(false) },
                                        queue = { xyz.mooma0750.wave.nowplaying.QueuePane(container.player, container.connectV2, songActions) },
                                        ai = {
                                            xyz.mooma0750.wave.ai.AiChatScreen(
                                                container.aiChat,
                                                onPlay = playerActions::playSong,
                                                onOpenSettings = { nav.push(Screen.Settings) },
                                            )
                                        },
                                        playerBar = { bar ->
                                            xyz.mooma0750.wave.nowplaying.PlayerBar(
                                                container.player,
                                                container.connectV2,
                                                songActions,
                                                onOpenDevices = openDevices,
                                                // 歌詞は中央のページに出す（もう一度押すと閉じる。Windows と同じ）
                                                lyricsShown = nav.current == Screen.Lyrics,
                                                onLyrics = { if (nav.current == Screen.Lyrics) nav.back() else nav.push(Screen.Lyrics) },
                                                queueShown = bar.queueShown,
                                                onQueue = bar.onQueue,
                                                onShowPanel = bar.onShowPanel,
                                            )
                                        },
                                        onActive = { desktopLayout = it },
                                    )
                            AppShell(
                                nav,
                                desktop = desktopParts,
                                hasPlayback = rememberHasPlayback(container.player, container.connectV2),
                                miniPlayer = { open -> MiniPlayer(container.player, container.connectV2, onOpen = open, onOpenDevices = openDevices, actions = songActions) },
                                nowPlaying = { close, inPanel, hidePanel ->
                                    NowPlayingScreen(
                                        connect = container.connectV2,
                                        connection = container.player,
                                        lyrics = container.lyrics,
                                        videos = container.musicVideos,
                                        // 右の列からアーティストなどを開くときは、列はそのまま（全面のときだけ閉じる）
                                        actions = if (inPanel) songActions else songActions.closing(close),
                                        onClose = close,
                                        onOpenDevices = openDevices,
                                        inPanel = inPanel,
                                        onHidePanel = hidePanel,
                                        onAskAi = { showAi = true },
                                        // デスクトップと同じ並びの右の欄では、操作は下の帯にある
                                        controlsInBar = inPanel && desktopLayout,
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
                                Tab.Ai ->
                                    xyz.mooma0750.wave.ai.AiChatScreen(
                                        container.aiChat,
                                        onPlay = playerActions::playSong,
                                        onOpenSettings = { nav.push(Screen.Settings) },
                                    )
                                Tab.Library -> library(true)
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
                                        showConnect = container.connectV2 != null,
                                        newReleases = newReleases,
                                        showAvatar = !desktopLayout,
                                    )
                                }
                                is Screen.Mood ->
                                    MoodCategoryScreen(
                                        screen.title,
                                        viewModel(key = "mood-$loginGeneration-${screen.params}") { MoodCategoryViewModel(container.music, screen.params) },
                                        onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
                                        actions = songActions,
                                        onBack = pop,
                                    )
                                Screen.Settings ->
                                    SettingsScreen(container, account, onReLogin = { reLogin = true }, onConnect = { nav.push(Screen.Connect) }, onBack = pop)
                                Screen.Connect ->
                                    ConnectScreen(container, onBack = pop)
                                Screen.Downloads ->
                                    container.downloads?.let { downloads ->
                                        xyz.mooma0750.wave.download.DownloadsScreen(
                                            downloads,
                                            container.settings,
                                            actions = songActions,
                                            onPlay = { songs, index, shuffle -> playerActions.playFrom(songs, index, shuffle = shuffle) },
                                            onOpenCollection = { collection ->
                                                when (collection.kind) {
                                                    xyz.mooma0750.wave.core.download.CollectionKind.Album -> nav.push(Screen.Album(collection.id, collection.title))
                                                    else -> nav.push(Screen.Playlist(PlaylistSummary(collection.id, collection.title, null, collection.thumbnailUrl)))
                                                }
                                            },
                                            onBack = pop,
                                        )
                                    }
                                Screen.Lyrics -> xyz.mooma0750.wave.nowplaying.LyricsPage(container.player, container.connectV2, container.lyrics, onBack = pop)
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
                                        searchViewModel(),
                                        showField = !desktopLayout,
                                        onSongClick = playerActions::playSong,
                                        actions = searchActions,
                                        onBack = pop,
                                        history = container.searchHistory,
                                        onOpenPodcast = { nav.push(Screen.Podcast(it.browseId, it.title)) },
                                        onOpenPlaylist = { nav.push(Screen.Playlist(it)) },
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
                                            PlaylistViewModel(
                                                container.music,
                                                playlist.playlistId,
                                                playlist.title,
                                                screen.initialQuery,
                                                container.settings.playlistOrders,
                                                // 取ってあるプレイリストは通信なしでも開ける
                                                offline = { container.downloads?.offlineCollection(collectionKindOf(playlist.playlistId), playlist.playlistId)?.songs },
                                            )
                                        },
                                        downloadButton = { title, songs ->
                                            container.downloads?.takeIf { playlist.playlistId != MusicRepository.NEW_EPISODES }?.let { downloads ->
                                                xyz.mooma0750.wave.download.CollectionDownloadButton(downloads, collectionKindOf(playlist.playlistId), playlist.playlistId, title, playlist.thumbnailUrl, songs)
                                            }
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
                                            ArtistViewModel(container.music, screen.browseId, container.likedSongs, likedSongsVersion, container.channelPosts)
                                        },
                                        title = screen.name,
                                        actions = songActions,
                                        // 「すべての曲」の一覧を文脈にして流す（読めなければ人気の曲から、最後の曲のラジオにつなぐ）
                                        onPlay = { play -> playerActions.playFrom(play.songs, play.index, play.playlistId, play.continuation, play.shuffle) },
                                        onOpenAllSongs = { id, name -> nav.push(Screen.Playlist(PlaylistSummary(id, name, null, null))) },
                                        // 「高く評価した曲」を、そのアーティストで絞った状態で開く
                                        onOpenLiked = { name ->
                                            nav.push(Screen.Playlist(PlaylistSummary(MusicRepository.LIKED_SONGS, likedSongsTitle, null, null), initialQuery = name))
                                        },
                                        onOpenAlbum = { album -> nav.push(Screen.Album(album.browseId, album.title)) },
                                        newReleases = newReleases.filter { it.artistId == screen.browseId },
                                        onPlaySong = playerActions::playSong,
                                        onBack = pop,
                                    )
                                }
                                is Screen.Album ->
                                    AlbumScreen(
                                        viewModel(key = "album-$loginGeneration-${screen.browseId}") {
                                            AlbumViewModel(container.music, screen.browseId, offline = {
                                                // 取ってあるアルバムは通信なしでも開ける
                                                container.downloads?.offlineCollection(xyz.mooma0750.wave.core.download.CollectionKind.Album, screen.browseId)?.let { c ->
                                                    xyz.mooma0750.wave.core.music.AlbumPage(c.id, c.title, null, c.songs.firstOrNull()?.artists.orEmpty(), c.thumbnailUrl, null, c.songs)
                                                }
                                            })
                                        },
                                        downloadButton = { page ->
                                            container.downloads?.let { downloads ->
                                                xyz.mooma0750.wave.download.CollectionDownloadButton(downloads, xyz.mooma0750.wave.core.download.CollectionKind.Album, screen.browseId, page.title, page.thumbnailUrl, page.songs)
                                            }
                                        },
                                        title = screen.title,
                                        actions = songActions,
                                        onPlay = { page, index, shuffle -> playerActions.playFrom(page.songs, index, page.playlistId, shuffle = shuffle) },
                                        onQueue = { page, next -> playerActions.queue(page.songs, next) },
                                        onBack = pop,
                                    )
                                is Screen.Podcast ->
                                    xyz.mooma0750.wave.podcast.PodcastScreen(
                                        viewModel(key = "podcast-$loginGeneration-${screen.browseId}") { xyz.mooma0750.wave.podcast.PodcastViewModel(container.music, screen.browseId, screen.title, container.podcastFeeds, container.feedLibrary) },
                                        title = screen.title,
                                        // エピソードとして取る（ダウンロードの画面で曲と分けて出す）
                                        actions = songActions.copy(setDownloaded = container.downloads?.let { downloads -> { song, on -> if (on) downloads.downloadSong(song, episode = true) else downloads.removeSong(song.videoId) } }),
                                        // 押したエピソードから、番組の一覧を続けて流す（読んだ分の後はラジオにつなぐ）
                                        onPlay = { songs, index, playlistId, shuffle -> playerActions.playFrom(songs, index, playlistId, shuffle = shuffle) },
                                        onBack = pop,
                                    )
                                is Screen.Song -> xyz.mooma0750.wave.song.SongScreen(screen.song, songActions, onPlay = playerActions::playSong, onBack = pop)
                            }
                            }
                            addTarget?.let { song ->
                                val like by container.player.likeState.collectAsState()
                                val likeScope = rememberCoroutineScope()
                                val likedNow = if (like.videoId == song.videoId) like.status?.let { it == xyz.mooma0750.wave.core.music.LikeStatus.Like } else null
                                // 開いている間は一覧を作り直さない（足すたびに読み直して並びが動かないように）
                                val openedVersion = remember(song) { playlistsVersion }
                                AddToPlaylistDialog(
                                    viewModel(key = "library-$loginGeneration-$openedVersion") { LibraryViewModel(container.music, container.libraryCache) },
                                    onDismiss = { addTarget = null },
                                    // 押しても閉じない（続けてほかのプレイリストにも足せる）
                                    onPick = { playlist, onResult -> container.playlistEditor.add(playlist, song, onResult) },
                                    onUnpick = { playlist, onResult -> container.playlistEditor.removeSong(playlist, song, onResult) },
                                    onCreate = { title ->
                                        container.playlistEditor.create(title, song)
                                        addTarget = null
                                    },
                                    song = song,
                                    membership = container.playlistMembership,
                                    liked = likedNow,
                                    onToggleLiked = { likedNow?.let { now -> likeScope.launch { container.player.setLiked(song.videoId, !now) } } },
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
        LoadingIndicator()
        if (message != null) Text(message)
    }
}

/** プレイリストの ID から、丸ごと取るものの種類（高く評価した曲は LM）。 */
private fun collectionKindOf(playlistId: String): xyz.mooma0750.wave.core.download.CollectionKind =
    if (playlistId == MusicRepository.LIKED_SONGS) xyz.mooma0750.wave.core.download.CollectionKind.Liked else xyz.mooma0750.wave.core.download.CollectionKind.Playlist
