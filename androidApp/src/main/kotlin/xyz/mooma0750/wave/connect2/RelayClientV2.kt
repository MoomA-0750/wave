package xyz.mooma0750.wave.connect2

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.connect.DeviceIdentity
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.RelayV2
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 中継 v2 への接続（Phase 6b）。プロセスで1本、使う側が参照を数える（古いソケットの callback を無視し、つなぎ直しの間隔を伸ばす）。
 * 受けたメッセージは deliver に渡す（呼ぶ側が ConnectClient の1本のスレッドへ移す）。ログは状態と種類だけ。
 */
class RelayClientV2(
    private val identity: DeviceIdentity,
    /** 例: wss://<中継のホスト>:<ポート>（local.properties の wave.relayV2Url） */
    private val baseUrl: String,
    /** 署名の対象に入れるホスト名（中継の設定と同じ値）。 */
    private val relayHost: String,
    private val scope: CoroutineScope,
    private val deliver: (RelayV2) -> Unit,
    private val onConnectionLost: () -> Unit,
) {
    enum class Status {
        Disconnected,
        Connecting,
        Connected,

        /** 名前を解決できない（Tailscale 未接続、または wave が Tailscale の対象外）。 */
        TailnetUnreachable,

        /** 中継に届くが、この端末は家に入っていない。 */
        NotMember,
        Failed,
    }

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val mutableStatus = MutableStateFlow(Status.Disconnected)
    val status: StateFlow<Status> = mutableStatus

    private var socket: WebSocket? = null
    private var users = 0
    private var reconnectJob: Job? = null
    private var failedAttempts = 0

    @Synchronized
    fun acquire() {
        users++
        if (users == 1 || (socket == null && status.value != Status.Connecting)) open()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) close()
    }

    /** つなぎ直す（家の記録を消した後に、新しい家の welcome を受け取る）。使っている人がいなければ何もしない。 */
    @Synchronized
    fun restart() {
        if (users == 0) return
        val closing = socket
        socket = null
        closing?.close(1000, null)
        if (closing != null) onConnectionLost()
        open()
    }

    fun send(message: RelayV2): Boolean = currentSocket()?.takeIf { status.value == Status.Connected }?.send(RelayV2.encode(message)) == true

    @Synchronized
    private fun currentSocket(): WebSocket? = socket

    @Synchronized
    private fun isCurrent(webSocket: WebSocket): Boolean = socket === webSocket

    /** 家に入っていない端末から、加入の第1手を送って Confirm を待つ（/v2/pair、認証なし）。 */
    suspend fun pair(
        token: String,
        hello: JsonElement,
    ): RelayV2? {
        val result = CompletableDeferred<RelayV2?>()
        val pairSocket =
            client.newWebSocket(
                Request.Builder().url("$baseUrl/v2/pair").build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(RelayV2.encode(RelayV2.PairHelloIn(token, hello)))
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        result.complete(RelayV2.decode(text))
                        webSocket.close(1000, null)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        result.complete(null)
                    }
                },
            )
        return try {
            withTimeoutOrNull(PAIR_TIMEOUT_MS) { result.await() }
        } finally {
            pairSocket.cancel()
        }
    }

    @Synchronized
    private fun open() {
        reconnectJob?.cancel()
        mutableStatus.value = Status.Connecting
        socket =
            client.newWebSocket(
                Request.Builder().url("$baseUrl/v2/connect").build(),
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (!isCurrent(webSocket)) return
                        when (val message = RelayV2.decode(text)) {
                            is RelayV2.Challenge -> {
                                val nonce = runCatching { Base64.getUrlDecoder().decode(message.nonce) }.getOrNull() ?: return
                                // 中継が名乗るホストではなく、設定のホストで署名する
                                val signature = identity.sign(RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(relayHost.encodeToByteArray(), nonce))
                                webSocket.send(RelayV2.encode(RelayV2.Auth(b64.encodeToString(identity.encodedPublicKey), b64.encodeToString(signature))))
                            }
                            is RelayV2.Welcome -> {
                                // close() と行き違ったら Connected にしない（レビュー p6b34 低-8）
                                synchronized(this@RelayClientV2) {
                                    if (socket !== webSocket) return
                                    failedAttempts = 0
                                    mutableStatus.value = Status.Connected
                                }
                                Log.i(WaveApplication.LOG_TAG, "relay-v2 connected")
                                deliver(message)
                            }
                            null -> Unit
                            else -> deliver(message)
                        }
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        if (isCurrent(webSocket) && status.value == Status.Connecting && reason == "auth") mutableStatus.value = Status.NotMember
                        webSocket.close(code, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        onDisconnected(webSocket, if (status.value == Status.NotMember || reason == "removed") Status.NotMember else Status.Disconnected)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "relay-v2 failure type=${t::class.simpleName}")
                        onDisconnected(webSocket, if (t is UnknownHostException) Status.TailnetUnreachable else Status.Failed)
                    }
                },
            )
    }

    @Synchronized
    private fun onDisconnected(
        webSocket: WebSocket,
        next: Status,
    ) {
        if (socket !== webSocket) return
        socket = null
        mutableStatus.value = next
        onConnectionLost()
        if (users > 0 && next != Status.NotMember) {
            val wait = (RECONNECT_DELAY_MS shl failedAttempts.coerceAtMost(4)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            failedAttempts++
            reconnectJob =
                scope.launch {
                    delay(wait)
                    synchronized(this@RelayClientV2) { if (users > 0 && socket == null) open() }
                }
        }
    }

    @Synchronized
    private fun close() {
        reconnectJob?.cancel()
        val closing = socket
        socket = null
        closing?.close(1000, null)
        if (closing != null) onConnectionLost()
        mutableStatus.value = Status.Disconnected
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 5_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val PAIR_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

/** 中継の参照を取って block を走らせ、必ず戻す（参照の数を崩さない）。 */
inline fun <T> RelayClientV2.holding(block: () -> T): T {
    acquire()
    try {
        return block()
    } finally {
        release()
    }
}
