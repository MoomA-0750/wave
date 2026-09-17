package xyz.mooma0750.wave.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.queue.ItemOrigin
import xyz.mooma0750.wave.core.queue.QueueItem
import xyz.mooma0750.wave.core.queue.QueueSource
import xyz.mooma0750.wave.core.queue.SavedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueStoreTest {
    private class MemoryFile : QueueFile {
        var content: String? = null
        var writes = 0

        override fun read(): String? = content

        override fun write(text: String) {
            writes++
            content = text
        }

        override fun delete() {
            content = null
        }
    }

    private val saved =
        SavedQueue(
            SavedQueue.VERSION,
            obtainedAtEpochMs = 1,
            items = listOf(QueueItem("i", Song("v", "t", emptyList(), null, null, null), ItemOrigin.Source)),
            currentIndex = 0,
            positionMs = 10,
            repeatMode = 0,
            source = QueueSource.Radio("v"),
            continuation = null,
            radioPlaylistId = null,
            exhausted = false,
        )

    @Test
    fun savesAndLoads(): Unit =
        runBlocking {
            val file = MemoryFile()
            val store = QueueStore(file, Dispatchers.Unconfined)
            store.save(saved, store.currentEpoch())
            assertEquals(saved, store.load())
        }

    @Test
    fun aSaveCapturedBeforeADeleteIsNotWritten(): Unit =
        runBlocking {
            val file = MemoryFile()
            val store = QueueStore(file, Dispatchers.Unconfined)
            val epoch = store.currentEpoch() // ログアウトの前に保存内容を作った
            store.delete()
            store.save(saved, epoch)
            store.saveBlocking(saved, epoch)
            assertNull(file.content, "ログアウトの後にファイルが復活した")
            assertEquals(0, file.writes)

            store.save(saved, store.currentEpoch())
            assertEquals(saved, store.load(), "削除の後に作った保存内容は書く")
        }

    @Test
    fun brokenFilesAreDeleted(): Unit =
        runBlocking {
            val file = MemoryFile().apply { content = "{broken" }
            val store = QueueStore(file, Dispatchers.Unconfined)
            assertNull(store.load())
            assertNull(file.content)
        }
}
