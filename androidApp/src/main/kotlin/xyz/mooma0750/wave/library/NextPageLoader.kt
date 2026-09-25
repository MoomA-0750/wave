package xyz.mooma0750.wave.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 続きのページを1つずつ読む。「もっと読む」と「全部読む（中を探す）」が同じ続きを2回読んで、曲が重複して並ばないように、
 * 続きのトークンは順番が来てから読む。
 */
class NextPageLoader {
    private val lock = Mutex()

    /**
     * @param currentToken 今の続きのトークン（順番が来てから読む）。無ければ何もしない
     * @param fetch そのトークンで読む
     * @param apply 読んだ結果を状態に足す（順番の中で行う）
     * @return 読んだ結果。続きが無ければ null
     */
    suspend fun <R> next(
        currentToken: () -> String?,
        fetch: suspend (String) -> R,
        apply: (R) -> Unit,
    ): R? =
        lock.withLock {
            val token = currentToken() ?: return@withLock null
            fetch(token).also(apply)
        }
}
