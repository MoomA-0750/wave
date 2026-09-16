package xyz.mooma0750.wave.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data object Restoring : AuthState

    data object LoggedOut : AuthState

    /** 保存物はあるが読めなかった。UI は再ログインを促す。 */
    data class Unreadable(val reason: UnreadableReason) : AuthState

    /** Credentials は UI 層へ運ばない。 */
    data class LoggedIn(val obtainedAtEpochMs: Long) : AuthState
}

/** ログイン状態の唯一の持ち主。操作は1つの Mutex で直列化し、後から来た操作が勝つ。 */
class AuthManager(
    private val store: CredentialStore,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Restoring)
    private val mutableCredentials = MutableStateFlow<Credentials?>(null)

    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    /** WaveSession 向け。state と同じ臨界区間で、credentials → state の順に更新する。 */
    internal val credentials: StateFlow<Credentials?> = mutableCredentials.asStateFlow()

    /** 起動時に1回。先に completeLogin・logout が終わっていたら何もしない。 */
    suspend fun restore() {
        mutex.withLock {
            if (mutableState.value != AuthState.Restoring) return
            when (val result = store.load()) {
                is LoadResult.Found -> publish(result.credentials, AuthState.LoggedIn(result.credentials.obtainedAtEpochMs))
                LoadResult.Empty -> publish(null, AuthState.LoggedOut)
                is LoadResult.Unreadable -> publish(null, AuthState.Unreadable(result.reason))
            }
        }
    }

    /** 保存できたときだけ LoggedIn にする（再起動で消えるログインを作らない）。 */
    suspend fun completeLogin(credentials: Credentials): SaveResult =
        mutex.withLock {
            val result = store.save(credentials)
            if (result == SaveResult.Saved) {
                publish(credentials, AuthState.LoggedIn(credentials.obtainedAtEpochMs))
            }
            result
        }

    /** 通信から先に認証を外し、その後に保存物を消す。 */
    suspend fun logout() {
        mutex.withLock {
            publish(null, AuthState.LoggedOut)
            store.clear()
        }
    }

    private fun publish(
        credentials: Credentials?,
        state: AuthState,
    ) {
        mutableCredentials.value = credentials
        mutableState.value = state
    }
}
