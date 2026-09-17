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
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import xyz.mooma0750.wave.core.music.LikeStatus
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** 今のログインの取得時刻。ログインしていなければ null。キューの保存に使う。 */
    private var loggedInAt: Long? = null

    /** 保存したキューを戻す判断が済んだか。済むまでは保存しない（戻す前の空のキューで上書きしない）。 */
    private var restoreSettled = false
    private var saveJob: Job? = null
    private var periodicSaveJob: Job? = null
    private var likes: LikeController? = null

    private lateinit var container: xyz.mooma0750.wave.AppContainer

    override fun onCreate() {
        super.onCreate()
        container = (application as WaveApplication).container
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
        val likeController =
            LikeController(
                container.music,
                scope,
                onChanged = { state -> publishLikeState(state) },
                onLikedSongsChanged = { container.onLikedSongsChanged() },
            )
        likes = likeController
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

                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (events.containsAny(*SAVE_EVENTS)) scheduleSave()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                        likeController.onCurrentSong(Media3QueuePlayer.songOf(player.currentMediaItem)?.videoId)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    periodicSaveJob?.cancel()
                    if (isPlaying) {
                        periodicSaveJob =
                            scope.launch {
                                while (true) {
                                    delay(PERIODIC_SAVE_MS)
                                    saveNow()
                                }
                            }
                    }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    queueSession.onRepeatModeChanged()
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
        // 保存したキューを戻すのも、この購読の中で行う（ログアウト・切り替えの処理と順序を決めるため）
        scope.launch {
            container.authManager.state.collect { state ->
                when (state) {
                    AuthState.LoggedOut, AuthState.LoggingOut, is AuthState.Unreadable -> {
                        loggedInAt = null
                        likeController.reset()
                        queueSession.reset()
                        container.streamCache.clear()
                        container.queueStore.delete()
                        restoreSettled = true
                    }
                    is AuthState.LoggedIn -> {
                        val previous = loggedInAt
                        if (previous != null && previous != state.obtainedAtEpochMs) {
                            loggedInAt = null
                            likeController.reset()
                            queueSession.reset()
                            container.streamCache.clear()
                            container.queueStore.delete()
                        }
                        loggedInAt = state.obtainedAtEpochMs
                        if (!restoreSettled) {
                            restoreSavedQueue(queueSession, player, state.obtainedAtEpochMs)
                            restoreSettled = true
                        }
                    }
                    AuthState.Restoring -> Unit
                }
            }
        }
    }

    /** 評価を自アプリの画面（コントローラーごとの extras）と通知のボタンへ配る。 */
    private fun publishLikeState(state: LikeState) {
        val current = session ?: return
        val bundle = state.toBundle()
        current.connectedControllers.filter { isOwnUiController(current, it) }.forEach { current.setSessionExtras(it, bundle) }
        current.setMediaButtonPreferences(likeButtons(state))
    }

    /** Media3 がプロセス内に作る通知用コントローラーか。パッケージ名と接続ヒントだけで判定されるので uid も見る。 */
    private fun isOwnNotificationController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean = controller.uid == Process.myUid() && session.isMediaNotificationController(controller)

    private fun likeButtons(state: LikeState): List<CommandButton> {
        val videoId = state.videoId ?: return emptyList()
        val liked = state.status == LikeStatus.Like
        val extras =
            Bundle().apply {
                putString(QueueCommands.KEY_NOTIFICATION_TOKEN, container.notificationCommandToken)
                putString(QueueCommands.KEY_VIDEO_ID, videoId)
            }
        return listOf(
            CommandButton
                .Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (liked) "評価を外す" else "高く評価")
                .setSessionCommand(SessionCommand(QueueCommands.TOGGLE_LIKE, extras))
                .setEnabled(state.status != null && !state.pending)
                .build(),
        )
    }

    /** 通知のボタンから来た値か。定数時間で比べる。Bundle が壊れていても落ちない。 */
    private fun hasNotificationToken(extras: Bundle?): Boolean =
        try {
            val token = extras?.getString(QueueCommands.KEY_NOTIFICATION_TOKEN) ?: return false
            java.security.MessageDigest.isEqual(token.encodeToByteArray(), container.notificationCommandToken.encodeToByteArray())
        } catch (_: RuntimeException) {
            false
        }

    /**
     * 保存したキューを、再生を始めずに戻す。保存時と今のログインが違えばファイルを消す。
     * 読み込みの間に UI から曲が選ばれた・ログインが変わったなら戻さない。
     */
    private suspend fun restoreSavedQueue(
        queueSession: QueueSession,
        player: Player,
        obtainedAt: Long,
    ) {
        val saved = container.queueStore.load() ?: return
        if (saved.obtainedAtEpochMs != obtainedAt) {
            container.queueStore.delete()
            return
        }
        val now = container.authManager.state.value
        if (now !is AuthState.LoggedIn || now.obtainedAtEpochMs != obtainedAt || player.mediaItemCount > 0) return
        queueSession.restore(saved)
        player.repeatMode = saved.repeatMode
        Log.i(WaveApplication.LOG_TAG, "queue restored size=${saved.items.size} index=${saved.currentIndex}")
    }

    /** 連続した変更は1回にまとめて保存する。 */
    private fun scheduleSave() {
        if (!restoreSettled) return
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveNow()
            }
    }

    private suspend fun saveNow() {
        if (!restoreSettled) return
        val obtainedAt = loggedInAt ?: return
        val queueSession = queue ?: return
        val player = session?.player ?: return
        // 保存内容を作る前に世代を読む（この後にログアウトの削除が走ったら書かない）
        val epoch = container.queueStore.currentEpoch()
        val saved = queueSession.saved(obtainedAt, player.currentPosition, player.repeatMode)
        if (saved == null) container.queueStore.delete() else container.queueStore.save(saved, epoch)
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
            if (container.isDebuggable) {
                Log.i(WaveApplication.LOG_TAG, "connect package=${controller.packageName} uid=${controller.uid} trusted=${controller.isTrusted} notification=${session.isMediaNotificationController(controller)} version=${controller.controllerVersion}")
            }
            if (isOwnNotificationController(session, controller)) {
                // 通知用コントローラーには、評価のボタンのコマンドだけを足す（受けるときにトークンを確かめる）
                return MediaSession.ConnectionResult
                    .AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .add(SessionCommand(QueueCommands.TOGGLE_LIKE, Bundle.EMPTY))
                            .build(),
                    ).setAvailablePlayerCommands(withoutShuffleMode(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS))
                    .setMediaButtonPreferences(likeButtons(likes?.state ?: LikeState()))
                    .build()
            }
            if (!isOwnUiController(session, controller)) {
                // システム（ロック画面・Bluetooth はプラットフォームのセッション経由）・信頼された外部は既定の再生操作だけ。
                // 信頼されていない他アプリは、Media3 のコントローラーでもレガシー（MediaBrowser で公開 Service につなぐ）でも拒否する。
                // レガシーで受け入れると、プラットフォームのセッションのトークンを渡すことになり、PlaybackState の custom action の
                // extras（通知の評価ボタンのトークン）とキューを読ませてしまう（F1d inner gate 指摘1）
                if (!controller.isTrusted) {
                    Log.w(WaveApplication.LOG_TAG, "controller rejected package=${controller.packageName} uid=${controller.uid} version=${controller.controllerVersion}")
                }
                return if (controller.isTrusted) {
                    MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session, controller)
                        .setAvailablePlayerCommands(withoutShuffleMode(if (controller.isTrusted) MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS else MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS))
                        .build()
                } else {
                    MediaSession.ConnectionResult.reject()
                }
            }
            val commands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .apply { QueueCommands.all.forEach(::add) }
                    .build()
            return MediaSession.ConnectionResult.accept(commands, withoutShuffleMode(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS))
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == QueueCommands.TOGGLE_LIKE) {
                // Android 13 以降のシステムのメディア操作は、Media3 が通知用コントローラーに差し替えて届ける。extras を返すかは SystemUI 次第（未確認）
                val allowed = isOwnUiController(session, controller) || (isOwnNotificationController(session, controller) && hasNotificationToken(args))
                if (!allowed) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val accepted = likes?.toggle(args.getString(QueueCommands.KEY_VIDEO_ID)) == true
                return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
            }
            if (!isOwnUiController(session, controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (customCommand.customAction in USER_SELECTIONS) skipGuard.onUserSelection()
            val handled =
                when (customCommand.customAction) {
                    QueueCommands.PLAY_SONG -> QueueCommands.songOf(args)?.let { queue.playSong(it) }
                    QueueCommands.PLAY_FROM ->
                        QueueCommands.playFromOf(args)?.let { queue.playFrom(it.songs, it.startIndex, it.playlistId, it.continuation, it.shuffle) }
                    QueueCommands.ADD_NEXT -> QueueCommands.songOf(args)?.let { queue.addNext(it) }
                    QueueCommands.ADD_TO_END -> QueueCommands.songOf(args)?.let { queue.addToEnd(it) }
                    QueueCommands.REMOVE -> QueueCommands.itemIdOf(args)?.let { queue.remove(it) }
                    QueueCommands.JUMP -> QueueCommands.itemIdOf(args)?.let { queue.jumpTo(it) }
                    QueueCommands.SHUFFLE_UPCOMING -> queue.shuffleUpcoming()
                    else -> null
                }
            return Futures.immediateFuture(SessionResult(if (handled != null) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (container.isDebuggable) {
                Log.i(WaveApplication.LOG_TAG, "media button package=${controllerInfo.packageName} uid=${controllerInfo.uid} trusted=${controllerInfo.isTrusted} notification=${session.isMediaNotificationController(controllerInfo)} version=${controllerInfo.controllerVersion}")
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (isOwnUiController(session, controller)) likes?.let { session.setSessionExtras(controller, it.state.toBundle()) }
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
        // 通知のカスタムボタンは評価だけ。トークンの無い Intent（他アプリからの明示的 Intent）は、通知用コントローラーへ転送させない
        if (intent?.action == CUSTOM_NOTIFICATION_ACTION && !isOwnNotificationIntent(intent)) {
            Log.w(WaveApplication.LOG_TAG, "notification intent dropped")
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun isOwnNotificationIntent(intent: Intent): Boolean =
        try {
            intent.getStringExtra(EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION) == QueueCommands.TOGGLE_LIKE &&
                hasNotificationToken(intent.getBundleExtra(EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS))
        } catch (_: RuntimeException) {
            false
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val playing = player != null && player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // scope を止めると待っている保存が捨てられるので、その前に最後の状態を書く
        val obtainedAt = loggedInAt
        val queueSession = queue
        val lastPlayer = session?.player
        if (restoreSettled && obtainedAt != null && queueSession != null && lastPlayer != null) {
            val epoch = container.queueStore.currentEpoch()
            queueSession.saved(obtainedAt, lastPlayer.currentPosition, lastPlayer.repeatMode)?.let { container.queueStore.saveBlocking(it, epoch) }
        }
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        queue = null
        likes = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONSECUTIVE_SKIPS = 3
        const val SAVE_DEBOUNCE_MS = 1_000L
        const val PERIODIC_SAVE_MS = 60_000L
        val SAVE_EVENTS =
            intArrayOf(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        val USER_SELECTIONS = setOf(QueueCommands.PLAY_SONG, QueueCommands.PLAY_FROM, QueueCommands.JUMP)
        const val CUSTOM_NOTIFICATION_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
        const val EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION = "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION"
        const val EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS = "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS"

        /**
         * すべてのコントローラーから、シャッフルモードの切り替えを外す（決定 G-5）。
         * Media3 のシャッフル順は並びと別に持つので、Bluetooth などから有効にされるとキューの表示と続きの取得が食い違う
         */
        fun withoutShuffleMode(commands: Player.Commands): Player.Commands = commands.buildUpon().remove(Player.COMMAND_SET_SHUFFLE_MODE).build()
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
