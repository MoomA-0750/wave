package xyz.mooma0750.wave.debug

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import xyz.mooma0750.wave.playback.PlaybackService
import xyz.mooma0750.wave.playback.TrackUri
import java.util.UUID

/** debug ビルドだけの再生確認（S2）。S4 でキューの入口に置き換える。 */
object DebugPlayback {
    const val VIDEO_ID = "dQw4w9WgXcQ"

    suspend fun controller(context: Context): MediaController =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().await()

    fun fixedItem(): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(UUID.randomUUID().toString())
            .setUri(TrackUri.of(VIDEO_ID))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("開発用の確認曲").setArtist("wave").build())
            .build()
}
