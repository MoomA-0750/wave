package xyz.mooma0750.wave.core.queue

import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffOrigin
import xyz.mooma0750.wave.handoff.HandoffRef
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.HandoffSource

/** 端末の中のキューの表現と、受け渡しの形式（ワイヤ用に凍結した形）の変換。 */
internal object HandoffMapping {
    fun itemOf(item: QueueItem): HandoffItem =
        HandoffItem(
            itemId = item.itemId,
            videoId = item.song.videoId,
            title = item.song.title,
            artists = item.song.artists.map { HandoffRef(it.name, it.browseId) },
            album = item.song.album?.let { HandoffRef(it.title, it.browseId) },
            durationSeconds = item.song.durationSeconds,
            thumbnailUrl = item.song.thumbnailUrl,
            isLive = item.song.isLive,
            origin =
                when (item.origin) {
                    ItemOrigin.Source -> HandoffOrigin.Source
                    ItemOrigin.Suggestion -> HandoffOrigin.Suggestion
                    ItemOrigin.UserAdded -> HandoffOrigin.UserAdded
                },
        )

    fun queueItemOf(item: HandoffItem): QueueItem =
        QueueItem(
            itemId = item.itemId,
            song =
                Song(
                    videoId = item.videoId,
                    title = item.title,
                    artists = item.artists.map { ArtistRef(it.name, it.browseId) },
                    album = item.album?.let { AlbumRef(it.name, it.browseId) },
                    durationSeconds = item.durationSeconds,
                    thumbnailUrl = item.thumbnailUrl,
                    isLive = item.isLive,
                ),
            origin =
                when (item.origin) {
                    HandoffOrigin.Source -> ItemOrigin.Source
                    HandoffOrigin.Suggestion -> ItemOrigin.Suggestion
                    HandoffOrigin.UserAdded -> ItemOrigin.UserAdded
                },
        )

    fun sourceOf(source: QueueSource?): HandoffSource? =
        when (source) {
            is QueueSource.Radio -> HandoffSource.Radio(source.seedVideoId)
            is QueueSource.Playlist -> HandoffSource.Playlist(source.playlistId)
            null -> null
        }

    fun queueSourceOf(source: HandoffSource?): QueueSource? =
        when (source) {
            is HandoffSource.Radio -> QueueSource.Radio(source.seedVideoId)
            is HandoffSource.Playlist -> QueueSource.Playlist(source.playlistId)
            null -> null
        }

    /** Media3 の repeatMode の値（0 オフ・1 1曲・2 全体）との変換。 */
    fun repeatOf(mode: Int): HandoffRepeat =
        when (mode) {
            1 -> HandoffRepeat.One
            2 -> HandoffRepeat.All
            else -> HandoffRepeat.Off
        }

    const val SCHEMA_VERSION = HandoffCodec.SCHEMA_VERSION
}
