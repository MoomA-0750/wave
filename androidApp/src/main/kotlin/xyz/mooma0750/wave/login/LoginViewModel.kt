package xyz.mooma0750.wave.login

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.auth.SaveResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed interface LoginUiState {
    data object Browsing : LoginUiState

    data object Saving : LoginUiState

    data object Done : LoginUiState

    data class Failed(
        val reason: FailureReason,
    ) : LoginUiState
}

enum class FailureReason { NoSapisid, SaveFailed, RendererGone }

/**
 * WebView から取った値は、このクラスのメモリ上だけを通って AuthManager へ渡す。
 * SavedStateHandle・rememberSaveable には入れない。
 */
class LoginViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val extracted = AtomicBoolean(false)
    private val mutableUiState = MutableStateFlow<LoginUiState>(LoginUiState.Browsing)
    val uiState: StateFlow<LoginUiState> = mutableUiState.asStateFlow()

    /** 着地を確認した WebView から呼ぶ。Fold の開閉などで2回来ても、処理するのは最初の1回だけ。 */
    fun onLanded(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
    ) {
        if (!extracted.compareAndSet(false, true)) return
        mutableUiState.value = LoginUiState.Saving
        // 画面が閉じても保存と後始末は最後まで走らせる
        container.appScope.launch {
            val credentials = cookie?.let { Credentials.fromLogin(it, visitorData, dataSyncId, System.currentTimeMillis()) }
            Log.i(
                WaveApplication.LOG_TAG,
                "login landed credentials=${credentials != null} visitorData=${visitorData != null} dataSyncId=${dataSyncId != null}",
            )
            val next =
                if (credentials == null) {
                    LoginUiState.Failed(FailureReason.NoSapisid)
                } else {
                    when (container.authManager.completeLogin(credentials)) {
                        SaveResult.Saved -> LoginUiState.Done
                        is SaveResult.Failed -> LoginUiState.Failed(FailureReason.SaveFailed)
                    }
                }
            clearWebViewCredentials()
            mutableUiState.value = next
        }
    }

    /** WebView のレンダラが落ちた。 */
    fun onRendererGone() {
        if (!extracted.get()) mutableUiState.value = LoginUiState.Failed(FailureReason.RendererGone)
    }

    /** 値を取る前に WebView が破棄された（途中でやめた・レンダラが落ちた）。Google にはログイン済みかもしれないので cookie を消す。 */
    fun onAbandoned() {
        if (!extracted.get()) container.appScope.launch { clearWebViewCredentials() }
    }

    /** ログイン画面に入り直したとき。前回のログインが完了済みなら、最初からやり直せる状態に戻す。 */
    fun resetIfDone() {
        if (mutableUiState.value == LoginUiState.Done) retry()
    }

    /** 失敗後にやり直すとき。 */
    fun retry() {
        extracted.set(false)
        mutableUiState.value = LoginUiState.Browsing
    }

    companion object {
        /**
         * WebView 側に残った cookie とストレージを消す。プロセス全体に効く。ディレクトリは次の起動で消える。
         * このプロセスでログイン用 WebView を使っていなければ何もしない（ここで Chromium を読み込ませない）。
         */
        suspend fun clearWebViewCredentials() {
            if (!WaveApplication.loginWebViewUsed) return
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
}
