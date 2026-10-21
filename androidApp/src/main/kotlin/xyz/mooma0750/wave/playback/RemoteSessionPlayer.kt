package xyz.mooma0750.wave.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
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
    /** その端末の音量（PC のアプリの中の音量。スマホ・時計は音量を合わせないので載せない・受けない）を変えてもらう（0〜100）。 */
    private val setVolume: (Int) -> Unit,
    /** 今の曲の画像を取る（手元の再生器と同じく、URL ではなく画像そのものを入れる）。 */
    private val artwork: ArtworkLoader,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** 今の曲の画像の URL と、取れた画像（取れるまで null）。 */
    private var artworkUrl: String? = null
    private var artworkBytes: ByteArray? = null

    /** 本人が音量ボタンで変えた直後の音量（相手の新しいカーソルが届くまで、最大2秒）。 */
    private var optimisticVolume: Int? = null
    private var optimisticVolumeUntilMs = 0L
    private var remote: ConnectV2Manager.RemotePlayback? = null

    /** 押した直後の再生中か（相手の新しいカーソルが届くまで、最大3秒）。 */
    private var optimisticPlaying: Boolean? = null
    private var optimisticUntilMs = 0L

    fun update(next: ConnectV2Manager.RemotePlayback) {
        if (optimisticPlaying != null && (SystemClock.elapsedRealtime() > optimisticUntilMs || next.playing == optimisticPlaying)) optimisticPlaying = null
        if (optimisticVolume != null && (SystemClock.elapsedRealtime() > optimisticVolumeUntilMs || next.volumePercent == optimisticVolume)) optimisticVolume = null
        remote = next
        val url = next.current.thumbnailUrl?.let(ArtworkLoader::urlOf)
        if (url != artworkUrl) {
            artworkUrl = url
            artworkBytes = null
            url?.let {
                artwork.load(it) { bytes ->
                    if (artworkUrl != it) return@load
                    artworkBytes = bytes
                    invalidateState()
                }
            }
        }
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
                        .setArtist(artistLine(song.artistsText, current.deviceLabel))
                        .setAlbumTitle(song.album?.title)
                        // 画像の URL は入れない（プラットフォームのセッションに https の URL があると、時計が画像を出さない。ArtworkEmbedder と同じ）。
                        // 画像そのものを今の曲にだけ入れ、アプリの画面・ウィジェットは曲の情報（extras）の URL から出す
                        .apply { if (index == current.currentIndex) artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                        .setExtras(Media3QueuePlayer.extrasOf(song, null))
                        .build()
                MediaItemData
                    .Builder(current.itemIds[index])
                    .setMediaItem(MediaItem.Builder().setMediaId(current.itemIds[index]).setMediaMetadata(metadata).build())
                    .setMediaMetadata(metadata)
                    .setDurationUs(song.durationSeconds?.takeUnless { song.isLive }?.let { it * 1_000_000L } ?: C.TIME_UNSET)
                    .apply {
                        // ライブはロック画面・通知でもシークバーを出さず、ライブとして出す
                        if (song.isLive) setIsDynamic(true).setIsSeekable(false).setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
                    }.build()
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
                    ).apply {
                        // 音量を知らせてくる端末なら、音量ボタンでその端末の音量を変える（Spotify と同じ。この端末の音量は変えない）
                        if (current.volumePercent != null) {
                            addAll(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                        }
                    }.build(),
            ).setPlaylist(items)
            .apply {
                val volume = optimisticVolume ?: current.volumePercent
                if (volume != null) {
                    setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(MAX_VOLUME).build())
                    setDeviceVolume(volume.coerceIn(0, MAX_VOLUME))
                }
            }
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

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ): ListenableFuture<*> {
        sendVolume(deviceVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        (optimisticVolume ?: remote?.volumePercent)?.let { sendVolume(it + VOLUME_STEP) }
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        (optimisticVolume ?: remote?.volumePercent)?.let { sendVolume(it - VOLUME_STEP) }
        return Futures.immediateVoidFuture()
    }

    private fun sendVolume(percent: Int) {
        val clamped = percent.coerceIn(0, MAX_VOLUME)
        optimisticVolume = clamped
        optimisticVolumeUntilMs = SystemClock.elapsedRealtime() + 2_000
        setVolume(clamped)
        invalidateState()
    }

    private companion object {
        const val MAX_VOLUME = 100

        /** 音量ボタン1回で変える幅。 */
        const val VOLUME_STEP = 5
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
