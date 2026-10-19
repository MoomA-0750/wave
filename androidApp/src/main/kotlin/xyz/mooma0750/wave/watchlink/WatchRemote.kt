package xyz.mooma0750.wave.watchlink

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.playback.Media3QueuePlayer

/** 相手の端末（時計から見たスマホ、スマホから見た時計）の再生の様子。 */
@Serializable
data class RemoteState(
    val deviceName: String,
    val title: String? = null,
    val artist: String? = null,
    val videoId: String? = null,
    val thumbnailUrl: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val liked: Boolean? = null,
    val hasQueue: Boolean = false,
    /** スマホが Connect v2 でほかの端末（Windows など）の再生を映しているとき、その端末の名前。操作はその端末に届く。 */
    val playingOn: String? = null,
    /** スマホから再生を移せる、家（Connect v2）のほかの端末。 */
    val devices: List<RemoteDevice> = emptyList(),
)

@Serializable
data class RemoteDevice(
    val deviceId: String,
    val label: String,
    val online: Boolean,
    val isPc: Boolean,
)

/**
 * スマホと時計の間の再生の操作と受け渡し（中継を使わず、Data Layer で直接）。どちらの端末でも同じ部品が動き、
 * 相手の再生を見て操作する側と、自分の再生を見せて操作される側の両方を持つ。キューの受け渡しは、端末間の受け渡し（ADR-0009）と同じスナップショットを使う。
 * 届くのは同じパッケージ・同じ署名の wave からだけ（Data Layer の決まり）。スマホから時計へログインを渡しているので、同じアカウントのもの。
 */
class WatchRemote(
    context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val isWatch = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private val json = Json { ignoreUnknownKeys = true }

    /** 相手の印（時計からはスマホ、スマホからは時計）。 */
    private val peerCapability = if (isWatch) WatchLogin.CAPABILITY_PHONE else CAPABILITY_WATCH

    /** 相手の再生（見ている間だけ届く）。相手が見つからなければ null。 */
    private val mutablePeer = MutableStateFlow<RemoteState?>(null)
    val peer: StateFlow<RemoteState?> = mutablePeer.asStateFlow()

    /** 相手を探している・見つからなかった。 */
    enum class PeerStatus { Searching, Found, NotFound }

    private val mutablePeerStatus = MutableStateFlow(PeerStatus.Searching)
    val peerStatus: StateFlow<PeerStatus> = mutablePeerStatus.asStateFlow()

    /** 受け渡しの結果（画面で知らせる）。 */
    enum class Result { Pulled, Pushed, NothingToPull, Failed }

    private val mutableResult = MutableStateFlow<Pair<Int, Result>?>(null)
    val result: StateFlow<Pair<Int, Result>?> = mutableResult.asStateFlow()
    private var resultId = 0

    private var peerNode: String? = null
    private var watchJob: Job? = null

    /** 相手の再生を見始める（画面を開いている間、少しずつ延ばす）。 */
    fun watchPeer() {
        if (watchJob?.isActive == true) return
        watchJob =
            scope.launch {
                while (isActive) {
                    val node = findPeer()
                    if (node == null) {
                        mutablePeerStatus.value = PeerStatus.NotFound
                        mutablePeer.value = null
                    } else {
                        mutablePeerStatus.value = PeerStatus.Found
                        send(node, PATH_SUBSCRIBE, ByteArray(0))
                    }
                    delay(SUBSCRIBE_RENEW_MS)
                }
            }
    }

    fun stopWatchingPeer() {
        watchJob?.cancel()
        watchJob = null
    }

    /** 相手の再生を操作する。 */
    fun command(command: String) {
        scope.launch { peerNode?.let { send(it, PATH_COMMAND, command.encodeToByteArray()) } }
    }

    /** 相手の再生をこの端末へ移す（相手は一時停止する）。 */
    fun pull() {
        scope.launch {
            val node = peerNode ?: findPeer() ?: return@launch report(Result.Failed)
            val handoffId = UUID.randomUUID().toString()
            pendingPulls.add(handoffId)
            if (!send(node, PATH_PULL, handoffId.encodeToByteArray())) {
                pendingPulls.remove(handoffId)
                report(Result.Failed)
            }
        }
    }

    /** この端末の再生を相手へ移す（受け取れたら、この端末は止まったまま）。 */
    fun push() {
        scope.launch {
            val node = peerNode ?: findPeer() ?: return@launch report(Result.Failed)
            container.player.controller()
            val handoffId = UUID.randomUUID().toString()
            val snapshot = onMain { container.handoffHost.endpoint?.capture(handoffId, container.session.loginGenerationId()) }
            if (snapshot == null) return@launch report(Result.NothingToPull)
            pendingPushes.add(handoffId)
            if (!send(node, PATH_PUSH, encodeForMessage(snapshot).encodeToByteArray())) {
                pendingPushes.remove(handoffId)
                onMain { container.handoffHost.endpoint?.resumeAfterCancel() }
                report(Result.Failed)
                return@launch
            }
            // 相手が答えないまま（プロセスが落ちた・届かない）なら、止めた再生を戻す
            delay(ACK_TIMEOUT_MS)
            if (pendingPushes.remove(handoffId)) {
                onMain { container.handoffHost.endpoint?.resumeAfterCancel() }
                report(Result.Failed)
            }
        }
    }

    /** 引き取られた側が答えを待っている受け渡し（答えが来なければ、止めた再生を戻す）。 */
    private val awaitingPullAck = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Data Layer のメッセージは約 100KB までなので、超えるときは今の曲より後ろを減らす（後ろを切ったら続きの読み込みはしない）。
     */
    private fun encodeForMessage(snapshot: xyz.mooma0750.wave.handoff.HandoffSnapshot): String {
        var current = snapshot
        var text = HandoffCodec.encode(current)
        while (text.encodeToByteArray().size > MAX_MESSAGE_BYTES) {
            val index = current.items.indexOfFirst { it.itemId == current.currentItemId }
            val upcoming = current.items.size - index - 1
            if (index < 0 || upcoming <= 0) break
            current = current.copy(items = current.items.take(index + 1 + upcoming / 2), continuation = null)
            text = HandoffCodec.encode(current)
        }
        return text
    }

    private val pendingPulls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingPushes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var publishJob: Job? = null
    private var publishUntilMs = 0L

    /** メッセージの入口（WatchLinkListener から）。 */
    fun onMessage(
        nodeId: String,
        path: String,
        data: ByteArray,
    ) {
        // ログインしていない端末は、再生を見せず・受け取らない（メッセージでプロセスが起きた直後は、ログインを読み終えるまで待つ）
        scope.launch { if (awaitSettledLogin(container.authManager)) handle(nodeId, path, data) }
    }

    private fun handle(
        nodeId: String,
        path: String,
        data: ByteArray,
    ) {
        when (path) {
            PATH_SUBSCRIBE -> startPublishing(nodeId)
            PATH_STATE -> json.runCatching { decodeFromString(RemoteState.serializer(), data.decodeToString()) }.getOrNull()?.let { mutablePeer.value = it }
            PATH_COMMAND -> scope.launch { runCommand(data.decodeToString()) }
            PATH_PULL -> scope.launch { answerPull(nodeId, data.decodeToString()) }
            PATH_SNAPSHOT -> scope.launch { adoptPulled(nodeId, data.decodeToString()) }
            PATH_PULLED -> scope.launch { finishPulled(data.decodeToString()) }
            PATH_PUSH -> scope.launch { adoptPushed(nodeId, data.decodeToString()) }
            PATH_PUSHED -> scope.launch { finishPushed(data.decodeToString()) }
        }
    }

    /** 見られる側: しばらくの間、自分の再生の様子を相手へ送り続ける。 */
    private fun startPublishing(nodeId: String) {
        publishUntilMs = System.currentTimeMillis() + PUBLISH_MS
        if (publishJob?.isActive == true) return
        publishJob =
            scope.launch {
                var last: RemoteState? = null
                var lastSentAt = 0L
                while (isActive && System.currentTimeMillis() < publishUntilMs) {
                    val state = currentState()
                    val now = System.currentTimeMillis()
                    // 位置だけの変化は5秒ごとに送る（相手は間を自分で進める）
                    if (state.copy(positionMs = 0) != last?.copy(positionMs = 0) || now - lastSentAt > POSITION_SEND_MS) {
                        send(nodeId, PATH_STATE, json.encodeToString(RemoteState.serializer(), state).encodeToByteArray())
                        last = state
                        lastSentAt = now
                    }
                    delay(PUBLISH_POLL_MS)
                }
            }
    }

    private suspend fun currentState(): RemoteState {
        // 再生サービスが動いていなければ起こさない（何も鳴っていない）
        val controller = if (onMain { container.handoffHost.endpoint } != null) container.player.controller() else null
        return onMain {
            val like = container.player.likeState.value
            controller?.let { controller ->
                val song = controller.currentMediaItem?.let(Media3QueuePlayer::songOf)
                RemoteState(
                    deviceName = android.os.Build.MODEL,
                    title = song?.title,
                    artist = song?.subtitle,
                    videoId = song?.videoId,
                    thumbnailUrl = song?.thumbnailUrl,
                    playing = controller.isPlaying,
                    positionMs = controller.currentPosition,
                    durationMs = controller.duration.coerceAtLeast(0),
                    liked = if (like.videoId == song?.videoId) like.status?.let { it == LikeStatus.Like } else null,
                    hasQueue = controller.mediaItemCount > 0,
                )
            } ?: RemoteState(deviceName = android.os.Build.MODEL)
        }.withConnect()
    }

    /** スマホの Connect v2 の様子（映している端末と、移せる端末）を足す。 */
    private fun RemoteState.withConnect(): RemoteState {
        val manager = container.connectV2 ?: return this
        val remote = manager.remotePlayback.value
        val devices =
            manager.members.value
                .filter { it.role == xyz.mooma0750.wave.handoff.v2.Role.Player && !it.isApi && !it.isSelf && it.deviceId != remote?.deviceId }
                .map { RemoteDevice(it.deviceId, it.label, it.online, it.isPc) }
        return copy(playingOn = remote?.deviceLabel, devices = devices)
    }

    private suspend fun runCommand(command: String) {
        val controller = container.player.controller()
        onMain {
            when (command) {
                CMD_PLAY -> controller.play()
                CMD_PAUSE -> controller.pause()
                CMD_NEXT -> controller.seekToNext()
                CMD_PREVIOUS -> controller.seekToPrevious()
                CMD_VOLUME_UP, CMD_VOLUME_DOWN ->
                    (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .adjustStreamVolume(AudioManager.STREAM_MUSIC, if (command == CMD_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                else -> Unit
            }
        }
        when {
            command.startsWith(CMD_PLAY_ON) -> container.connectV2?.playOnDevice(command.removePrefix(CMD_PLAY_ON))
            command == CMD_PLAY_HERE -> container.connectV2?.transfer()
        }
        when (command) {
            CMD_LIKE, CMD_UNLIKE -> container.player.likeState.value.videoId?.let { container.player.setLiked(it, command == CMD_LIKE) }
        }
        Log.i(WaveApplication.LOG_TAG, "remote command=$command")
    }

    /** 引き取られる側: 一時停止して、キューを渡す。 */
    private suspend fun answerPull(
        nodeId: String,
        handoffId: String,
    ) {
        container.player.controller()
        val snapshot = onMain { container.handoffHost.endpoint?.capture(handoffId, container.session.loginGenerationId()) }
        val text = snapshot?.let(::encodeForMessage).orEmpty()
        if (snapshot != null) awaitingPullAck.add(handoffId)
        val sent = send(nodeId, PATH_SNAPSHOT, "$handoffId\n$text".encodeToByteArray())
        Log.i(WaveApplication.LOG_TAG, "remote pull answered has=${snapshot != null} sent=$sent")
        if (snapshot == null) return
        if (!sent) {
            awaitingPullAck.remove(handoffId)
            onMain { container.handoffHost.endpoint?.resumeAfterCancel() }
            return
        }
        // 相手が答えないまま（プロセスが落ちた・届かない）なら、止めた再生を戻す
        delay(ACK_TIMEOUT_MS)
        if (awaitingPullAck.remove(handoffId)) onMain { container.handoffHost.endpoint?.resumeAfterCancel() }
    }

    /** 引き取る側: 届いたキューで再生を始め、結果を返す。 */
    private suspend fun adoptPulled(
        nodeId: String,
        message: String,
    ) {
        val handoffId = message.substringBefore('\n')
        val text = message.substringAfter('\n', "")
        // 頼んだ覚えが無い（このプロセスが途中で落ちた）ものは受けず、相手に戻してもらう
        if (!pendingPulls.remove(handoffId)) {
            if (text.isNotEmpty()) send(nodeId, PATH_PULLED, "$handoffId\nfalse".encodeToByteArray())
            return
        }
        if (text.isEmpty()) {
            report(Result.NothingToPull)
            return
        }
        val adopted = adopt(text, handoffId)
        send(nodeId, PATH_PULLED, "$handoffId\n$adopted".encodeToByteArray())
        report(if (adopted) Result.Pulled else Result.Failed)
    }

    private suspend fun finishPulled(message: String) {
        if (!awaitingPullAck.remove(message.substringBefore('\n'))) return
        val adopted = message.substringAfter('\n') == "true"
        onMain { container.handoffHost.endpoint?.let { if (adopted) it.markHandedOff() else it.resumeAfterCancel() } }
    }

    /** 受け取る側（相手から押し付けられた）: キューで再生を始め、結果を返す。 */
    private suspend fun adoptPushed(
        nodeId: String,
        text: String,
    ) {
        val handoffId = runCatching { json.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonObject)["handoffId"].toString().trim('"') } }.getOrNull() ?: return
        val adopted = adopt(text, handoffId)
        send(nodeId, PATH_PUSHED, "$handoffId\n$adopted".encodeToByteArray())
        Log.i(WaveApplication.LOG_TAG, "remote push adopted=$adopted")
    }

    private suspend fun finishPushed(message: String) {
        val handoffId = message.substringBefore('\n')
        if (!pendingPushes.remove(handoffId)) return
        val adopted = message.substringAfter('\n') == "true"
        onMain { container.handoffHost.endpoint?.let { if (adopted) it.markHandedOff() else it.resumeAfterCancel() } }
        report(if (adopted) Result.Pushed else Result.Failed)
    }

    private suspend fun adopt(
        text: String,
        handoffId: String,
    ): Boolean {
        val decoded = HandoffCodec.decode(text, handoffId) as? HandoffDecodeResult.Ok ?: return false
        container.player.controller()
        return onMain { container.handoffHost.endpoint?.adopt(decoded.snapshot, container.session.loginGenerationId()) == true }
    }

    private fun report(result: Result) {
        mutableResult.value = ++resultId to result
        Log.i(WaveApplication.LOG_TAG, "remote result=$result")
    }

    private suspend fun findPeer(): String? {
        val nodes =
            runCatching { Wearable.getCapabilityClient(appContext).getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes }
                .getOrNull()
                .orEmpty()
        // 近くにあるもの（Bluetooth でつながっているもの）を先に
        val node = nodes.sortedByDescending { it.isNearby }.firstOrNull()
        peerNode = node?.id
        return node?.id
    }

    private suspend fun send(
        nodeId: String,
        path: String,
        data: ByteArray,
    ): Boolean =
        try {
            Wearable.getMessageClient(appContext).sendMessage(nodeId, path, data).await()
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    companion object {
        const val CAPABILITY_WATCH = "wave_watch"
        const val PATH_SUBSCRIBE = "/wave/remote/subscribe"
        const val PATH_STATE = "/wave/remote/state"
        const val PATH_COMMAND = "/wave/remote/command"
        const val PATH_PULL = "/wave/remote/pull"
        const val PATH_SNAPSHOT = "/wave/remote/snapshot"
        const val PATH_PULLED = "/wave/remote/pulled"
        const val PATH_PUSH = "/wave/remote/push"
        const val PATH_PUSHED = "/wave/remote/pushed"

        const val CMD_PLAY = "play"
        const val CMD_PAUSE = "pause"
        const val CMD_NEXT = "next"
        const val CMD_PREVIOUS = "previous"
        const val CMD_LIKE = "like"
        const val CMD_UNLIKE = "unlike"
        const val CMD_VOLUME_UP = "volume_up"
        const val CMD_VOLUME_DOWN = "volume_down"

        /** スマホに、Connect v2 のその端末へ再生を移してもらう（後ろに端末の ID）。 */
        const val CMD_PLAY_ON = "play_on:"

        /** スマホに、映している端末の再生をスマホへ移してもらう。 */
        const val CMD_PLAY_HERE = "play_here"

        private const val SUBSCRIBE_RENEW_MS = 30_000L
        private const val PUBLISH_MS = 45_000L
        private const val PUBLISH_POLL_MS = 1_000L
        private const val POSITION_SEND_MS = 5_000L

        /** 受け渡しの答えを待つ時間。 */
        private const val ACK_TIMEOUT_MS = 30_000L

        /** Data Layer のメッセージの上限（約 100KB）より少し小さく。 */
        private const val MAX_MESSAGE_BYTES = 90_000
    }
}
