package xyz.mooma0750.wave.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.ai.AiConversation
import xyz.mooma0750.wave.core.ai.AiHistory
import java.io.File
import java.io.IOException

/**
 * Claude との会話の履歴のファイル（アプリの files/ai_history.json）。端末の中だけに置き、同期しない（Windows・Mac・iPhone も同じ）。
 * 発言と曲の情報だけで、ログインの情報は入れない。読み書きは1本の列で順に行い、書くときは一時ファイルから置き換える。
 */
class AiHistoryStore(
    private val file: File,
    private val scope: CoroutineScope,
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val mutable = MutableStateFlow<List<AiConversation>>(emptyList())

    /** 新しく話した順。 */
    val conversations: StateFlow<List<AiConversation>> = mutable.asStateFlow()

    private val loaded =
        scope.async(io) {
            // 書き換えは読み終わってから行うので、ここでは読んだものをそのまま置く
            mutable.value = runCatching { if (file.exists()) AiHistory.decode(file.readText()) else emptyList() }.getOrDefault(emptyList())
        }

    fun save(conversation: AiConversation) = change { AiHistory.upsert(it, conversation) }

    fun remove(id: String) = change { AiHistory.remove(it, id) }

    /** ログアウトのとき: 全部消す。 */
    fun clear() = change { emptyList() }

    private fun change(transform: (List<AiConversation>) -> List<AiConversation>) {
        scope.launch(io) {
            loaded.await()
            mutable.update(transform)
            write(mutable.value)
        }
    }

    private fun write(list: List<AiConversation>) {
        try {
            if (list.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AiHistory.encode(list))
            if (!tmp.renameTo(file)) throw IOException("rename")
        } catch (error: IOException) {
            Log.i(WaveApplication.LOG_TAG, "ai history save failed type=${error::class.simpleName}")
        }
    }
}
