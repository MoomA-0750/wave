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

    override val ended: Boolean
        get() = player.playbackState == Player.STATE_ENDED

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

    override fun append(items: List<QueueItem>) {
        items.forEach { itemsById[it.itemId] = it }
        player.addMediaItems(items.map(::mediaItemOf))
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
                        .setArtworkUri(song.thumbnailUrl?.toUri())
                        .setDurationMs(song.durationSeconds?.let { it * 1000L })
                        .build(),
                ).build()
        }
    }
}
