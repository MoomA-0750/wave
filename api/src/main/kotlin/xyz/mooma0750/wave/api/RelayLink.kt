package xyz.mooma0750.wave.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.RelayV2
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 中継 v2 への接続（Android の RelayClientV2 と同じ手順）。つながっている間は切れたらつなぎ直す。
 * 受けたメッセージは deliver に渡す（呼ぶ側が ConnectClient の1本のスレッドへ移す）。ログは状態と種類だけ。
 */
class RelayLink(
    private val keys: ApiKeys,
    /** 例: ws://wave-relay-v2:8791 */
    private val baseUrl: String,
    /** 署名の対象に入れるホスト名（中継の WAVE_RELAY_HOST と同じ値）。 */
    private val relayHost: String,
    private val scope: CoroutineScope,
    private val deliver: (RelayV2) -> Unit,
    private val onStatus: (Status) -> Unit,
) {
    enum class Status { Disconnected, Connecting, Connected, NotMember, Removed, Failed }

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    @Volatile
    var status: Status = Status.Disconnected
        private set
    private var socket: WebSocket? = null
    private var wanted = false
    private var reconnectJob: Job? = null
    private var failedAttempts = 0

    @Synchronized
    fun start() {
        wanted = true
        if (socket == null) open()
    }

    @Synchronized
    fun stop() {
        wanted = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        setStatus(Status.Disconnected)
    }

    /** つなぎ直す（家に加えてもらった後、鎖を取り直す）。 */
    @Synchronized
    fun restart() {
        val closing = socket
        socket = null
        closing?.close(1000, null)
        failedAttempts = 0
        if (wanted) open()
    }

    fun send(message: RelayV2): Boolean = currentSocket()?.takeIf { status == Status.Connected }?.send(RelayV2.encode(message)) == true

    @Synchronized
    private fun currentSocket(): WebSocket? = socket

    @Synchronized
    private fun isCurrent(webSocket: WebSocket) = socket === webSocket

    private fun setStatus(next: Status) {
        status = next
        onStatus(next)
    }

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
        setStatus(Status.Connecting)
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
                                val signature = keys.sign(RelayV2.AUTH_LABEL, WaveCrypto.lengthPrefixed(relayHost.encodeToByteArray(), nonce))
                                webSocket.send(RelayV2.encode(RelayV2.Auth(b64.encodeToString(keys.identityPub), b64.encodeToString(signature))))
                            }
                            is RelayV2.Welcome -> {
                                synchronized(this@RelayLink) {
                                    if (socket !== webSocket) return
                                    failedAttempts = 0
                                }
                                setStatus(Status.Connected)
                                log("relay connected")
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
                        if (isCurrent(webSocket)) {
                            when {
                                reason == "removed" -> setStatus(Status.Removed)
                                status == Status.Connecting && reason == "auth" -> setStatus(Status.NotMember)
                            }
                        }
                        webSocket.close(code, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        onDisconnected(webSocket, if (status == Status.NotMember || status == Status.Removed) status else Status.Disconnected)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        log("relay failure type=${t::class.simpleName}")
                        onDisconnected(webSocket, Status.Failed)
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
        setStatus(next)
        deliverLost()
        if (!wanted || next == Status.Removed) return
        // 家に入っていない（加えてもらう前）も、間を空けてつなぎ直す（加入を待っている間に加えてもらえる）
        val wait = (RECONNECT_DELAY_MS shl failedAttempts.coerceAtMost(4)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        failedAttempts++
        reconnectJob =
            scope.launch {
                delay(wait)
                synchronized(this@RelayLink) { if (wanted && socket == null) open() }
            }
    }

    /** 切れたことを ConnectClient に伝える（中継の Presence が来ないので、こちらで知らせる）。 */
    var onLost: () -> Unit = {}

    private fun deliverLost() = onLost()

    private companion object {
        const val RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val PAIR_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

internal fun log(message: String) = println("wave-api $message")
