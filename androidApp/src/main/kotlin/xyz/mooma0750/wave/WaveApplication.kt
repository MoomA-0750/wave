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
import xyz.mooma0750.wave.playback.MediaHttp
import xyz.mooma0750.wave.playback.ResolvedStreamCache
import java.io.File

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
    }

    /** サムネイルは、認証情報を持たない音声用と同じクライアントで取る（ADR-0003）。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.mediaHttpClient })) }
            .build()

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
            delegate = OkHttp.create(),
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

    /** UI から再生サービスへの接続。 */
    val player = PlayerConnection(context)

    /** 解決済みのストリーム URL。ログアウトで消す。 */
    val streamCache = ResolvedStreamCache(stream.resolver, SystemClock::elapsedRealtime)

    init {
        // ログインが反映されたら、プレイヤーの設定を先に取っておく（最初の再生を速くする）
        appScope.launch {
            session.appliedObtainedAtEpochMs.filterNotNull().collect { stream.resolver.prewarm() }
        }
    }

    private val mutableLoginGeneration = MutableStateFlow(0)

    /** ログインが完了するたびに増える。ログイン画面の ViewModel をログインごとに作り直すキーに使う。 */
    val loginGeneration: StateFlow<Int> = mutableLoginGeneration.asStateFlow()

    fun onLoginCompleted() {
        mutableLoginGeneration.update { it + 1 }
    }

    /** ログアウトの唯一の入口。保存物を消し、WebView 側の後始末もする。両方消せたら true。 */
    suspend fun logout(): Boolean {
        val cleared = authManager.logout()
        WebViewCredentials.clear()
        Log.i(WaveApplication.LOG_TAG, "logout cleared=$cleared")
        return cleared
    }
}
