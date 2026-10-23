package xyz.mooma0750.wave.download

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.download.DownloadManifest
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.playback.AtomicQueueFile

/** 曲ごとの `<名前>.json`（AudioCache の形式に、取り終えた印と曲の情報を足したもの）。署名付きの URL は書かない。 */
@Serializable
data class DownloadedTrack(
    val videoId: String,
    val mimeType: String? = null,
    val itag: Int? = null,
    val contentLength: Long,
    val complete: Boolean,
    val song: Song? = null,
    val downloadedAtMs: Long = 0,
)

/**
 * 消えない置き場（`filesDir/downloads`、ADR-0016 の 4）。曲は `<videoId>.bin` と `<videoId>.json`、
 * RSS のエピソードは音声の URL の SHA-256 を名前にする。目録は `downloads.json`。
 * 速く鳴らすための置き場（AudioCache）とは別で、上限で消さない。
 */
class DownloadStore(
    filesDir: File,
) {
    val dir = File(filesDir, "downloads")
    private val manifestFile = AtomicQueueFile(File(dir, "downloads.json"))

    /** 取り終えた曲（videoId → 音のファイル）。再生のたびにディスクを見ないよう、最初に使うときに1回だけ読んで手元に持つ。 */
    private val complete: ConcurrentHashMap<String, File> by lazy { scan() }

    private fun scan(): ConcurrentHashMap<String, File> {
        val found = ConcurrentHashMap<String, File>()
        dir.mkdirs()
        // 取りかけのファイルは残さない（続きから取ると別のファイルを継ぎ足しうるので、取り直す）
        dir.listFiles()?.filter { it.name.endsWith(PART) }?.forEach { it.delete() }
        dir.listFiles()?.filter { it.name.endsWith(".json") && it.name != "downloads.json" }?.forEach { meta ->
            val track = runCatching { json.decodeFromString(DownloadedTrack.serializer(), meta.readText()) }.getOrNull()
            val audio = File(dir, meta.name.removeSuffix(".json") + ".bin")
            if (track != null && track.complete && audio.isFile && audio.length() == track.contentLength) {
                found[track.videoId] = audio
            } else {
                meta.delete()
                audio.delete()
            }
        }
        return found
    }

    fun readManifest(): DownloadManifest = manifestFile.read()?.let { runCatching { json.decodeFromString(DownloadManifest.serializer(), it) }.getOrNull() } ?: DownloadManifest()

    fun writeManifest(manifest: DownloadManifest) {
        dir.mkdirs()
        manifestFile.write(json.encodeToString(DownloadManifest.serializer(), manifest))
    }

    fun completeIds(): Set<String> = complete.keys.toSet()

    fun isComplete(videoId: String): Boolean = complete.containsKey(videoId)

    /** 取り終えた曲の音のファイル。無ければ null。 */
    fun audioFile(videoId: String): File? = complete[videoId]?.takeIf { it.isFile }

    fun partFile(videoId: String): File = File(dir, keyOf(videoId) + PART)

    /** 取り終えた。取りかけのファイルを音のファイルにして、形式を書く。 */
    fun finish(
        videoId: String,
        part: File,
        track: DownloadedTrack,
    ): Boolean {
        val key = keyOf(videoId)
        val audio = File(dir, "$key.bin")
        audio.delete()
        if (!part.renameTo(audio)) return false
        File(dir, "$key.json").writeText(json.encodeToString(DownloadedTrack.serializer(), track.copy(contentLength = audio.length())))
        complete[videoId] = audio
        return true
    }

    fun delete(videoId: String) {
        val key = keyOf(videoId)
        complete.remove(videoId)
        File(dir, "$key.bin").delete()
        File(dir, "$key.json").delete()
        File(dir, key + PART).delete()
    }

    /** 全部消す（ログアウト・「ダウンロードをすべて削除」）。目録も消す。 */
    fun clear() {
        complete.clear()
        dir.listFiles()?.forEach { it.delete() }
        manifestFile.delete()
        dir.mkdirs()
    }

    /** 使っている容量（バイト）。 */
    fun bytesUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    companion object {
        private const val PART = ".part"
        private val json = Json { ignoreUnknownKeys = true }

        /** ファイルの名前。曲は videoId、RSS のエピソードは音声の URL の SHA-256。 */
        fun keyOf(videoId: String): String {
            val url = Feed.audioUrlOf(videoId) ?: return videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return MessageDigest.getInstance("SHA-256").digest(url.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
