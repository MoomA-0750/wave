package xyz.mooma0750.wave.debug

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.mooma0750.wave.WaveApplication
import java.util.concurrent.TimeUnit

/**
 * debug だけ: Phase 6 の計測（P6-S）。認証ヘッダを持つクライアントとは別の OkHttp で、計測用の echo に WSS でつなぐ。
 * ログは接続・往復の時間・遅れて届いた時間だけ。
 */
object P6sEcho {
    const val DEFAULT_URL = "wss://relay.invalid:10443/ws"

    private val client =
        OkHttpClient
            .Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    private var socket: WebSocket? = null
    private var sentAt = 0L
    private var delayRequestedAt = 0L

    fun start(
        url: String,
        delaysSeconds: List<Int>,
    ) {
        stop()
        val openedAt = SystemClock.elapsedRealtime()
        socket =
            client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "p6s open ms=${SystemClock.elapsedRealtime() - openedAt} protocol=${response.protocol}")
                        sentAt = SystemClock.elapsedRealtime()
                        webSocket.send("ping-probe")
                        delayRequestedAt = SystemClock.elapsedRealtime()
                        delaysSeconds.filter { it > 0 }.forEach { webSocket.send("delay:$it") }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (text.startsWith("delayed:")) {
                            Log.i(WaveApplication.LOG_TAG, "p6s delayed arrived afterS=${(now - delayRequestedAt) / 1000} requested=${text.removePrefix("delayed:")}")
                        } else {
                            Log.i(WaveApplication.LOG_TAG, "p6s echo rttMs=${now - sentAt} ok=${text == "ping-probe"}")
                        }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "p6s closed code=$code")
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.i(WaveApplication.LOG_TAG, "p6s failure type=${t::class.simpleName} status=${response?.code} afterS=${(SystemClock.elapsedRealtime() - openedAt) / 1000}")
                    }
                },
            )
    }

    fun stop() {
        socket?.close(1000, null)
        socket = null
    }
}
