package xyz.mooma0750.wave.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import xyz.mooma0750.wave.core.music.Song

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

    suspend fun playSong(song: Song) = send(QueueCommands.PLAY_SONG, QueueCommands.songArgs(song))

    suspend fun playFrom(
        songs: List<Song>,
        startIndex: Int,
        playlistId: String?,
        continuation: String?,
    ) = send(QueueCommands.PLAY_FROM, QueueCommands.playFromArgs(QueueCommands.PlayFrom(songs, startIndex, playlistId, continuation)))

    suspend fun addNext(song: Song) = send(QueueCommands.ADD_NEXT, QueueCommands.songArgs(song))

    suspend fun addToEnd(song: Song) = send(QueueCommands.ADD_TO_END, QueueCommands.songArgs(song))

    suspend fun remove(itemId: String) = send(QueueCommands.REMOVE, QueueCommands.itemArgs(itemId))

    suspend fun jumpTo(itemId: String) = send(QueueCommands.JUMP, QueueCommands.itemArgs(itemId))

    private suspend fun send(
        action: String,
        args: android.os.Bundle,
    ) {
        controller().sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args).await()
    }
}
