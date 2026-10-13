package xyz.mooma0750.wave.podcast

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.core.music.FeedLibrary
import xyz.mooma0750.wave.core.music.FeedLibraryState

/** RSS で登録した番組と聴いたところを、この端末のファイルに置く（ADR-0012）。アカウントには結びつけない（ログアウトでも消さない）。 */
object FeedStore {
    private val lock = Mutex()

    fun create(context: Context): FeedLibrary {
        val file = AtomicFile(File(context.noBackupFilesDir, "podcast-feeds.json"))
        val initial = runCatching { file.readFully().decodeToString() }.getOrNull()?.let(FeedLibraryState::decode) ?: FeedLibraryState()
        return FeedLibrary(initial, System::currentTimeMillis) { state ->
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val stream = file.startWrite()
                    try {
                        stream.write(FeedLibraryState.encode(state).encodeToByteArray())
                        file.finishWrite(stream)
                    } catch (_: java.io.IOException) {
                        file.failWrite(stream)
                    }
                }
            }
        }
    }
}
