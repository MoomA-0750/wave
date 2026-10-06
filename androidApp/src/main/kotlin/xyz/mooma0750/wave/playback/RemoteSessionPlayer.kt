package xyz.mooma0750.wave.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import xyz.mooma0750.wave.connect2.ConnectV2Manager
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.v2.ControlAction

/**
 * ほかの端末の再生を、通知・ロック画面・Bluetooth に映すための再生器（音は出さない）。
 * ほかの端末を映している間だけ MediaSession の再生器をこれに差し替え、再生・一時停止・次へ・前へ・シークを
 * その端末への遠隔操作にする（Spotify と同じく、どの入口の再生ボタンも鳴らしている端末を操作する）。
 */
@UnstableApi
class RemoteSessionPlayer(
    private val control: (ControlAction, Long?) -> Unit,
    /** 通知のアーティストの行（アーティスト, 端末名 → 「アーティスト • 端末名で再生中」）。 */
    private val artistLine: (String, String) -> String,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var remote: ConnectV2Manager.RemotePlayback? = null

    /** 押した直後の再生中か（相手の新しいカーソルが届くまで、最大3秒）。 */
    private var optimisticPlaying: Boolean? = null
    private var optimisticUntilMs = 0L

    fun update(next: ConnectV2Manager.RemotePlayback) {
        if (optimisticPlaying != null && (SystemClock.elapsedRealtime() > optimisticUntilMs || next.playing == optimisticPlaying)) optimisticPlaying = null
        remote = next
        invalidateState()
    }

    override fun getState(): State {
        val current = remote ?: return State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
        val playing = optimisticPlaying ?: current.playing
        val items =
            current.items.mapIndexed { index, song ->
                val metadata =
                    MediaMetadata
                        .Builder()
                        .setTitle(song.title)
                        // Spotify と同じく、どの端末で鳴っているかを出す
                        .setArtist(artistLine(song.artists.joinToString(", ") { it.name }, current.deviceLabel))
                        .setAlbumTitle(song.album?.title)
                        .setArtworkUri(song.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let(android.net.Uri::parse))
                        .build()
                MediaItemData
                    .Builder(current.itemIds[index])
                    .setMediaItem(MediaItem.Builder().setMediaId(current.itemIds[index]).setMediaMetadata(metadata).build())
                    .setMediaMetadata(metadata)
                    .setDurationUs(song.durationSeconds?.let { it * 1_000_000L } ?: C.TIME_UNSET)
                    .build()
            }
        return State
            .Builder()
            .setAvailableCommands(
                Player.Commands
                    .Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                    ).build(),
            ).setPlaylist(items)
            .setCurrentMediaItemIndex(current.currentIndex)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(
                when (current.repeat) {
                    HandoffRepeat.One -> Player.REPEAT_MODE_ONE
                    HandoffRepeat.All -> Player.REPEAT_MODE_ALL
                    HandoffRepeat.Off -> Player.REPEAT_MODE_OFF
                },
            ).setContentPositionMs { current.positionAt(SystemClock.elapsedRealtime()) }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        control(if (playWhenReady) ControlAction.Play else ControlAction.Pause, null)
        optimisticPlaying = playWhenReady
        optimisticUntilMs = SystemClock.elapsedRealtime() + 3_000
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> control(ControlAction.Next, null)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> control(ControlAction.Previous, null)
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> control(ControlAction.Seek, positionMs.coerceAtLeast(0))
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }
}
