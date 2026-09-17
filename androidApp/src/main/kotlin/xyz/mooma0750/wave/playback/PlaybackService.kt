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

    private lateinit var container: xyz.mooma0750.wave.AppContainer

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory = WaveStreamDataSource.Factory(container.stream.resolver, container.streamCache, container.mediaHttpClient)
        container = (application as WaveApplication).container
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

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    // 件数と位置だけ（曲名などは出さない）
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                        Log.i(WaveApplication.LOG_TAG, "queue size=${player.mediaItemCount} index=${player.currentMediaItemIndex} source=${queueSession.snapshot().source?.javaClass?.simpleName} exhausted=${queueSession.snapshot().exhausted}")
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    Log.i(WaveApplication.LOG_TAG, "transition index=${player.currentMediaItemIndex} reason=$reason")
                    queueSession.onPlaybackProgressed()
                }
            },
        )
        session =
            MediaSession
                .Builder(this, player)
                .setCallback(Callback(queueSession))
                // 通知・ロック画面のアートワークも、認証情報を持たない音声用のクライアントで取る
                .setBitmapLoader(
                    androidx.media3.datasource.DataSourceBitmapLoader
                        .Builder(this)
                        .setDataSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(container.mediaHttpClient))
                        .build(),
                )
                .build()

        // ログアウトしたら再生を止めてキューと解決済みの URL を消す（決定 E5）。
        // ログインし直した（別のログインに変わった）ときも、アカウントの切り替えとして同じく消す（2026-09-17 本人の決定）
        scope.launch {
            var loggedInAt: Long? = null
            container.authManager.state.collect { state ->
                when (state) {
                    AuthState.LoggedOut, AuthState.LoggingOut, is AuthState.Unreadable -> {
                        queueSession.reset()
                        container.streamCache.clear()
                        loggedInAt = null
                    }
                    is AuthState.LoggedIn -> {
                        if (loggedInAt != null && loggedInAt != state.obtainedAtEpochMs) {
                            queueSession.reset()
                            container.streamCache.clear()
                        }
                        loggedInAt = state.obtainedAtEpochMs
                    }
                    AuthState.Restoring -> Unit
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

    /**
     * このアプリの UI のコントローラーか。パッケージ名は名乗れるので uid で判定し、さらに接続ヒントの値で、
     * Media3 が同じ uid で作る通知用コントローラー（他アプリの Intent を転送しうる）と区別する。
     */
    private fun isOwnUiController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean =
        controller.uid == Process.myUid() &&
            !session.isMediaNotificationController(controller) &&
            controller.connectionHints.getString(PlayerConnection.CONNECTION_TOKEN_KEY) == container.controllerToken

    private inner class Callback(
        private val queue: QueueSession,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!isOwnUiController(session, controller)) {
                // 通知・システム（ロック画面・Bluetooth はプラットフォームのセッション経由）・信頼された外部は既定の再生操作だけ。
                // 信頼されていない他アプリの Media3 コントローラーは、キューの中身を読ませないために拒否する
                val allowed =
                    session.isMediaNotificationController(controller) ||
                        controller.isTrusted ||
                        controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
                return if (allowed) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
            }
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
            if (!isOwnUiController(session, controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 通知のカスタムボタンは使っていない。他アプリからの Intent でキューのコマンドを転送させない
        if (intent?.action == CUSTOM_NOTIFICATION_ACTION) return START_NOT_STICKY
        return super.onStartCommand(intent, flags, startId)
    }

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
        const val CUSTOM_NOTIFICATION_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
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
