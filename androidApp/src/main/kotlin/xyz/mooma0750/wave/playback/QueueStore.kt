package xyz.mooma0750.wave.playback

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.core.queue.SavedQueue
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 保存先のファイル。書き込みの途中で落ちても前の内容か新しい内容のどちらかが残ること。 */
interface QueueFile {
    fun read(): String?

    fun write(text: String)

    fun delete()
}

/** AtomicFile（退避ファイルと fsync）で書く。 */
class AtomicQueueFile(
    file: File,
) : QueueFile {
    private val atomic = AtomicFile(file)

    override fun read(): String? = runCatching { atomic.readFully().decodeToString() }.getOrNull()

    override fun write(text: String) {
        val stream =
            try {
                atomic.startWrite()
            } catch (_: java.io.IOException) {
                return
            }
        try {
            stream.write(text.encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (_: java.io.IOException) {
            atomic.failWrite(stream)
        }
    }

    override fun delete() = atomic.delete()
}

/**
 * キューの保存ファイル（F1b）。バックアップに含めない `noBackupFilesDir` に置き、暗号化はしない（決定 F-2）。
 *
 * 書き込みと削除は1つの Mutex で直列にする。さらに削除のたびに世代を進め、削除より前に作った保存内容は書かない
 * （ログアウトの削除の後に、待っていた保存が走ってファイルが復活しないように）。
 */
class QueueStore(
    private val file: QueueFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val epoch = AtomicLong()

    /** 保存内容を作る直前に読み、save に渡す。 */
    fun currentEpoch(): Long = epoch.get()

    suspend fun save(
        saved: SavedQueue,
        epochAtCapture: Long,
    ) {
        // JSON にするのも IO のスレッドで（長いキューでメインスレッドを止めない）
        withContext(io) { writeIfCurrent(SavedQueue.encode(saved), epochAtCapture) }
    }

    /** onDestroy から呼ぶ。scope を止める前に、呼び出したスレッドで書き終える。 */
    fun saveBlocking(
        saved: SavedQueue,
        epochAtCapture: Long,
    ) {
        val text = SavedQueue.encode(saved)
        runBlocking { writeIfCurrent(text, epochAtCapture) }
    }

    /** 読めない・形式が違うファイルは消して null。 */
    suspend fun load(): SavedQueue? =
        withContext(io) {
            lock.withLock {
                val text = file.read() ?: return@withLock null
                SavedQueue.decode(text) ?: run {
                    file.delete()
                    null
                }
            }
        }

    /** ログアウト・アカウントの切り替え・キューが空になったとき。 */
    suspend fun delete() {
        epoch.incrementAndGet()
        withContext(io) { lock.withLock { file.delete() } }
    }

    private suspend fun writeIfCurrent(
        text: String,
        epochAtCapture: Long,
    ) {
        lock.withLock {
            if (epoch.get() == epochAtCapture) file.write(text)
        }
    }

    companion object {
        fun create(context: Context): QueueStore = QueueStore(AtomicQueueFile(File(context.noBackupFilesDir, "queue.json")))
    }
}
