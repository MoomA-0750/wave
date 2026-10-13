package xyz.mooma0750.wave

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.connect.LegacyConnectCleanup
import xyz.mooma0750.wave.connect2.ConnectNotices
import xyz.mooma0750.wave.connect2.ConnectV2Manager
import xyz.mooma0750.wave.connect2.PairingV2Controller
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.AuthState
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.mooma0750.wave.core.auth.KeystoreCredentialStore
import xyz.mooma0750.wave.core.lyrics.LrclibClient
import xyz.mooma0750.wave.core.lyrics.LyricsRepository
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.nowplaying.MusicVideos
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.stream.PlaybackHistory
import xyz.mooma0750.wave.core.stream.StreamComponents
import xyz.mooma0750.wave.library.LikedSongsCache
import xyz.mooma0750.wave.library.PlaylistEditor
import xyz.mooma0750.wave.library.PlaylistMembership
import xyz.mooma0750.wave.playback.AtomicQueueFile
import xyz.mooma0750.wave.playback.ConnectV2Host
import xyz.mooma0750.wave.playback.HandoffHost
import xyz.mooma0750.wave.playback.MediaHttp
import xyz.mooma0750.wave.playback.PlayerConnection
import xyz.mooma0750.wave.playback.QueueStore
import xyz.mooma0750.wave.playback.ResolvedStreamCache
import xyz.mooma0750.wave.search.SearchHistoryStore
import xyz.mooma0750.wave.settings.AppSettings

/** プロセスで1つだけ作る（同じファイルに DataStore を2つ作ると例外になる）。 */
private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

class WaveApplication :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // ログイン用 WebView のデータは、このプロセスで WebView を初めて使う前に毎回消す
        // （取得の途中でプロセスが落ちた場合の後始末を兼ねる）。
        // 注意: suffix はプロセスごとに1回しか設定できず、このプロセスのすべての WebView に効く。
        // 起動時の削除と WebViewCredentials.clear() もプロセス全体に効くので、ログイン以外の用途で WebView を足すなら別プロセスにすること。
        deleteLoginWebViewData(this)
        WebView.setDataDirectorySuffix(LOGIN_WEBVIEW_SUFFIX)
        container = AppContainer(this)
        container.appScope.launch { container.authManager.restore() }
        // ログインしていれば、登録したアーティストの新作の確かめを予約する（設定で切っていれば取り消す）
        container.appScope.launch {
            kotlinx.coroutines.flow.combine(container.authManager.state, container.settings.releaseNotices) { auth, enabled -> auth is AuthState.LoggedIn && enabled }
                .distinctUntilChanged()
                .collect { xyz.mooma0750.wave.releases.ReleaseWatcher.schedule(this@WaveApplication, it) }
        }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    container.startedActivities++
                    // 画面が前面の間は中継 v2 につなぎ、他の端末の再生を表示する（Phase 6b、D-11）
                    if (container.startedActivities == 1) container.connectV2?.relay?.acquire()
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    val before = container.startedActivities
                    container.startedActivities = (before - 1).coerceAtLeast(0)
                    if (before == 1) {
                        val relay = container.connectV2?.relay
                        // 画面の回転などで作り直すだけなら、すぐ切らない（つなぎ直しを避ける、レビュー p6b34 低-5）。数は後で必ず戻す
                        if (activity.isChangingConfigurations) {
                            container.appScope.launch {
                                kotlinx.coroutines.delay(CONFIGURATION_CHANGE_GRACE_MS)
                                relay?.release()
                            }
                        } else {
                            relay?.release()
                        }
                    }
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: android.os.Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            },
        )
    }

    /** サムネイルは、認証情報を持たない音声用と同じクライアントで取る（ADR-0003）。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.mediaHttpClient })) }
            .build()
            .also { loader ->
                container.imageCacheCleaner = {
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }

    companion object {
        const val LOGIN_WEBVIEW_SUFFIX = "login"
        const val LOG_TAG = "wave"

        /** YouTube に送る言語（画面の言語に合わせる）。日本語の画面なら ja、それ以外は en。 */
        fun contentLanguage(resources: android.content.res.Resources): String =
            if (resources.configuration.locales[0].language == "ja") "ja" else "en"

        /** 画面の作り直しの間、中継 v2 の接続を残しておく時間。 */
        const val CONFIGURATION_CHANGE_GRACE_MS = 5_000L

        /**
         * ログイン用 WebView が作るファイル。名前は Pixel 3（Android 12）で確かめた。
         * 認証情報が入るのは前の2つ（cookie・ストレージ・HTTP キャッシュ）。後の2つは訪問したオリジンの記録と計測用の領域で、痕跡として消す。
         */
        fun deleteLoginWebViewData(context: Context) {
            File(context.dataDir, "app_webview_$LOGIN_WEBVIEW_SUFFIX").deleteRecursively()
            File(context.cacheDir, "webview_$LOGIN_WEBVIEW_SUFFIX").deleteRecursively()
            File(context.noBackupFilesDir, ".webview_$LOGIN_WEBVIEW_SUFFIX").deleteRecursively()
            File(context.dataDir, "shared_prefs/AwOriginVisitLoggerPrefs.xml").delete()
        }
    }
}

/** 手動 DI。認証情報に触れる部品はここでだけ組み立てる。 */
class AppContainer(
    context: Context,
) {
    val appContext: Context = context.applicationContext
    val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** 画面のライフサイクルより長く走らせる処理（ログイン完了・ログアウト・通信）用。メインスレッドは使わない。 */
    val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    // 例外文には値が入りうるので、種類だけを記録する
                    Log.e(WaveApplication.LOG_TAG, "uncaught in appScope type=${error::class.qualifiedName}")
                },
        )

    val authManager = AuthManager(KeystoreCredentialStore(context.credentialDataStore))

    // OkHttp に Interceptor・Authenticator・CookieJar を足すと、ガードの後でヘッダが付くので足さない
    private val guardedEngine =
        AuthGuardEngine(
            // 接続の失敗で POST を黙って送り直す設定（Ktor の既定は true）を切る。プレイリストへの追加・作成が重複しないように。
            // ただし OkHttp は 503 + Retry-After: 0 と、まとめた接続での 421 は、この設定に関わらず送り直す（サーバーが処理していないと示す応答）。
            // 読み取りの一時的な失敗は innertubex の再試行で補う
            delegate = OkHttp.create { config { retryOnConnectionFailure(false) } },
            onViolation = { host -> Log.w(WaveApplication.LOG_TAG, "auth headers blocked for host=$host") },
            onSetCookieNames = { host, names -> Log.i(WaveApplication.LOG_TAG, "set-cookie host=$host names=$names") },
        )

    val httpClient: HttpClient = createWaveHttpClient(guardedEngine)

    // 画面が日本語なので、YouTube の見出しやジャンル名も日本語にそろえる（端末が英語でも混ざらないように）
    val session = WaveSession(httpClient, authManager, appScope, rethrowViolations = isDebuggable, contentLanguage = WaveApplication.contentLanguage(appContext.resources))

    /** ストリームの解決（WaveSession のガード付きクライアントを使う）。期限はスリープ中も進む時計で測る。 */
    val stream = StreamComponents.create(session, nowMs = SystemClock::elapsedRealtime, trackCipherPath = isDebuggable)

    /** 音声バイト・サムネイルの取得用。認証情報を持たない（ADR-0003）。 */
    val mediaHttpClient = MediaHttp.create()

    /** RSS のポッドキャストのフィードと音声の取得用（ADR-0012）。認証情報を持たず、https の間だけリダイレクトを追う。 */
    val feedHttpClient = xyz.mooma0750.wave.playback.MediaHttp.createForFeeds()

    /** RSS のポッドキャストの検索（Apple の目録）とフィードの読み取り。 */
    val podcastFeeds =
        xyz.mooma0750.wave.core.music.PodcastFeedClient(
            HttpClient(OkHttp.create { preconfigured = feedHttpClient }),
            country = java.util.Locale.getDefault().country.ifBlank { "JP" },
            userAgent = "wave/${BuildConfig.VERSION_NAME}",
            fetchFeed = { url, agent -> xyz.mooma0750.wave.podcast.FeedFetch.fetch(feedHttpClient, url, agent) },
        )

    /** RSS で登録した番組と聴いたところ（この端末だけ）。 */
    val feedLibrary: xyz.mooma0750.wave.core.music.FeedLibrary by lazy { xyz.mooma0750.wave.podcast.FeedStore.create(appContext) }

    /** ライブ配信の検索と HLS の解決。www.youtube.com へは認証情報を持たないクライアントで送る（ADR-0003）。 */
    val live =
        xyz.mooma0750.wave.core.music.LiveClient(
            HttpClient(OkHttp.create { preconfigured = mediaHttpClient }),
            hl = java.util.Locale.getDefault().language.ifBlank { "ja" },
            gl = java.util.Locale.getDefault().country.ifBlank { "JP" },
            nowMs = SystemClock::elapsedRealtime,
        )

    /** 解決したライブの再生先（HLS の媒体プレイリストを読み直すたびに /player を呼ばない）。 */
    val liveCache = xyz.mooma0750.wave.playback.LiveStreamCache(SystemClock::elapsedRealtime)

    val music = MusicRepository(session, live)

    /** 歌詞。LRCLIB へは認証情報を持たないクライアントで、曲名・アーティスト名・アルバム名・長さだけを送る。 */
    val lyrics =
        LyricsRepository(
            music,
            LrclibClient(HttpClient(OkHttp.create { preconfigured = mediaHttpClient }), userAgent = "wave/${BuildConfig.VERSION_NAME}"),
        )

    /** アーティストの YouTube のチャンネルの投稿（ライブの告知）。認証情報を持たないクライアントで読む。 */
    val channelPosts =
        xyz.mooma0750.wave.core.music.ChannelPostsClient(
            HttpClient(OkHttp.create { preconfigured = mediaHttpClient }),
            hl = java.util.Locale.getDefault().language.ifBlank { "ja" },
            gl = java.util.Locale.getDefault().country.ifBlank { "JP" },
        )

    /** 自アプリの UI のコントローラーを見分けるための、プロセスごとの値（接続ヒントで渡す）。 */
    val controllerToken: String = UUID.randomUUID().toString()

    /**
     * 通知の評価ボタンの PendingIntent に入れる、プロセスごとの値（決定 G-2）。他アプリの明示的 Intent と区別する。
     * 通知の内容を読める権限を持つアプリには読まれうる。UI のコントローラーの値とは分ける。ログに出さない
     */
    val notificationCommandToken: String = UUID.randomUUID().toString()

    private val mutableLikedSongsVersion = MutableStateFlow(0)

    /** 高く評価した曲が変わった（かもしれない）たびに増える。一覧を読み直すキーに使う。 */
    val likedSongsVersion: StateFlow<Int> = mutableLikedSongsVersion.asStateFlow()

    /** 前に読んだライブラリ（画面をすぐ出すため）。 */
    val libraryCache = xyz.mooma0750.wave.library.LibraryCache()

    /** 曲がどのプレイリストに入っているかの確認（プレイリストが変わるまで使い回す）。 */
    val playlistMembership = PlaylistMembership(music)

    private var membershipPrefetch: kotlinx.coroutines.Job? = null

    /** 再生が始まった曲について、どのプレイリストに入っているかを先に調べておく（追加の画面をすぐ出し、✓の印を付ける）。 */
    fun prefetchMembership(videoId: String) {
        membershipPrefetch?.cancel()
        membershipPrefetch =
            appScope.launch {
                if (authManager.state.value !is xyz.mooma0750.wave.core.auth.AuthState.LoggedIn) return@launch
                val playlists = libraryCache.playlists ?: (music.libraryPlaylists() as? xyz.mooma0750.wave.core.session.CallResult.Ok)?.value?.also { libraryCache.playlists = it } ?: return@launch
                playlistMembership.prefetch(playlists.filter { it.editable }.map { it.playlistId }, videoId)
            }
    }

    /** 高く評価した曲の読み込み（アーティストのページで使う）。評価が変わるまで使い回す。 */
    val likedSongs = LikedSongsCache(music)

    private val mutableLikedIds = MutableStateFlow<Set<String>>(emptySet())

    /** 高く評価した曲の ID（一覧の曲にバッジを付けるのに使う）。読む前・読めなければ空。 */
    val likedIds: StateFlow<Set<String>> = mutableLikedIds.asStateFlow()

    private var likedIdsJob: kotlinx.coroutines.Job? = null

    /** 高く評価した曲の ID を読む（まだ読んでいない・評価が変わった後）。読み込みは likedSongs と共有する。 */
    fun loadLikedIds() {
        if (likedIdsJob?.isActive == true) return
        likedIdsJob =
            appScope.launch {
                val version = likedSongsVersion.value
                val result = likedSongs.songs(version)
                if (version == likedSongsVersion.value) mutableLikedIds.value = result.songs.mapTo(HashSet()) { it.videoId }
            }
    }

    /**
     * 評価が変わった（かもしれない）。曲と向きが分かれば、手元の ID の集合をすぐ直す（一覧を全部読み直さない）。
     */
    fun onLikedSongsChanged(
        videoId: String? = null,
        liked: Boolean? = null,
    ) {
        mutableLikedSongsVersion.update { it + 1 }
        if (videoId != null && liked != null) mutableLikedIds.update { if (liked) it + videoId else it - videoId }
    }

    private val mutablePlaylistsVersion = MutableStateFlow(0)

    /** プレイリストを編集した（かもしれない）たびに増える。ライブラリとプレイリストの表示を読み直すキーに使う。 */
    val playlistsVersion: StateFlow<Int> = mutablePlaylistsVersion.asStateFlow()

    /** プレイリストを変えた（Claude の道具など、PlaylistEditor を通らない変更）。 */
    fun onPlaylistsChanged() {
        mutablePlaylistsVersion.update { it + 1 }
    }

    val playlistEditor = PlaylistEditor(context.applicationContext, music, appScope, onChanged = { mutablePlaylistsVersion.update { it + 1 } }, membership = playlistMembership)

    /** UI から再生サービスへの接続。 */
    val player = PlayerConnection(context, controllerToken)

    /** 解決済みのストリーム URL。ログアウトで消す。 */
    val streamCache = ResolvedStreamCache(stream.resolver, SystemClock::elapsedRealtime)

    /** 映像の表示用に解決した URL（音声とは別に持つ）。ログアウトで消す。 */
    val videoStreamCache = ResolvedStreamCache(stream.resolver, SystemClock::elapsedRealtime)

    /** 曲に重ねる映像（MV）。 */
    val musicVideos = MusicVideos(music, stream.resolver, videoStreamCache, mediaHttpClient)

    val settings = AppSettings(context)

    /** 登録したアーティストの新作。 */
    val releases = xyz.mooma0750.wave.releases.Releases(appContext, music, authManager, settings)

    /** Claude の中継へ直接送る（スマホ）。 */
    private val directAiTransport by lazy { xyz.mooma0750.wave.ai.DirectAiTransport(settings) }

    /** 時計は tailnet の中の中継に届かないので、スマホに中継してもらう。 */
    val phoneRelayAiTransport by lazy { xyz.mooma0750.wave.ai.PhoneRelayAiTransport(appContext) }

    private val aiTransport: xyz.mooma0750.wave.ai.AiTransport
        get() = if (appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) phoneRelayAiTransport else directAiTransport

    /** スマホ: 時計からの Claude への頼みを中継する。 */
    val aiRelayHost by lazy { xyz.mooma0750.wave.ai.AiRelayHost(appContext, directAiTransport, authManager, appScope) }

    /** Claude との会話（tools/ai-bridge の中継を通す）。会話は端末の中の履歴（files/ai_history.json）に残す。 */
    val aiChat: xyz.mooma0750.wave.ai.AiChat by lazy {
        val res = appContext.resources
        val texts =
            object : xyz.mooma0750.wave.ai.AiTools.Texts {
                override fun searched(query: String) = res.getString(R.string.ai_searched, query)

                override fun played(count: Int) = res.getQuantityString(R.plurals.ai_played, count, count)

                override fun queued(count: Int) = res.getQuantityString(R.plurals.ai_queued, count, count)

                override fun created(title: String, count: Int) = res.getQuantityString(R.plurals.ai_created, count, title, count)

                override fun added(title: String, count: Int) = res.getQuantityString(R.plurals.ai_added, count, title, count)

                override fun readLiked() = res.getString(R.string.ai_read_liked)
            }
        xyz.mooma0750.wave.ai.AiChat(aiTransport, xyz.mooma0750.wave.ai.AiTools(this, texts), appScope, xyz.mooma0750.wave.ai.AiHistoryStore(java.io.File(appContext.filesDir, "ai_history.json"), appScope))
    }

    /** スマホと時計の間の再生の移動の、再生サービスへの入口（Phase 6 で作った形）。 */
    val handoffHost = HandoffHost()

    /** Connect v2 の、再生サービスへの入口（Phase 6b）。 */
    val connectV2Host = ConnectV2Host()

    /** この端末の身元（Keystore の署名鍵）。 */
    val deviceIdentity = DeviceIdentity()

    init {
        // v1 の連携（ADR-0009、2026-10-14 に削除）が残したペアの一覧とそれを包む Keystore の鍵、debug の確認用のファイルを消す。
        // どれも v1 だけのもので、v2 は読まない（身元の鍵 DeviceIdentity は v2 も使うので消さない）
        appScope.launch(Dispatchers.IO) { LegacyConnectCleanup.run(context) }
    }

    /** 前面にある Activity の数（引き取った曲の再生を始めてよいか）。 */
    @Volatile
    var startedActivities = 0

    /** Connect v2（Phase 6b）。中継 v2 の場所が設定されていなければ null。 */
    val connectV2: ConnectV2Manager? =
        // 時計は tailnet の中の中継に届かない（つなぎ直しを繰り返して電池を使う）ので持たない。ほかの端末の再生はスマホを通して操作する
        if (BuildConfig.RELAY_V2_URL.isNotEmpty() && BuildConfig.RELAY_V2_HOST.isNotEmpty() && !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) {
            ConnectV2Manager(
                identity = deviceIdentity,
                session = session,
                host = connectV2Host,
                appScope = appScope,
                baseUrl = BuildConfig.RELAY_V2_URL,
                relayHost = BuildConfig.RELAY_V2_HOST,
                storageFile = java.io.File(context.noBackupFilesDir, "connect-v2.bin"),
                labelsFile = java.io.File(context.noBackupFilesDir, "connect-v2-labels.json"),
                uiInForeground = { startedActivities > 0 },
                resources = appContext.resources,
            )
        } else {
            null
        }

    init {
        // RSS の登録と聴いたところを、家の端末の間で合わせる（ADR-0013）
        connectV2?.let { manager ->
            appScope.launch { manager.libraryUpdates.collect { feedLibrary.apply(it) } }
            appScope.launch {
                // 登録の変化はすぐ、聴いたところだけの変化は30秒に1回まで書く（再生中は15秒ごとに変わる）
                var sent: xyz.mooma0750.wave.core.music.FeedLibraryState? = null
                var sentAtMs = 0L
                var pending: kotlinx.coroutines.Job? = null
                feedLibrary.state.collect { state ->
                    val subscriptionsChanged = sent == null || sent?.subscriptions != state.subscriptions || sent?.removed != state.removed
                    val now = SystemClock.elapsedRealtime()
                    pending?.cancel()
                    if (subscriptionsChanged || now - sentAtMs >= LIBRARY_POSITIONS_EVERY_MS) {
                        sent = state
                        sentAtMs = now
                        manager.publishLibrary(state.toBody())
                    } else {
                        pending =
                            appScope.launch {
                                kotlinx.coroutines.delay(LIBRARY_POSITIONS_EVERY_MS - (now - sentAtMs))
                                val latest = feedLibrary.state.value
                                sent = latest
                                sentAtMs = SystemClock.elapsedRealtime()
                                manager.publishLibrary(latest.toBody())
                            }
                    }
                }
            }
        }
        // Connect v2: 裏にいるときに他の端末の再生で止まったら、通知で知らせる（Toast は裏では出ない、Opus 低-5）
        connectV2?.let { manager ->
            appScope.launch {
                manager.events.collect { event ->
                    if (startedActivities != 0) return@collect
                    // 裏で再生ボタンを押して鳴らさなかったときも知らせる（D-9、レビュー p6b34 中-5）
                    ConnectNotices.background(event)?.let { notifyConnect(appContext.getString(it.title), appContext.getString(it.text)) }
                }
            }
        }
    }

    private companion object {
        const val LIBRARY_POSITIONS_EVERY_MS = 30_000L
        const val CONNECT_CHANNEL_ID = "connect"
        const val CONNECT_NOTIFICATION_ID = 4201
    }

    private fun notifyConnect(
        title: String,
        text: String,
    ) {
        val manager = androidx.core.app.NotificationManagerCompat.from(appContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            androidx.core.app.NotificationChannelCompat.Builder(CONNECT_CHANNEL_ID, androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW).setName(appContext.getString(R.string.app_notification_channel_connect)).build(),
        )
        val intent = android.app.PendingIntent.getActivity(appContext, 0, android.content.Intent(appContext, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification =
            androidx.core.app.NotificationCompat
                .Builder(appContext, CONNECT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(CONNECT_NOTIFICATION_ID, notification) }
    }

    val pairingV2: PairingV2Controller? =
        connectV2?.let { manager ->
            PairingV2Controller(deviceIdentity, manager, session, appScope, android.os.Build.MODEL, appContext.resources)
        }

    /** スマホから時計へのログインの受け渡し（ADR-0011）。 */
    val watchLogin = xyz.mooma0750.wave.watchlink.WatchLogin(context, authManager, appScope)

    /** スマホと時計の間の再生の操作と受け渡し。 */
    val watchRemote by lazy { xyz.mooma0750.wave.watchlink.WatchRemote(context, this, appScope) }

    /** 再生履歴の送信（ADR-0006）。 */
    val playbackHistory = PlaybackHistory(session)

    /** 次に開いたときに戻すキュー。ログアウトで消す。 */
    val queueStore = QueueStore.create(context)

    /** 検索の履歴（語と、検索から開いたアーティスト・アルバム）。ログアウトで消す。 */
    val searchHistory =
        SearchHistoryStore(
            AtomicQueueFile(java.io.File(context.noBackupFilesDir, "search-history.json")),
            appScope,
        )

    init {
        // 起動時・ログアウト後にログインしていなければ、再生サービスが動いていなくても保存したキューを消す
        appScope.launch {
            authManager.state.collect { state ->
                if (state is AuthState.LoggedOut || state is AuthState.Unreadable) {
                    queueStore.delete()
                    searchHistory.clear()
                }
            }
        }
        // ログインが反映されたら、プレイヤーの設定を先に取っておく（最初の再生を速くする）
        appScope.launch {
            session.appliedObtainedAtEpochMs.filterNotNull().collect { stream.resolver.prewarm() }
        }
    }

    /** WaveApplication が設定する（Coil のキャッシュを消す）。 */
    var imageCacheCleaner: () -> Unit = {}

    private val mutableLoginGeneration = MutableStateFlow(0)

    /** ログインが完了するたびに増える。ログイン画面の ViewModel をログインごとに作り直すキーに使う。 */
    val loginGeneration: StateFlow<Int> = mutableLoginGeneration.asStateFlow()

    fun onLoginCompleted() {
        mutableLoginGeneration.update { it + 1 }
    }

    /** ログアウトの唯一の入口。保存物を消し、WebView 側の後始末もする。両方消せたら true。 */
    suspend fun logout(): Boolean {
        // 先に消す（ログアウトの完了を待つ間にプロセスが終わっても、前のアカウントのキュー・検索履歴を残さない）
        queueStore.delete()
        searchHistory.clear()
        val cleared = authManager.logout()
        // 再生サービスが動いていなくても、前のアカウントで解決した URL とサムネイルを残さない
        streamCache.clear()
        videoStreamCache.clear()
        liveCache.clear()
        settings.clearPlaylistOrders()
        likedIdsJob?.cancel()
        mutableLikedIds.value = emptySet()
        playlistMembership.clear()
        libraryCache.clear()
        // 前のアカウントの会話を残さない（履歴も消す）
        aiChat.wipe()
        releases.clear()
        imageCacheCleaner()
        WebViewCredentials.clear()
        Log.i(WaveApplication.LOG_TAG, "logout cleared=$cleared")
        return cleared
    }
}
