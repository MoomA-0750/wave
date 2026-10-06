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
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.SongPage
import xyz.mooma0750.wave.core.music.UpNextPage
import xyz.mooma0750.wave.core.queue.QueueContentSource
import xyz.mooma0750.wave.core.queue.QueueSession
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.stream.StreamFailure
import xyz.mooma0750.wave.handoff.HandoffSnapshot

/** 再生の本体。画面が無くても動き、キューの続き（サジェスト）もここで取る。 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    // 保存などの例外でサービスごと落とさない（種類だけを記録する）
    private val scope =
        MainScope() +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                Log.e(WaveApplication.LOG_TAG, "uncaught in PlaybackService type=${error::class.qualifiedName}")
            }

    /** 直前のエラーが、この曲だけの失敗だったか（次が無くて飛ばせず止まった場合に、続きが来たら再開してよい）。 */
    private var lastErrorWasThisSong = false
    private var session: MediaSession? = null

    /** 手元の再生器（セッションの再生器は、ほかの端末を映している間だけ remotePlayer に替わる）。 */
    private var localPlayer: ExoPlayer? = null

    /** ほかの端末の再生を通知・ロック画面・Bluetooth に映す再生器（音は出さない）。 */
    private var remotePlayer: RemoteSessionPlayer? = null
    private var queue: QueueSession? = null
    private val skipGuard = SkipGuard(MAX_CONSECUTIVE_SKIPS)

    /** 今のログインの取得時刻。ログインしていなければ null。キューの保存に使う。 */
    private var loggedInAt: Long? = null

    /** 保存したキューを戻す判断が済んだか。済むまでは保存しない（戻す前の空のキューで上書きしない）。 */
    private var restoreSettled = false

    /** 自アプリの UI がつながったか。保存したキューはこのときだけ戻す（他アプリの bind・Intent での起動では戻さない、F1 outer gate 指摘1）。 */
    private var ownUiConnected = false
    private var restoreStarted = false
    private var saveJob: Job? = null
    private var periodicSaveJob: Job? = null
    private var likes: LikeController? = null

    /** 今の曲の画像を、URL ではなく画像そのものとして入れる（時計に出すため）。 */
    private var artwork: ArtworkEmbedder? = null

    /** 中継に接続しているか（引き取られるのを待ち受ける）。再生中と、一時停止から前面のタイムアウトの少し前までだけ（P6-S の計測で届くことを確認）。 */
    private var relayHeld = false

    /** Connect v2 の入口（Phase 6b）。 */
    private var connectV2Endpoint: ConnectV2Host.Endpoint? = null

    /** 取り込み・曲の選択・再開の処理の中（サービスの中の play() を、本人の再生ボタンとして知らせない）。 */
    private var connectV2Suppressed = false

    /** 渡す再生の操作（シャッフルのモードは除く）。 */
    private val playerCommands by lazy { withoutShuffleMode(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS) }

    /**
     * block の中で同期に来る Player の callback を、本人の操作（再生ボタン・一時停止）として Connect v2 に知らせない。
     * 効くのは Main で同期に呼ばれる間だけ（suspend や post を挟まないこと）。
     */
    private inline fun <T> suppressingV2(block: () -> T): T {
        connectV2Suppressed = true
        try {
            return block()
        } finally {
            connectV2Suppressed = false
        }
    }

    /**
     * 渡した（かもしれない）後、この端末で再生し直すまで true。この間のキューは相手の再生より古いので、引き取りの要求には渡さない
     * （2台で行き来したあと、古いキューで相手の今の再生を上書きした。2026-09-17 の実機確認）。
     * snapshot を作った時点で立てる（Ack が落ちても古いキューを渡さない）。Cancel と、この端末での再生で戻す
     */
    private var handedOff = false

    /** このプロセスで再生したか。保存から戻しただけのキュー（相手の再生より古いかもしれない）は渡さない。 */
    private var playedInProcess = false
    private var relayReleaseJob: Job? = null
    private var handoffEndpoint: HandoffHost.Endpoint? = null

    private lateinit var container: AppContainer

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
        val queueSession =
            QueueSession(
                Media3QueuePlayer(player, stoppedOnSkippableError = { lastErrorWasThisSong }),
                RepositoryContentSource(container.music),
                scope,
                newItemId = { UUID.randomUUID().toString() },
                loginGeneration = { container.music.currentLoginGeneration() },
            )
        queue = queueSession
        artwork = ArtworkEmbedder(player, container.mediaHttpClient, scope)
        val likeController =
            LikeController(
                container.music,
                scope,
                onChanged = { state -> publishLikeState(state) },
                onLikedSongsChanged = { container.onLikedSongsChanged() },
                resources = resources,
            )
        likes = likeController
        val historyTracker =
            PlaybackHistoryTracker(
                player,
                container.streamCache,
                container.playbackHistory,
                enabled = { container.settings.isHistoryEnabled() },
                scope = scope,
                elapsedMs = android.os.SystemClock::elapsedRealtime,
                random = java.security.SecureRandom().asKotlinRandom(),
            )
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 例外文や原因には URL が入りうるので、コードと型名だけを記録する
                    val cause = error.cause
                    Log.w(WaveApplication.LOG_TAG, "playback error code=${error.errorCodeName} cause=${cause?.javaClass?.simpleName} reason=${(cause as? StreamResolveException)?.reason}")
                    val failure = failureOf(error)
                    lastErrorWasThisSong = failure == PlaybackFailure.ThisSong
                    if (skipGuard.onError(failure, player.hasNextMediaItem(), android.os.SystemClock.elapsedRealtime())) {
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
                    // 今の曲が変わった・キューが置き換わった: 画像を入れる（入れ終わった曲では何もしない）
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) artwork?.onCurrentItemChanged()
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                        likeController.onCurrentSong(Media3QueuePlayer.songOf(player.currentMediaItem)?.videoId, TrackUri.epochOf(player.currentMediaItem?.localConfiguration?.uri))
                    }
                    // Connect v2（Phase 6b）: 位置の変化を知らせる。キューの変化は onTimelineChanged（並びが変わったときだけ）
                    container.connectV2Host.listener?.let { v2 ->
                        val significant = events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)
                        if (significant || events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_REPEAT_MODE_CHANGED)) v2.onCursorChanged(significant)
                    }
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    // 本人（画面・通知・Bluetooth）が再生を始めた。引き取りの取り込みは除く
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !connectV2Suppressed) {
                        val listener = container.connectV2Host.listener
                        if (playWhenReady) listener?.onUserStartedPlayback(newSelection = false) else listener?.onUserPaused()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    historyTracker.onIsPlayingChanged(isPlaying)
                    if (isPlaying) {
                        handedOff = false
                        playedInProcess = true
                    }
                    updateRelayHold(isPlaying)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (isPlaying) {
                        skipGuard.onPlaying(now)
                        lastErrorWasThisSong = false
                        // 復元したキューを再生し始めたときなど、曲の遷移を待たずに続きの取得を判断する
                        queueSession.onPlaybackProgressed()
                    } else {
                        skipGuard.onNotPlaying(now)
                    }
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
                    if (playbackState == Player.STATE_ENDED) queueSession.onPlaybackProgressed()
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    // 件数と位置だけ（曲名などは出さない）
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                        Log.i(WaveApplication.LOG_TAG, "queue size=${player.mediaItemCount} index=${player.currentMediaItemIndex}")
                        // 曲のソースの準備（SOURCE_UPDATE）では知らせない（レビュー p6b34 中-1）
                        container.connectV2Host.listener?.onQueueChanged()
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    Log.i(WaveApplication.LOG_TAG, "transition index=${player.currentMediaItemIndex} reason=$reason")
                    historyTracker.onMediaItemTransition()
                    queueSession.onPlaybackProgressed()
                }
            },
        )
        localPlayer = player
        session =
            MediaSession
                .Builder(this, player)
                .setCallback(Callback(queueSession))
                // 通知・ロック画面・時計から、アプリに戻れるようにする
                .setSessionActivity(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        android.content.Intent(this, MainActivity::class.java)
                            .setAction(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                // 通知・ロック画面のアートワークも、認証情報を持たない音声用のクライアントで取る
                // 取った画像は持っておく（毎回取り直すと、セッションの画像が入ったり抜けたりして、時計・車載に出ない）
                .setBitmapLoader(
                    androidx.media3.session.CacheBitmapLoader(
                        androidx.media3.datasource.DataSourceBitmapLoader
                            .Builder(this)
                            .setDataSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(container.mediaHttpClient))
                            // 既定では画面の大きさに合わせて縮む（時計・車載に渡す絵が小さくなる）
                            .setMaximumOutputDimension(ARTWORK_MAX_PIXELS)
                            .build(),
                    ),
                )
                .build()

        // ログアウトしたら再生を止めてキューと解決済みの URL を消す（決定 E5）。
        // ログインし直した（別のログインに変わった）ときも、アカウントの切り替えとして同じく消す（2026-09-17 本人の決定）
        val endpoint =
            object : HandoffHost.Endpoint {
                private var resumeAfterCancel = false

                override fun hasQueue(): Boolean = player.mediaItemCount > 0 && loggedInAt != null && playedInProcess && !handedOff

                override fun markHandedOff() {
                    resumeAfterCancel = false
                    // Ack より先に、本人がこの端末で再生を再開していたら、その再生は新しい
                    if (!player.isPlaying) handedOff = true
                    historyTracker.markHandedOff()
                    // 渡すものが無くなったので、一時停止後の待ち受けを続けない（相手の Ack の再送を受けてから離す）
                    if (!player.isPlaying && relayHeld) {
                        relayReleaseJob?.cancel()
                        relayReleaseJob =
                            scope.launch {
                                delay(HANDED_OFF_RELEASE_DELAY_MS)
                                relayHeld = false
                                relayReleaseJob = null
                                releaseRelays()
                            }
                    }
                }

                override fun resumeAfterCancel() {
                    handedOff = false
                    if (resumeAfterCancel) player.play()
                    resumeAfterCancel = false
                }

                override fun forgetHandedOff() {
                    handedOff = false
                }

                override fun capture(
                    handoffId: String,
                    expectedLoginGeneration: Long,
                ): HandoffSnapshot? {
                    // 確かめたアカウントのキューでなければ渡さない（確かめた後にアカウントが切り替わった）
                    if (queueSession.accountEpoch != expectedLoginGeneration || container.session.loginGenerationId() != expectedLoginGeneration) return null
                    val wasPlaying = player.isPlaying || player.playWhenReady
                    // 同じ相手の押し直しで2回目の capture が来ても、最初の「再生していた」を消さない
                    resumeAfterCancel = resumeAfterCancel || wasPlaying
                    handedOff = true
                    player.pause()
                    val (listenedMs, historySent) = historyTracker.handoffState()
                    return queueSession.handoffSnapshot(
                        handoffId = handoffId,
                        positionMs = player.currentPosition,
                        playing = wasPlaying,
                        repeatMode = player.repeatMode,
                        listenedMs = listenedMs,
                        historySent = historySent,
                        nowEpochMs = System.currentTimeMillis(),
                    )
                }

                override fun adopt(
                    snapshot: HandoffSnapshot,
                    expectedLoginGeneration: Long,
                ): Boolean {
                    if (loggedInAt == null || container.session.loginGenerationId() != expectedLoginGeneration) return false
                    handedOff = false
                    // 途中の復元を無効にし、受け取ったキューを保存で上書きしてよい状態にする
                    restoreStarted = true
                    restoreSettled = true
                    skipGuard.onUserSelection()
                    lastErrorWasThisSong = false
                    historyTracker.seedHandoff(snapshot.currentItemId, snapshot.listenedMs, snapshot.historySent)
                    player.repeatMode = PlaybackDecisions.repeatModeOf(snapshot.repeat)
                    // 確かめた世代に結び付ける（この後に切り替わっても、続きの取得・曲の解決は照合で止まる）
                    queueSession.adopt(snapshot, expectedLoginGeneration)
                    return true
                }
            }
        handoffEndpoint = endpoint
        container.handoffHost.register(endpoint)

        val v2Endpoint =
            object : ConnectV2Host.Endpoint {
                override fun snapshot(): HandoffSnapshot? {
                    if (loggedInAt == null) return null
                    // 中身（並び・出どころ・続き）が同じなら同じ文字列になるよう、位置・履歴・時刻は入れない（カーソルが持つ。キューを置き直さない判断に使う）
                    return queueSession.handoffSnapshot(
                        handoffId = V2_SNAPSHOT_ID,
                        positionMs = 0,
                        playing = false,
                        repeatMode = Player.REPEAT_MODE_OFF,
                        listenedMs = 0,
                        historySent = false,
                        nowEpochMs = 0,
                    )
                }

                override fun cursor(): ConnectV2Host.Cursor? {
                    val itemId = queueSession.currentItemId() ?: return null
                    val (listenedMs, historySent) = historyTracker.handoffState()
                    val repeat = PlaybackDecisions.repeatOf(player.repeatMode)
                    // 最後まで行った・エラーで止まった端末を「再生中」と書かない（レビュー p6b34 中-2）
                    val playing = PlaybackDecisions.isActivelyPlaying(player.playWhenReady, player.playbackState)
                    return ConnectV2Host.Cursor(itemId, player.currentPosition.coerceAtLeast(0), playing, repeat, listenedMs, historySent)
                }

                // Connect v2 が止める・戻すのは、本人の一時停止・再生ボタンとして知らせない
                override fun pause() = suppressingV2 { player.pause() }

                override fun resume() = suppressingV2 { player.play() }

                override fun adopt(
                    snapshot: HandoffSnapshot,
                    positionMs: Long,
                    expectedLoginGeneration: Long,
                ): Boolean = suppressingV2 { endpoint.adopt(snapshot.copy(positionMs = positionMs.coerceAtLeast(0), playing = true), expectedLoginGeneration) }

                override fun control(
                    action: xyz.mooma0750.wave.handoff.v2.ControlAction,
                    positionMs: Long?,
                ): Boolean =
                    suppressingV2 {
                        if (player.mediaItemCount == 0) return@suppressingV2 false
                        when (action) {
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Play -> player.play()
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Pause -> player.pause()
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Next -> player.seekToNextMediaItem()
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Previous -> player.seekToPrevious()
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Seek -> player.seekTo((positionMs ?: 0).coerceAtLeast(0))
                            xyz.mooma0750.wave.handoff.v2.ControlAction.Transfer -> return@suppressingV2 false
                        }
                        true
                    }
            }
        connectV2Endpoint = v2Endpoint
        container.connectV2Host.register(v2Endpoint)

        // ほかの端末を映している間は、通知・ロック画面・Bluetooth の再生器をその端末を映すものに替える（ボタンは遠隔操作になる）
        container.connectV2?.let { manager ->
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                manager.remotePlayback.collect { remote -> mirrorRemote(remote, manager) }
            }
        }

        // 前のアカウントの評価・履歴の送信の状態・キュー・解決済みの URL・保存を捨てる（ログアウト・アカウントの切り替え）
        suspend fun clearAccountState() {
            loggedInAt = null
            likeController.reset()
            historyTracker.reset()
            queueSession.reset()
            container.streamCache.clear()
            container.videoStreamCache.clear()
            container.queueStore.delete()
        }

        // 保存したキューを戻すのも、この購読の中で行う（ログアウト・切り替えの処理と順序を決めるため）
        scope.launch {
            container.authManager.state.collect { state ->
                when (state) {
                    AuthState.LoggedOut, AuthState.LoggingOut, is AuthState.Unreadable -> {
                        clearAccountState()
                        restoreSettled = true
                    }
                    is AuthState.LoggedIn -> {
                        val previous = loggedInAt
                        if (previous != null && previous != state.obtainedAtEpochMs) clearAccountState()
                        loggedInAt = state.obtainedAtEpochMs
                        // ログアウトの後のログインでは reset が走らないので、空のキューを今のログインの世代に結び付け直す
                        queueSession.rebindIfEmpty()
                        maybeRestore()
                    }
                    AuthState.Restoring -> Unit
                }
            }
        }
    }

    private fun updateRelayHold(isPlaying: Boolean) {
        if (!container.connectConfigured && container.connectV2 == null) return
        if (isPlaying) {
            relayReleaseJob?.cancel()
            relayReleaseJob = null
            if (!relayHeld) {
                relayHeld = true
                acquireRelays()
            }
        } else if (relayHeld && relayReleaseJob == null) {
            relayReleaseJob =
                scope.launch {
                    // 一時停止から、前面のタイムアウト（10分）の少し前まで待ち受ける。受け渡しの窓の間は延ばす
                    delay(RELAY_HOLD_AFTER_PAUSE_MS)
                    while (container.connect.holdingConnection) delay(10_000)
                    relayHeld = false
                    relayReleaseJob = null
                    releaseRelays()
                }
        }
    }

    /** v1 の中継と、設定されていれば Connect v2 の中継（Phase 6b、L-9 で並べて動かす）。 */
    private fun acquireRelays() {
        if (container.connectConfigured) container.relayClient.acquire()
        container.connectV2?.relay?.acquire()
    }

    private fun releaseRelays() {
        if (container.connectConfigured) container.relayClient.release()
        container.connectV2?.relay?.release()
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
                // 押した時点で見えていた表示の「逆」を目標にする（表示が古いまま押しても、逆向きに書き込まない）
                putBoolean(QueueCommands.KEY_TARGET_LIKED, !liked)
            }
        return listOf(
            CommandButton
                .Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (liked) R.string.playback_like_remove else R.string.playback_like))
                .setSessionCommand(SessionCommand(QueueCommands.TOGGLE_LIKE, extras))
                // 書き込み中も評価を読めていない間も、ボタンは残す。
                // 無効なボタンはプラットフォームのセッション（通知センター・時計・車載）の一覧から落ちるので、押すたびにハートが消えて見える
                .setEnabled(true)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
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

    /** 手元で鳴っていなければ、セッションの再生器をほかの端末を映すものにする。映すものが無ければ手元の再生器に戻す。 */
    private fun mirrorRemote(
        remote: xyz.mooma0750.wave.connect2.ConnectV2Manager.RemotePlayback?,
        manager: xyz.mooma0750.wave.connect2.ConnectV2Manager,
    ) {
        val current = session ?: return
        val local = localPlayer ?: return
        if (remote != null && !local.playWhenReady) {
            val mirror = remotePlayer ?: RemoteSessionPlayer({ action, position -> manager.control(action, position) }, { artist, device -> getString(xyz.mooma0750.wave.R.string.nowplaying_artist_on_device, artist, device) }).also { remotePlayer = it }
            mirror.update(remote)
            if (current.player !== mirror) current.player = mirror
        } else if (current.player !== local) {
            current.player = local
        }
    }

    /** ログインが分かっていて、自アプリの UI がつながっていれば、1回だけ保存したキューを戻す。 */
    private fun maybeRestore() {
        val obtainedAt = loggedInAt ?: return
        if (restoreStarted || restoreSettled || !ownUiConnected) return
        val queueSession = queue ?: return
        val player = localPlayer ?: return
        restoreStarted = true
        scope.launch {
            restoreSavedQueue(queueSession, player, obtainedAt)
            restoreSettled = true
        }
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
        val player = localPlayer ?: return
        // 保存内容を作る前に世代を読む（この後にログアウトの削除が走ったら書かない）
        val epoch = container.queueStore.currentEpoch()
        val saved = queueSession.saved(obtainedAt, player.currentPosition, player.repeatMode)
        if (saved == null) container.queueStore.delete() else container.queueStore.save(saved, epoch)
    }

    private fun failureOf(error: PlaybackException): PlaybackFailure = PlaybackDecisions.failureOf(error.cause, error.errorCode)

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

    /** 本人が通知の読み取りを許可したアプリ（時計・車載のコンパニオンなど）。 */
    private fun notificationListenerPackages(): Set<String> =
        runCatching { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this) }.getOrDefault(emptySet())

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
            val access =
                ControllerGate.classify(
                    isOwnNotificationController = isOwnNotificationController(session, controller),
                    isOwnUiController = isOwnUiController(session, controller),
                    isTrusted = controller.isTrusted,
                    // 通知を読める（本人が許可した）アプリは、通知のボタンを押せる立場なので、つないでも新しくできることは増えない。
                    // 時計・車載のコンパニオンはここに当たる（Media3 の isTrusted はこれを拾わないことがある）
                    readsOurNotification = { controller.packageName in notificationListenerPackages() },
                )
            return when (access) {
                // 通知用コントローラーには、評価のボタンのコマンドだけを足す（受けるときにトークンを確かめる）
                ControllerGate.Access.OwnNotification ->
                    MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                .buildUpon()
                                .add(SessionCommand(QueueCommands.TOGGLE_LIKE, Bundle.EMPTY))
                                .build(),
                        ).setAvailablePlayerCommands(playerCommands)
                        .setMediaButtonPreferences(likeButtons(likes?.state ?: LikeState()))
                        .build()
                // システム（ロック画面・Bluetooth はプラットフォームのセッション経由）・信頼された外部・通知を読めるアプリは、再生の操作だけ
                // （このアプリのキューの操作・評価のコマンドは渡さない）
                ControllerGate.Access.PlaybackOnly -> {
                    if (!controller.isTrusted) Log.i(WaveApplication.LOG_TAG, "controller accepted via notification access package=${controller.packageName}")
                    MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }
                // 信頼されていない他アプリは、Media3 のコントローラーでもレガシー（MediaBrowser で公開 Service につなぐ）でも拒否する。
                // レガシーで受け入れると、プラットフォームのセッションのトークンを渡すことになり、PlaybackState の custom action の
                // extras（通知の評価ボタンのトークン）とキューを読ませてしまう（F1d inner gate 指摘1）
                ControllerGate.Access.Reject -> {
                    Log.w(WaveApplication.LOG_TAG, "controller rejected package=${controller.packageName} uid=${controller.uid} version=${controller.controllerVersion}")
                    MediaSession.ConnectionResult.reject()
                }
                ControllerGate.Access.OwnUi -> {
                    val commands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .apply { QueueCommands.all.forEach(::add) }
                            .build()
                    MediaSession.ConnectionResult.accept(commands, playerCommands)
                }
            }
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
                val target = if (args.containsKey(QueueCommands.KEY_TARGET_LIKED)) args.getBoolean(QueueCommands.KEY_TARGET_LIKED) else null
                val accepted = likes?.setLiked(args.getString(QueueCommands.KEY_VIDEO_ID), target) == true
                return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
            }
            if (!isOwnUiController(session, controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (customCommand.customAction in USER_SELECTIONS) skipGuard.onUserSelection()
            val local = localPlayer ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            val wasEmpty = local.mediaItemCount == 0
            val wasPlaying = local.playWhenReady
            // 曲を選ぶ処理の中の play() は、再生ボタン（D-9）として知らせない。後で「曲を選んだ」を1回だけ知らせる（レビュー p6b34 高-1）
            val handled =
                suppressingV2 {
                    when (customCommand.customAction) {
                        QueueCommands.PLAY_SONG -> QueueCommands.songOf(args)?.let { queue.playSong(it) }
                        QueueCommands.PLAY_FROM ->
                            QueueCommands.playFromOf(args)?.let { queue.playFrom(it.songs, it.startIndex, it.playlistId, it.continuation, it.shuffle) }
                        QueueCommands.ADD_NEXT -> QueueCommands.songsOf(args)?.let { queue.addNext(it) } ?: QueueCommands.songOf(args)?.let { queue.addNext(it) }
                        QueueCommands.ADD_TO_END -> QueueCommands.songsOf(args)?.let { queue.addToEnd(it) } ?: QueueCommands.songOf(args)?.let { queue.addToEnd(it) }
                        QueueCommands.REMOVE -> QueueCommands.itemIdOf(args)?.let { queue.remove(it) }
                        QueueCommands.JUMP -> QueueCommands.itemIdOf(args)?.let { queue.jumpTo(it) }
                        QueueCommands.SHUFFLE_UPCOMING -> queue.shuffleUpcoming()
                        else -> null
                    }
                }
            // ほかの端末を映している間に曲を選んだ: 手元では鳴らさず（用意だけ）、その端末で鳴らしてもらう（Spotify と同じ）
            val remoteTarget = container.connectV2?.remotePlayback?.value?.deviceId
            if (handled != null && remoteTarget != null && customCommand.customAction in USER_SELECTIONS && session.player === remotePlayer) {
                suppressingV2 { local.pause() }
                container.connectV2?.playOnRemote(remoteTarget)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            // 曲を選んだ（キューを置き換えた・飛んだ）のは、本人が新しく再生を始めたこと（L-3）
            // 空のキュー・最後まで行ったキューに「次に再生」「最後に追加」で足して再生が始まった場合も、新しい再生（再レビュー 低-O）
            val newSelection =
                PlaybackDecisions.startedNewSelection(
                    handled = handled != null,
                    userSelection = customCommand.customAction in USER_SELECTIONS,
                    wasEmpty = wasEmpty,
                    wasPlaying = wasPlaying,
                    itemCountAfter = local.mediaItemCount,
                    playWhenReadyAfter = local.playWhenReady,
                )
            if (newSelection) container.connectV2Host.listener?.onUserStartedPlayback(newSelection = true)
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
            if (isOwnUiController(session, controller)) {
                likes?.let { session.setSessionExtras(controller, it.state.toBundle()) }
                ownUiConnected = true
                maybeRestore()
            }
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
        val player = localPlayer
        val playing = player != null && PlaybackDecisions.isActivelyPlaying(player.playWhenReady, player.playbackState)
        if (!playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        artwork?.release()
        artwork = null
        relayReleaseJob?.cancel()
        if (relayHeld) releaseRelays()
        relayHeld = false
        handoffEndpoint?.let { container.handoffHost.unregister(it) }
        connectV2Endpoint?.let { container.connectV2Host.unregister(it) }
        handoffEndpoint = null
        // scope を止めると待っている保存が捨てられるので、その前に最後の状態を書く
        val obtainedAt = loggedInAt
        val queueSession = queue
        val lastPlayer = localPlayer
        if (restoreSettled && obtainedAt != null && queueSession != null && lastPlayer != null) {
            val epoch = container.queueStore.currentEpoch()
            queueSession.saved(obtainedAt, lastPlayer.currentPosition, lastPlayer.repeatMode)?.let { container.queueStore.saveBlocking(it, epoch) }
        }
        scope.cancel()
        session?.release()
        localPlayer?.release()
        remotePlayer?.release()
        session = null
        localPlayer = null
        remotePlayer = null
        queue = null
        likes = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONSECUTIVE_SKIPS = 3
        const val SAVE_DEBOUNCE_MS = 1_000L
        const val RELAY_HOLD_AFTER_PAUSE_MS = 9 * 60 * 1000L
        private const val HANDED_OFF_RELEASE_DELAY_MS = 5_000L
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
        const val V2_SNAPSHOT_ID = "00000000-0000-4000-8000-000000000000"

        /** 通知・ロック画面・時計に渡す画像の、長辺の上限。 */
        const val ARTWORK_MAX_PIXELS = 1024
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
        expectedGeneration: Long?,
    ): CallResult<UpNextPage> = music.upNext(videoId, playlistId, continuation, expectedGeneration)

    override suspend fun playlistPage(
        playlistId: String,
        continuation: String,
        expectedGeneration: Long?,
    ): CallResult<SongPage> =
        when (val result = music.playlist(playlistId, continuation, expectedGeneration)) {
            is CallResult.Ok -> CallResult.Ok(SongPage(result.value.songs, result.value.continuation))
            is CallResult.Failed -> result
        }
}

/** 再試行しない失敗と、時間をおく失敗を分ける。 */
@OptIn(UnstableApi::class)
class WaveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        PlaybackDecisions.retryDelayFor(loadErrorInfo.exception, loadErrorInfo.errorCount) ?: super.getRetryDelayMsFor(loadErrorInfo)
}
