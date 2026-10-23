package xyz.mooma0750.wave.download

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.download.PlayCount
import xyz.mooma0750.wave.core.download.PlayCounts
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.playback.AtomicQueueFile

/**
 * この端末で聴いた回数（ADR-0016 の 10。曲ごとの回数・最後に聴いた時刻・曲の情報。2000 曲まで）。
 * `noBackupFilesDir/play_counts.json` に置く。ログアウトで消す。
 */
class PlayCountStore(
    dir: File,
) {
    private val file = AtomicQueueFile(File(dir, "play_counts.json"))
    private val lock = Mutex()
    private var cached: List<PlayCount>? = null

    suspend fun all(): List<PlayCount> = lock.withLock { load() }

    suspend fun record(
        song: Song,
        nowMs: Long,
    ) = lock.withLock {
        val updated = PlayCounts.record(load(), song, nowMs)
        cached = updated
        withContext(Dispatchers.IO) { file.write(json.encodeToString(serializer, updated)) }
    }

    suspend fun clear() =
        lock.withLock {
            cached = emptyList()
            withContext(Dispatchers.IO) { file.delete() }
        }

    private suspend fun load(): List<PlayCount> =
        cached ?: withContext(Dispatchers.IO) { file.read()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList() }.also { cached = it }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(PlayCount.serializer())
    }
}
