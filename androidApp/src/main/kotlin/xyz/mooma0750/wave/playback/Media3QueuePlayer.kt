package xyz.mooma0750.wave.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import xyz.mooma0750.wave.core.queue.QueueItem
import xyz.mooma0750.wave.core.queue.QueuePlayer

/**
 * core の QueuePlayer を Media3 の Player で実装する。並びの正本は Player。
 * MediaItem には曲の全情報が載らないので、itemId から QueueItem を引く表を持つ。メインスレッドだけから使う。
 */
class Media3QueuePlayer(
    private val player: Player,
) : QueuePlayer {
    private val itemsById = HashMap<String, QueueItem>()

    override val items: List<QueueItem>
        get() = (0 until player.mediaItemCount).mapNotNull { itemsById[player.getMediaItemAt(it).mediaId] }

    override val currentIndex: Int
        get() = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex

    /** 最後まで行って止まっている、またはエラーで止まっている（次が無くて飛ばせなかった）。追加が来たら再開する。 */
    override val ended: Boolean
        get() = player.playbackState == Player.STATE_ENDED || (player.playbackState == Player.STATE_IDLE && player.playerError != null)

    override val repeatAll: Boolean
        get() = player.repeatMode == Player.REPEAT_MODE_ALL

    override fun replace(
        items: List<QueueItem>,
        startIndex: Int,
    ) {
        itemsById.clear()
        items.forEach { itemsById[it.itemId] = it }
        player.setMediaItems(items.map(::mediaItemOf), startIndex, 0L)
        player.prepare()
        player.play()
    }

    override fun restore(
        items: List<QueueItem>,
        startIndex: Int,
        positionMs: Long,
    ) {
        itemsById.clear()
        items.forEach { itemsById[it.itemId] = it }
        // prepare しない。再生ボタンが押されたら、Media3 が IDLE から prepare する
        player.setMediaItems(items.map(::mediaItemOf), startIndex, positionMs)
    }

    override fun append(items: List<QueueItem>) {
        items.forEach { itemsById[it.itemId] = it }
        val wasEmpty = player.mediaItemCount == 0
        player.addMediaItems(items.map(::mediaItemOf))
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
    }

    override fun insertAfterCurrent(items: List<QueueItem>) {
        items.forEach { itemsById[it.itemId] = it }
        val index = if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
        player.addMediaItems(index, items.map(::mediaItemOf))
        if (player.mediaItemCount == items.size) {
            player.prepare()
            player.play()
        }
    }

    /** 1件ずつ動かすとタイムラインの変更がその回数だけコントローラーへ送られるので、削除と追加の2回にする。 */
    override fun replaceAfterCurrent(items: List<QueueItem>) {
        if (player.mediaItemCount == 0) return
        val start = player.currentMediaItemIndex + 1
        items.forEach { itemsById[it.itemId] = it }
        player.removeMediaItems(start, player.mediaItemCount)
        player.addMediaItems(items.map(::mediaItemOf))
        // 並べ替えで外れた項目（呼び出し側が渡さなかったもの）は表から消す
        val kept = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
        itemsById.keys.retainAll(kept)
    }

    override fun remove(itemId: String) {
        val index = indexOf(itemId) ?: return
        player.removeMediaItem(index)
        itemsById.remove(itemId)
    }

    override fun jumpTo(itemId: String) {
        val index = indexOf(itemId) ?: return
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    override fun resumeAt(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    override fun clear() {
        player.stop()
        player.clearMediaItems()
        itemsById.clear()
    }

    private fun indexOf(itemId: String): Int? = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == itemId }

    companion object {
        private val GOOGLE_IMAGE_SIZE = Regex("=w\\d+-h\\d+")
        private const val SONG_EXTRA = "xyz.mooma0750.wave.SONG"
        private val songJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** mediaItemOf で載せた曲。読めなければ null。 */
        fun songOf(item: MediaItem?): xyz.mooma0750.wave.core.music.Song? =
            item?.mediaMetadata?.extras?.getString(SONG_EXTRA)?.let { runCatching { songJson.decodeFromString(xyz.mooma0750.wave.core.music.Song.serializer(), it) }.getOrNull() }

        /** 検索結果のサムネイル（120px）は再生画面・通知には小さいので、googleusercontent のサイズ指定を大きくする。 */
        fun largeArtwork(url: String): String = if ("googleusercontent.com" in url) url.replace(GOOGLE_IMAGE_SIZE, "=w544-h544") else url

        fun mediaItemOf(item: QueueItem): MediaItem {
            val song = item.song
            return MediaItem
                .Builder()
                .setMediaId(item.itemId)
                .setUri(TrackUri.of(song.videoId))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(song.title)
                        .setArtist(song.artists.joinToString(", ") { it.name })
                        .setAlbumTitle(song.album?.title)
                        .setArtworkUri(song.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(::largeArtwork)?.toUri())
                        .setDurationMs(song.durationSeconds?.let { it * 1000L })
                        // 自アプリの画面の曲のメニュー（アーティスト・アルバムを開く）で使う。公開のメタデータだけ
                        .setExtras(android.os.Bundle().apply { putString(SONG_EXTRA, songJson.encodeToString(xyz.mooma0750.wave.core.music.Song.serializer(), song)) })
                        .build(),
                ).build()
        }
    }
}
