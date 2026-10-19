package xyz.mooma0750.wave.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.mooma0750.wave.WaveApplication

/**
 * 今の曲の画像を、URL ではなく画像そのもの（artworkData）としてメタデータに入れる。
 *
 * 時計（Galaxy Watch など）は、プラットフォームのセッションに画像の URL があるとそちらを読みに行き、https だと読めずに画像を出さない
 * （YouTube のセッションは画像そのものだけを持ち、URL を持たない。2026-09-25 に Galaxy で項目名を比べて確かめた）。
 * そのため曲の MediaItem には URL を入れず、今の曲になったら画像を取ってきて、同じ URI のまま中身の情報だけ差し替える
 * （URI が同じなら ExoPlayer は準備し直さず、再生は途切れない）。Main から使う。
 * ほかの端末を映す [RemoteSessionPlayer] も、同じ理由で URL を入れず画像そのものを入れる。
 */
class ArtworkEmbedder(
    private val player: Player,
    private val loader: ArtworkLoader,
) {
    /** 今の曲が変わった・キューが置き換わったときに呼ぶ。 */
    fun onCurrentItemChanged() {
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artworkData != null) return
        val url = Media3QueuePlayer.songOf(item)?.thumbnailUrl?.let(ArtworkLoader::urlOf) ?: return
        loader.load(url) { embed(item.mediaId, it) }
    }

    /** 今の曲がまだ同じなら、画像を入れた MediaItem に差し替える。 */
    private fun embed(
        mediaId: String,
        bytes: ByteArray,
    ) {
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        if (current.mediaId != mediaId || current.mediaMetadata.artworkData != null) return
        val updated: MediaItem =
            current
                .buildUpon()
                .setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build())
                .build()
        player.replaceMediaItem(index, updated)
    }
}

/**
 * 通知・ロック画面・時計に渡す画像を取ってくる（手元の再生器の [ArtworkEmbedder] と、ほかの端末を映す [RemoteSessionPlayer] が使う）。
 * 取りかけは1つだけ（新しく頼むと前のは捨てる）。Main から使い、取れたら Main で知らせる。
 */
class ArtworkLoader(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** 取った画像（URL ごと、少しだけ持つ。前の曲に戻ったときに取り直さない）。 */
    private val cache =
        object : LinkedHashMap<String, ByteArray>(CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > CACHE_ENTRIES
        }

    fun load(
        url: String,
        onLoaded: (ByteArray) -> Unit,
    ) {
        job?.cancel()
        cache[url]?.let { return onLoaded(it) }
        job =
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { fetch(url) } ?: return@launch
                cache[url] = bytes
                onLoaded(bytes)
            }
    }

    private fun fetch(url: String): ByteArray? =
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                // 大きすぎる応答は読まない（画像は数十KB）
                if (body.contentLength() > MAX_BYTES) return@use null
                body.bytes().takeIf { it.size <= MAX_BYTES }
            }
        }.onFailure { Log.i(WaveApplication.LOG_TAG, "artwork fetch failed type=${it::class.simpleName}") }.getOrNull()

    fun release() {
        job?.cancel()
        cache.clear()
    }

    companion object {
        private const val CACHE_ENTRIES = 8
        private const val MAX_BYTES = 1024 * 1024L

        /** 曲の画像（一覧の小さいもの）から、取りに行く大きめの画像の URL。https でなければ取らない（null）。 */
        fun urlOf(thumbnailUrl: String): String? = thumbnailUrl.takeIf { it.startsWith("https://") }?.let(Media3QueuePlayer::largeArtwork)
    }
}
