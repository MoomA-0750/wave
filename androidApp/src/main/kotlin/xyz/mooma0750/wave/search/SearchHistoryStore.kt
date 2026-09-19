package xyz.mooma0750.wave.search

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.playback.QueueFile

/**
 * 検索の履歴の保存（キューの保存と同じ作り: `noBackupFilesDir` の AtomicFile、暗号化しない、書き込みは1つの Mutex で直列）。
 * 中身は検索した語と、検索から開いたアーティスト・アルバムの名前だけ。アカウントを切り替えたら消す。
 */
class SearchHistoryStore(
    private val file: QueueFile,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SearchHistoryEntry.serializer())
    private val lock = Mutex()
    private val mutableEntries = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val entries: StateFlow<List<SearchHistoryEntry>> = mutableEntries

    init {
        scope.launch {
            val loaded = withContext(io) { file.read()?.let { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() } }
            // 読んでいる間に足された分（そちらが新しい）を手前に残す
            if (loaded != null) mutableEntries.value = SearchHistory.merge(mutableEntries.value, loaded)
            Log.i(WaveApplication.LOG_TAG, "search history loaded entries=${mutableEntries.value.size}")
        }
    }

    fun add(entry: SearchHistoryEntry) = save(SearchHistory.add(mutableEntries.value, entry))

    fun remove(entry: SearchHistoryEntry) = save(SearchHistory.remove(mutableEntries.value, entry))

    fun clear() = save(emptyList())

    private fun save(next: List<SearchHistoryEntry>) {
        if (next == mutableEntries.value) return
        mutableEntries.value = next
        scope.launch {
            lock.withLock {
                withContext(io) {
                    if (next.isEmpty()) file.delete() else file.write(json.encodeToString(serializer, next))
                }
            }
        }
    }
}
