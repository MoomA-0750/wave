package xyz.mooma0750.wave.core.queue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * プロセスが終わっても次に開いたときに戻すための、キューの保存形式（F1b）。
 * 視聴データ（曲のメタデータ・出どころ・continuation）で、認証情報は含まない。世代は実行時の値なので保存しない。
 */
@Serializable
data class SavedQueue(
    val version: Int,
    /** 保存したときのログインの取得時刻。今のログインと違えば戻さない（別のアカウントのキューを出さない）。 */
    val obtainedAtEpochMs: Long,
    val items: List<QueueItem>,
    val currentIndex: Int,
    val positionMs: Long,
    /** Media3 の repeatMode の値（0 オフ・1 1曲・2 全体）。 */
    val repeatMode: Int,
    val source: QueueSource?,
    val continuation: String?,
    val radioPlaylistId: String?,
    val exhausted: Boolean,
    /** シャッフル中なら、シャッフルを入れる前の並び（項目 ID）。切っていれば null。前の形式のファイルには無い（切った状態で戻す）。 */
    val unshuffledOrder: List<String>? = null,
) {
    companion object {
        const val VERSION = 1
        private val json =
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
            }

        fun encode(saved: SavedQueue): String = json.encodeToString(serializer(), saved)

        /** 読めない・形式のバージョンが違う・中身が矛盾している（空、位置が範囲外）なら null。 */
        fun decode(text: String): SavedQueue? {
            val saved = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            if (saved.version != VERSION) return null
            if (saved.items.isEmpty() || saved.currentIndex !in saved.items.indices) return null
            if (saved.repeatMode !in 0..2 || saved.positionMs < 0) return null
            return saved
        }
    }
}
