package xyz.mooma0750.wave.core.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AuthState {
    data object Restoring : AuthState

    data object LoggedOut : AuthState

    /** 通信からは認証を外したが、端末の保存物をまだ消している。消し終わるまで「ログアウトした」と表示しない。 */
    data object LoggingOut : AuthState

    /** 保存物はあるが読めなかった。UI は再ログインを促し、手動で消してやり直す手段も出す。 */
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

    /**
     * 公開する前に、同じ臨界区間で呼ぶ（WaveSession が世代を進める、ADR-0007）。
     * state を購読する側（再生サービスなど）が LoggedIn を見た時点で、WaveSession の世代は新しいログインに進んでいる。
     */
    internal var beforePublish: (suspend (Credentials?) -> Unit)? = null

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

    /**
     * 保存できたときだけ LoggedIn にする（再起動で消えるログインを作らない）。
     * 保存と反映は途中でキャンセルさせない（ディスクだけがログイン済みになるのを防ぐ）。
     */
    suspend fun completeLogin(credentials: Credentials): SaveResult =
        mutex.withLock {
            withContext(NonCancellable) {
                val result = store.save(credentials)
                if (result == SaveResult.Saved) {
                    publish(credentials, AuthState.LoggedIn(credentials.obtainedAtEpochMs))
                }
                result
            }
        }

    /**
     * 通信から先に認証を外し（LoggingOut）、保存物を消し終えてから LoggedOut にする。
     * 消去は途中でキャンセルさせない。プロセスが途中で終わった場合は、次の起動でログイン状態に戻る
     * （UI は消し終わるまで「ログアウトした」と表示しないので、利用者にはそう見える）。
     *
     * @return 暗号文と鍵の両方を消せたら true
     */
    suspend fun logout(): Boolean =
        mutex.withLock {
            withContext(NonCancellable) {
                publish(null, AuthState.LoggingOut)
                val cleared = store.clear()
                publish(null, AuthState.LoggedOut)
                cleared
            }
        }

    /**
     * 読めない保存物や、保存に失敗し続ける状態から抜けるために、利用者の操作で保存物を消す。
     * 自動では呼ばない（一時的な失敗で有効な認証情報を消さないため）。
     */
    suspend fun discardSaved(): Boolean = logout()

    private suspend fun publish(
        credentials: Credentials?,
        state: AuthState,
    ) {
        beforePublish?.invoke(credentials)
        mutableCredentials.value = credentials
        mutableState.value = state
    }
}
