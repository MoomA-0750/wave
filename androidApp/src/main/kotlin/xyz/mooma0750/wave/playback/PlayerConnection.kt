package xyz.mooma0750.wave.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import xyz.mooma0750.wave.core.music.Song
import java.util.UUID

/** UI から PlaybackService へつなぐ。MediaController はアプリで1つを使い回す。 */
class PlayerConnection(
    private val context: Context,
) {
    private var controller: MediaController? = null

    suspend fun controller(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        return MediaController
            .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .buildAsync()
            .await()
            .also { controller = it }
    }

    companion object {
        /** キューの項目。mediaId は項目ごとの ID、videoId は URI とメタデータに持つ。 */
        fun mediaItemOf(song: Song): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(UUID.randomUUID().toString())
                .setUri(TrackUri.of(song.videoId))
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(song.title)
                        .setArtist(song.artists.joinToString(", ") { it.name })
                        .setAlbumTitle(song.album?.title)
                        .setArtworkUri(song.thumbnailUrl?.toUri())
                        .build(),
                ).build()
    }
}
