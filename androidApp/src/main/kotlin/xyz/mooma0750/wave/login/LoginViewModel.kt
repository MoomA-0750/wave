package xyz.mooma0750.wave.login

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.WebViewCredentials
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.auth.SaveResult
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LoginUiState {
    data object Browsing : LoginUiState

    data object Saving : LoginUiState

    data object Done : LoginUiState

    data class Failed(
        val reason: FailureReason,
    ) : LoginUiState
}

enum class FailureReason { NoSapisid, NoVisitorData, SaveFailed, RendererGone }

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
                when {
                    credentials == null -> LoginUiState.Failed(FailureReason.NoSapisid)
                    // visitorData が無いと後から補う経路が無く、/player が通らないことがある。保存せずにやり直してもらう
                    credentials.visitorData == null -> LoginUiState.Failed(FailureReason.NoVisitorData)
                    else ->
                        when (container.authManager.completeLogin(credentials)) {
                            SaveResult.Saved -> LoginUiState.Done
                            is SaveResult.Failed -> LoginUiState.Failed(FailureReason.SaveFailed)
                        }
                }
            WebViewCredentials.clear()
            mutableUiState.value = next
            if (next == LoginUiState.Done) container.onLoginCompleted()
        }
    }

    /** WebView のレンダラが落ちた。 */
    fun onRendererGone() {
        if (!extracted.get()) mutableUiState.value = LoginUiState.Failed(FailureReason.RendererGone)
    }

    /** 値を取る前に WebView が破棄された（途中でやめた・レンダラが落ちた）。Google にはログイン済みかもしれないので cookie を消す。 */
    fun onAbandoned() {
        if (!extracted.get()) container.appScope.launch { WebViewCredentials.clear() }
    }

    /** 失敗後にやり直すとき。 */
    fun retry() {
        extracted.set(false)
        mutableUiState.value = LoginUiState.Browsing
    }

    /** 保存済みのログイン情報を消してからやり直す（読めない保存物・保存に失敗し続ける場合の、利用者の操作による脱出口）。 */
    fun discardSavedAndRetry() {
        container.appScope.launch {
            val cleared = container.authManager.discardSaved()
            Log.i(WaveApplication.LOG_TAG, "discard saved login cleared=$cleared")
            retry()
        }
    }
}
