package xyz.mooma0750.wave.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import xyz.mooma0750.wave.playback.PlayerConnection

/** 画面に出す再生の状態。MediaController から読み取る。 */
data class PlayerUiState(
    val connected: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<QueueRow> = emptyList(),
    val currentIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: String? = null,
)

data class QueueRow(
    val itemId: String,
    val title: String,
    val artist: String,
)

@Composable
fun rememberPlayerUiState(connection: PlayerConnection): Pair<PlayerUiState, MediaController?> {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var state by remember { mutableStateOf(PlayerUiState()) }

    LaunchedEffect(connection) { controller = connection.controller() }

    DisposableEffect(controller) {
        val current = controller
        if (current == null) return@DisposableEffect onDispose {}
        fun read() {
            val item = current.currentMediaItem
            state =
                PlayerUiState(
                    connected = true,
                    title = item?.mediaMetadata?.title?.toString(),
                    artist = item?.mediaMetadata?.artist?.toString(),
                    isPlaying = current.isPlaying,
                    buffering = current.playbackState == Player.STATE_BUFFERING,
                    positionMs = current.currentPosition,
                    durationMs = current.duration.coerceAtLeast(0),
                    queue =
                        (0 until current.mediaItemCount).map {
                            val mediaItem = current.getMediaItemAt(it)
                            QueueRow(mediaItem.mediaId, mediaItem.mediaMetadata.title?.toString().orEmpty(), mediaItem.mediaMetadata.artist?.toString().orEmpty())
                        },
                    currentIndex = current.currentMediaItemIndex,
                    hasNext = current.hasNextMediaItem(),
                    hasPrevious = current.hasPreviousMediaItem(),
                    error = current.playerError?.errorCodeName,
                )
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) = read()
            }
        current.addListener(listener)
        read()
        onDispose { current.removeListener(listener) }
    }

    // 再生位置は通知が来ないので、再生中だけ1秒ごとに読む
    LaunchedEffect(controller, state.isPlaying) {
        val current = controller ?: return@LaunchedEffect
        while (state.isPlaying) {
            state = state.copy(positionMs = current.currentPosition, durationMs = current.duration.coerceAtLeast(0))
            kotlinx.coroutines.delay(1_000)
        }
    }
    return state to controller
}
