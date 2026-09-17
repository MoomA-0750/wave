package xyz.mooma0750.wave.core.auth

/** 認証情報の永続化。実装はプラットフォームごと（Android は Keystore + DataStore）。 */
interface CredentialStore {
    /** 例外を投げない。 */
    suspend fun load(): LoadResult

    /** 失敗は SaveResult で返す。例外文に値を含めない。 */
    suspend fun save(credentials: Credentials): SaveResult

    /** 暗号文 → 鍵 の順に消す。途中で失敗しても、呼ばれるたびに残りを消しにいく。両方消せたら true。 */
    suspend fun clear(): Boolean
}

sealed interface LoadResult {
    class Found(val credentials: Credentials) : LoadResult

    data object Empty : LoadResult

    /** 保存物はあるが読めない。保存物は消さない（再ログインで上書きされる）。 */
    data class Unreadable(val reason: UnreadableReason) : LoadResult
}

enum class UnreadableReason {
    /** 鍵が無い・永久に無効化された。 */
    KeyUnavailable,

    /** 認証タグの検証に失敗した。 */
    Tampered,

    /** 復号はできたが中身が壊れている。 */
    Malformed,

    /** 新しい版のアプリが書いた。 */
    UnsupportedSchema,

    /** Keystore・ディスクの一時的な失敗。次の起動で読めるかもしれない。 */
    Transient,
}

sealed interface SaveResult {
    data object Saved : SaveResult

    data class Failed(val transient: Boolean) : SaveResult
}
