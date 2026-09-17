package xyz.mooma0750.wave.connect

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.handoff.Envelope
import xyz.mooma0750.wave.handoff.RelayMessage
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 中継への接続（Phase 6、ADR-0008）。プロセスで1本。
 * 認証ヘッダを持つクライアント（AuthGuardEngine）とは別の OkHttp を使う（ADR-0003 の許可ルートは変えない）。
 * ログは状態と種類だけ（端末 ID・本文は出さない）。
 */
class RelayClient(
    private val identity: DeviceIdentity,
    /** 例: wss://<中継のホスト>:<ポート>（local.properties の wave.relayUrl） */
    private val baseUrl: String,
    /** 署名の対象に入れるホスト名（中継の設定と同じ値）。 */
    private val relayHost: String,
    private val scope: CoroutineScope,
) {
    enum class Status {
        Disconnected,
        Connecting,
        Connected,

        /** 名前を解決できない（Tailscale 未接続、または wave が Tailscale の対象外）。 */
        TailnetUnreachable,

        /** 中継に届くが、この端末は登録されていない（ペアリングが必要）。 */
        NotRegistered,
        Failed,
    }

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val mutableStatus = MutableStateFlow(Status.Disconnected)
    val status: StateFlow<Status> = mutableStatus

    private val mutableIncoming = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 16)

    /** 認証の後に中継から届いたメッセージ（封筒・ペアリングの第1手・エラーなど）。 */
    val incoming: SharedFlow<RelayMessage> = mutableIncoming

    private var socket: WebSocket? = null
    private var sessionNonce: ByteArray? = null
    private var users = 0
    private var reconnectJob: Job? = null
    private var failedAttempts = 0

    /** 使い始める（参照を数え、0 から 1 になったら接続する）。 */
    @Synchronized
    fun acquire() {
        users++
        // 他の利用者がつないでいても、未登録・失敗のまま待っているなら、すぐつなぎ直す（ペアリングの直後に登録が済んでいる）
        if (users == 1 || (socket == null && status.value != Status.Connecting)) open()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) close()
    }

    fun send(message: RelayMessage): Boolean = socket?.takeIf { status.value == Status.Connected }?.send(RelayMessage.encode(message)) == true

    fun sendEnvelope(envelope: Envelope): Boolean = send(RelayMessage.Send(envelope))

    /** 登録済みの端末として、別の端末の公開鍵を中継に登録する（ペアリングの承認の後）。 */
    fun register(peerPublicKey: ByteArray): Boolean {
        val nonce = sessionNonce ?: return false
        val encoded = b64.encodeToString(peerPublicKey)
        val signature = identity.sign(RelayMessage.REGISTER_LABEL, WaveCrypto.lengthPrefixed(relayHost.encodeToByteArray(), nonce, encoded.encodeToByteArray()))
        return send(RelayMessage.Register(encoded, b64.encodeToString(signature)))
    }

    fun revoke(deviceId: String): Boolean {
        val nonce = sessionNonce ?: return false
        val signature = identity.sign(RelayMessage.REVOKE_LABEL, WaveCrypto.lengthPrefixed(relayHost.encodeToByteArray(), nonce, deviceId.encodeToByteArray()))
        return send(RelayMessage.Revoke(deviceId, b64.encodeToString(signature)))
    }

    /** 未登録の端末（B）から、ペアリングの第1手を送って A の Confirm を待つ（/pair、認証なし）。 */
    suspend fun pair(
        token: String,
        hello: kotlinx.serialization.json.JsonElement,
    ): RelayMessage? {
        val result = CompletableDeferred<RelayMessage?>()
        val pairSocket =
            client.newWebSocket(
                Request.Builder().url("$baseUrl/pair").build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(RelayMessage.encode(RelayMessage.PairHelloIn(token, hello)))
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        result.complete(RelayMessage.decode(text))
                        webSocket.close(1000, null)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "relay pair failure type=${t::class.simpleName}")
                        result.complete(null)
                    }
                },
            )
        return try {
            withTimeoutOrNull(PAIR_TIMEOUT_MS) { result.await() }
        } finally {
            // 取り消されても /pair のソケットを残さない（中継の未認証の接続の枠を使い続けない）
            pairSocket.cancel()
        }
    }

    @Synchronized
    private fun open() {
        reconnectJob?.cancel()
        mutableStatus.value = Status.Connecting
        socket =
            client.newWebSocket(
                Request.Builder().url("$baseUrl/connect").build(),
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        // 閉じた前の接続から遅れて届いたものは、今の接続の nonce・状態を書き換えない
                        if (!isCurrent(webSocket)) return
                        when (val message = RelayMessage.decode(text)) {
                            is RelayMessage.Challenge -> {
                                val nonce = runCatching { Base64.getUrlDecoder().decode(message.nonce) }.getOrNull() ?: return
                                // 中継が名乗るホストではなく、設定のホストで署名する（別の中継に署名を流用させない）
                                val signature = identity.sign(RelayMessage.AUTH_LABEL, WaveCrypto.lengthPrefixed(relayHost.encodeToByteArray(), nonce))
                                sessionNonce = nonce
                                webSocket.send(RelayMessage.encode(RelayMessage.Auth(b64.encodeToString(identity.encodedPublicKey), b64.encodeToString(signature))))
                            }
                            is RelayMessage.Welcome -> {
                                synchronized(this@RelayClient) { failedAttempts = 0 }
                                mutableStatus.value = Status.Connected
                                Log.i(WaveApplication.LOG_TAG, "relay connected")
                            }
                            null -> Unit
                            else -> mutableIncoming.tryEmit(message)
                        }
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        // 認証の前に閉じられたら、登録されていない
                        if (isCurrent(webSocket) && status.value == Status.Connecting && reason == "auth") mutableStatus.value = Status.NotRegistered
                        webSocket.close(code, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        onDisconnected(webSocket, if (status.value == Status.NotRegistered) Status.NotRegistered else Status.Disconnected)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "relay failure type=${t::class.simpleName}")
                        onDisconnected(webSocket, if (t is UnknownHostException) Status.TailnetUnreachable else Status.Failed)
                    }
                },
            )
    }

    @Synchronized
    private fun isCurrent(webSocket: WebSocket): Boolean = socket === webSocket

    @Synchronized
    private fun onDisconnected(
        webSocket: WebSocket,
        next: Status,
    ) {
        if (socket !== webSocket) return
        socket = null
        sessionNonce = null
        mutableStatus.value = next
        if (users > 0 && next != Status.NotRegistered) {
            // Tailscale が切れたまま再生を続けても、名前解決を5秒ごとに試し続けない
            val wait = (RECONNECT_DELAY_MS shl failedAttempts.coerceAtMost(4)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            failedAttempts++
            reconnectJob =
                scope.launch {
                    delay(wait)
                    synchronized(this@RelayClient) { if (users > 0 && socket == null) open() }
                }
        }
    }

    @Synchronized
    private fun close() {
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        sessionNonce = null
        mutableStatus.value = Status.Disconnected
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 5_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val PAIR_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
