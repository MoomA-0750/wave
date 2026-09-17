package xyz.mooma0750.wave

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** ログイン用 WebView に残る Google の cookie とストレージの後始末。 */
object WebViewCredentials {
    /** このプロセスでログイン用 WebView を作ったか。作っていなければ、後始末で Chromium を読み込ませない。 */
    @Volatile
    var used: Boolean = false
        private set

    fun markUsed() {
        used = true
    }

    /** cookie とストレージを消す。プロセス全体に効く。ディレクトリは次の起動で消える（WaveApplication）。 */
    suspend fun clear() {
        if (!used) return
        withContext(Dispatchers.Main + NonCancellable) {
            try {
                val cookies = CookieManager.getInstance()
                suspendCancellableCoroutine { continuation -> cookies.removeAllCookies { continuation.resume(Unit) } }
                cookies.flush()
                WebStorage.getInstance().deleteAllData()
            } catch (error: RuntimeException) {
                // WebView の更新中などで使えない。ディレクトリは次の起動で消える
                Log.w(WaveApplication.LOG_TAG, "webview cleanup failed type=${error::class.simpleName}")
            }
        }
    }
}
