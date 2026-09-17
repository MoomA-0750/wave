package xyz.mooma0750.wave

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.KeystoreCredentialStore
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.stream.StreamComponents
import xyz.mooma0750.wave.playback.PlayerConnection
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.stream.PlaybackHistory
import xyz.mooma0750.wave.library.PlaylistEditor
import xyz.mooma0750.wave.settings.AppSettings
import xyz.mooma0750.wave.playback.MediaHttp
import xyz.mooma0750.wave.playback.HandoffHost
import xyz.mooma0750.wave.playback.QueueStore
import xyz.mooma0750.wave.playback.ResolvedStreamCache
import java.io.File
import java.util.UUID

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
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    container.startedActivities++
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    container.startedActivities = (container.startedActivities - 1).coerceAtLeast(0)
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

    val session = WaveSession(httpClient, authManager, appScope, rethrowViolations = isDebuggable)

    /** ストリームの解決（WaveSession のガード付きクライアントを使う）。期限はスリープ中も進む時計で測る。 */
    val stream = StreamComponents.create(session, nowMs = SystemClock::elapsedRealtime, trackCipherPath = isDebuggable)

    /** 音声バイト・サムネイルの取得用。認証情報を持たない（ADR-0003）。 */
    val mediaHttpClient = MediaHttp.create()

    val music = MusicRepository(session)

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

    fun onLikedSongsChanged() {
        mutableLikedSongsVersion.update { it + 1 }
    }

    private val mutablePlaylistsVersion = MutableStateFlow(0)

    /** プレイリストを編集した（かもしれない）たびに増える。ライブラリとプレイリストの表示を読み直すキーに使う。 */
    val playlistsVersion: StateFlow<Int> = mutablePlaylistsVersion.asStateFlow()

    val playlistEditor = PlaylistEditor(context.applicationContext, music, appScope, onChanged = { mutablePlaylistsVersion.update { it + 1 } })

    /** UI から再生サービスへの接続。 */
    val player = PlayerConnection(context, controllerToken)

    /** 解決済みのストリーム URL。ログアウトで消す。 */
    val streamCache = ResolvedStreamCache(stream.resolver, SystemClock::elapsedRealtime)

    val settings = AppSettings(context)

    /** 端末間の受け渡しの、再生サービスへの入口（Phase 6）。 */
    val handoffHost = HandoffHost()

    /** この端末の身元（Keystore の署名鍵）。 */
    val deviceIdentity = xyz.mooma0750.wave.connect.DeviceIdentity()

    /** ペアリングした端末の一覧（信頼の根拠）。 */
    val pairStore = xyz.mooma0750.wave.connect.PairStore(java.io.File(context.noBackupFilesDir, "pairs.bin"))

    /** 中継の場所が設定されているか（local.properties）。無ければ端末間の受け渡しは使えない。 */
    val connectConfigured: Boolean = BuildConfig.RELAY_URL.isNotEmpty() && BuildConfig.RELAY_HOST.isNotEmpty()

    val relayClient = xyz.mooma0750.wave.connect.RelayClient(deviceIdentity, BuildConfig.RELAY_URL, BuildConfig.RELAY_HOST, appScope)

    /** 前面にある Activity の数（引き取った曲の再生を始めてよいか）。 */
    @Volatile
    var startedActivities = 0

    val connect =
        xyz.mooma0750.wave.connect.ConnectManager(
            identity = deviceIdentity,
            pairs = pairStore,
            relay = relayClient,
            session = session,
            host = handoffHost,
            scope = appScope,
            uiInForeground = { startedActivities > 0 },
            ensurePlaybackService = { kotlinx.coroutines.withContext(Dispatchers.Main) { player.controller() } },
        )

    val pairing =
        xyz.mooma0750.wave.connect.PairingController(
            identity = deviceIdentity,
            pairs = pairStore,
            relay = relayClient,
            connect = connect,
            session = session,
            scope = appScope,
            deviceName = android.os.Build.MODEL,
        )

    /** 再生履歴の送信（ADR-0006）。 */
    val playbackHistory = PlaybackHistory(session)

    /** 次に開いたときに戻すキュー。ログアウトで消す。 */
    val queueStore = QueueStore.create(context)

    init {
        // 起動時・ログアウト後にログインしていなければ、再生サービスが動いていなくても保存したキューを消す
        appScope.launch {
            authManager.state.collect { state -> if (state is AuthState.LoggedOut || state is AuthState.Unreadable) queueStore.delete() }
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
        // 先に消す（ログアウトの完了を待つ間にプロセスが終わっても、前のアカウントのキューを残さない）
        queueStore.delete()
        val cleared = authManager.logout()
        // 再生サービスが動いていなくても、前のアカウントで解決した URL とサムネイルを残さない
        streamCache.clear()
        imageCacheCleaner()
        WebViewCredentials.clear()
        Log.i(WaveApplication.LOG_TAG, "logout cleared=$cleared")
        return cleared
    }
}
