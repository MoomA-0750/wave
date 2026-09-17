package xyz.mooma0750.wave.playback

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.SongPage
import xyz.mooma0750.wave.core.music.UpNextPage
import xyz.mooma0750.wave.core.queue.QueueContentSource
import xyz.mooma0750.wave.core.queue.QueueSession
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.stream.StreamFailure
import java.util.UUID

/** 再生の本体。画面が無くても動き、キューの続き（サジェスト）もここで取る。 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private var queue: QueueSession? = null
    private val skipGuard = SkipGuard(MAX_CONSECUTIVE_SKIPS)

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
        val queueSession = QueueSession(Media3QueuePlayer(player), RepositoryContentSource(container.music), scope, newItemId = { UUID.randomUUID().toString() })
        queue = queueSession
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 例外文や原因には URL が入りうるので、コードと型名だけを記録する
                    val cause = error.cause
                    Log.w(WaveApplication.LOG_TAG, "playback error code=${error.errorCodeName} cause=${cause?.javaClass?.simpleName} reason=${(cause as? StreamResolveException)?.reason}")
                    if (skipGuard.onError(failureOf(error), player.hasNextMediaItem())) {
                        // この曲だけの失敗は飛ばして続ける。一時的な失敗・どの曲でも起きる失敗では止める
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) skipGuard.onPlaying()
                    if (playbackState == Player.STATE_ENDED) queueSession.onPlaybackProgressed()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    queueSession.onPlaybackProgressed()
                }
            },
        )
        session =
            MediaSession
                .Builder(this, player)
                .setCallback(Callback(queueSession))
                .build()

        // ログアウトしたら再生を止めてキューと解決済みの URL を消す（決定 E5）
        scope.launch {
            container.authManager.state.collect { state ->
                if (state == AuthState.LoggedOut || state == AuthState.LoggingOut) {
                    queueSession.reset()
                    container.streamCache.clear()
                }
            }
        }
    }

    private fun failureOf(error: PlaybackException): PlaybackFailure {
        val cause = error.cause
        return when {
            cause is StreamResolveException ->
                when (cause.reason) {
                    StreamFailure.Unplayable, StreamFailure.NoAudioFormat, StreamFailure.ItagUnavailable, StreamFailure.StreamChanged -> PlaybackFailure.ThisSong
                    StreamFailure.Network, StreamFailure.Http, StreamFailure.PlayerConfigUnavailable -> PlaybackFailure.Transient
                    else -> PlaybackFailure.Everywhere
                }
            cause is StreamRejectedException -> PlaybackFailure.ThisSong
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure.ThisSong
            error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> PlaybackFailure.ThisSong
            else -> PlaybackFailure.Transient
        }
    }

    /** このアプリ自身のコントローラーか。パッケージ名は名乗れるので、Binder から取る uid で判定する。 */
    private fun isOwnController(controller: MediaSession.ControllerInfo): Boolean = controller.uid == Process.myUid()

    private inner class Callback(
        private val queue: QueueSession,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // 外部のコントローラー（通知・Bluetooth など）は既定の再生操作だけ。キューの操作は渡さない
            if (!isOwnController(controller)) return super.onConnect(session, controller)
            val commands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .apply { QueueCommands.all.forEach(::add) }
                    .build()
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!isOwnController(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (customCommand.customAction in USER_SELECTIONS) skipGuard.onUserSelection()
            val handled =
                when (customCommand.customAction) {
                    QueueCommands.PLAY_SONG -> QueueCommands.songOf(args)?.let { queue.playSong(it) }
                    QueueCommands.PLAY_FROM ->
                        QueueCommands.playFromOf(args)?.let { queue.playFrom(it.songs, it.startIndex, it.playlistId, it.continuation) }
                    QueueCommands.ADD_NEXT -> QueueCommands.songOf(args)?.let { queue.addNext(it) }
                    QueueCommands.ADD_TO_END -> QueueCommands.songOf(args)?.let { queue.addToEnd(it) }
                    QueueCommands.REMOVE -> QueueCommands.itemIdOf(args)?.let { queue.remove(it) }
                    QueueCommands.JUMP -> QueueCommands.itemIdOf(args)?.let { queue.jumpTo(it) }
                    else -> null
                }
            return Futures.immediateFuture(SessionResult(if (handled != null) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
        }

        /** MediaItem の直接の追加は受け付けない。キューの操作はカスタムコマンドだけ（任意の videoId を解決させない）。 */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFailedFuture(UnsupportedOperationException("use queue commands"))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val playing = player != null && player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        queue = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONSECUTIVE_SKIPS = 3
        val USER_SELECTIONS = setOf(QueueCommands.PLAY_SONG, QueueCommands.PLAY_FROM, QueueCommands.JUMP)
    }
}

/** QueueSession の続きの取得元を MusicRepository で実装する。 */
private class RepositoryContentSource(
    private val music: MusicRepository,
) : QueueContentSource {
    override suspend fun upNext(
        videoId: String?,
        playlistId: String?,
        continuation: String?,
    ): CallResult<UpNextPage> = music.upNext(videoId, playlistId, continuation)

    override suspend fun playlistPage(
        playlistId: String,
        continuation: String,
    ): CallResult<SongPage> =
        when (val result = music.playlist(playlistId, continuation)) {
            is CallResult.Ok -> CallResult.Ok(SongPage(result.value.songs, result.value.continuation))
            is CallResult.Failed -> result
        }
}

/** 再試行しない失敗と、時間をおく失敗を分ける。 */
@OptIn(UnstableApi::class)
class WaveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val cause = loadErrorInfo.exception
        return when {
            cause is StreamRejectedException -> C.TIME_UNSET
            cause is StreamResolveException && !cause.transient -> C.TIME_UNSET
            // /player の失敗（429 など）は、すぐに叩き直さない
            cause is StreamResolveException -> 5_000L * loadErrorInfo.errorCount
            else -> super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
