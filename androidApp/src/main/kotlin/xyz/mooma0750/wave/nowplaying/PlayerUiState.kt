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
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    /** ライブ配信を流している（長さもシークも無い）。 */
    val isLive: Boolean = false,
)

data class QueueRow(
    val itemId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    /** 自分で足した曲（キューの「次に再生」「キューに追加」）。 */
    val userAdded: Boolean = false,
)

/**
 * withQueue が false なら、キューの行は読まない（曲名と再生中かだけ要る画面。時計ではキューの曲の情報を読み直すのが重い）。
 * キューの行は同じ項目なら前に読んだものを使い回し、キューが変わったときだけ並べ直す。
 */
@Composable
fun rememberPlayerUiState(
    connection: PlayerConnection,
    withQueue: Boolean = true,
): Pair<PlayerUiState, MediaController?> {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var state by remember { mutableStateOf(PlayerUiState()) }
    val rows = remember { HashMap<String, QueueRow>() }

    LaunchedEffect(connection) { controller = connection.controller() }

    DisposableEffect(controller) {
        val current = controller
        if (current == null) return@DisposableEffect onDispose {}
        fun queue(): List<QueueRow> {
            if (!withQueue) return emptyList()
            val list =
                (0 until current.mediaItemCount).map {
                    val mediaItem = current.getMediaItemAt(it)
                    // 曲の情報が差し替わった項目（ライブの印など）は読み直す
                    rows.getOrPut("${mediaItem.mediaId}|${mediaItem.mediaMetadata.title}") { row(mediaItem) }
                }
            // 外れた項目の行は捨てる
            if (rows.size > list.size * 2 + 16) rows.clear()
            return list
        }

        fun read(queueChanged: Boolean = true) {
            val item = current.currentMediaItem
            state =
                PlayerUiState(
                    title = item?.mediaMetadata?.title?.toString(),
                    artist = item?.mediaMetadata?.artist?.toString(),
                    isPlaying = current.isPlaying,
                    buffering = current.playbackState == Player.STATE_BUFFERING,
                    positionMs = current.currentPosition,
                    durationMs = current.duration.coerceAtLeast(0),
                    queue = if (queueChanged) queue() else state.queue,
                    currentIndex = current.currentMediaItemIndex,
                    hasNext = current.hasNextMediaItem(),
                    hasPrevious = current.hasPreviousMediaItem(),
                    repeatMode = current.repeatMode,
                    error = current.playerError?.errorCodeName,
                    isLive = xyz.mooma0750.wave.playback.Media3QueuePlayer.songOf(item)?.isLive == true || current.isCurrentMediaItemLive,
                )
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) = read(queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
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

private fun row(mediaItem: androidx.media3.common.MediaItem): QueueRow =
    QueueRow(
        mediaItem.mediaId,
        mediaItem.mediaMetadata.title
            ?.toString()
            .orEmpty(),
        mediaItem.mediaMetadata.artist
            ?.toString()
            .orEmpty(),
        // 曲の情報の画像の URL は入れていない（時計のため）ので、曲の情報（extras）から引く
        xyz.mooma0750.wave.playback.Media3QueuePlayer
            .songOf(mediaItem)
            ?.thumbnailUrl,
        xyz.mooma0750.wave.playback.Media3QueuePlayer.isUserAdded(mediaItem),
    )

/**
 * この端末で鳴っているか・キューがあるかだけを見る（位置は読まない）。画面全体の並びを決める所で使い、
 * 再生の位置が進むたびに画面全体を組み直さないようにする。
 */
@Composable
fun rememberPlaybackPresence(connection: PlayerConnection): Pair<Boolean, Boolean> {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var presence by remember { mutableStateOf(false to false) }
    LaunchedEffect(connection) { controller = connection.controller() }
    DisposableEffect(controller) {
        val current = controller ?: return@DisposableEffect onDispose {}
        fun read() {
            presence = current.isPlaying to (current.mediaItemCount > 0)
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
    return presence
}
