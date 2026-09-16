package xyz.mooma0750.wave

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
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
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.KeystoreCredentialStore
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.WaveSession
import java.io.File

/** プロセスで1つだけ作る（同じファイルに DataStore を2つ作ると例外になる）。 */
private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

class WaveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // ログイン用 WebView のデータは、このプロセスで WebView を初めて使う前に毎回消す
        // （取得の途中でプロセスが落ちた場合の後始末を兼ねる）
        deleteLoginWebViewData(this)
        WebView.setDataDirectorySuffix(LOGIN_WEBVIEW_SUFFIX)
        container = AppContainer(this)
        container.appScope.launch { container.authManager.restore() }
    }

    companion object {
        const val LOGIN_WEBVIEW_SUFFIX = "login"

        /** このプロセスでログイン用 WebView を作ったか。作っていなければ、ログアウト時に WebView を初期化しない。 */
        @Volatile
        var loginWebViewUsed: Boolean = false
            private set

        fun markLoginWebViewUsed() {
            loginWebViewUsed = true
        }
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
}
