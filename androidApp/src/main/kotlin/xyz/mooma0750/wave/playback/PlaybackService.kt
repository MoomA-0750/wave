package xyz.mooma0750.wave.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import xyz.mooma0750.wave.WaveApplication

/** 再生の本体。画面が無くても動く。 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        val container = (application as WaveApplication).container
        val dataSourceFactory = WaveStreamDataSource.Factory(container.stream.resolver, container.streamCache, container.mediaHttpClient)
        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(WaveLoadErrorHandlingPolicy()),
                ).setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 例外文や原因には URL が入りうるので、コードと型名だけを記録する
                    Log.w(WaveApplication.LOG_TAG, "playback error code=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName} reason=${(error.cause as? StreamResolveException)?.reason}")
                    consecutiveFailures++
                    if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES && player.hasNextMediaItem()) {
                        // 再生できない曲は飛ばして続ける
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) consecutiveFailures = 0
                }
            },
        )
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

/** 解決できない種類の失敗（再生不可・解読失敗など）は、同じ曲を再試行しない。 */
@OptIn(UnstableApi::class)
class WaveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val cause = loadErrorInfo.exception
        if (cause is StreamResolveException && !cause.transient) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
