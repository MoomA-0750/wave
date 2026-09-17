package xyz.mooma0750.wave.debug

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.stream.ResolveResult

/**
 * ADR-0001 の前提の実機確認（debug ビルドだけ）。固定の曲の itag 141 と 774 を解決し、音声用の匿名クライアントで全体を取得する。
 * 出すのは itag・status・バイト数・時間・真偽値だけ。URL は出さない。
 */
object StreamCheck {
    private const val VIDEO_ID = "dQw4w9WgXcQ"

    suspend fun run(container: AppContainer): List<String> =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            for (itag in listOf(141, 774)) {
                val resolveStart = SystemClock.elapsedRealtime()
                val result = container.stream.resolver.resolve(VIDEO_ID, requiredItag = itag)
                val resolveMs = SystemClock.elapsedRealtime() - resolveStart
                val line =
                    when (result) {
                        is ResolveResult.Failed -> "itag=$itag resolve=${result.reason} resolveMs=$resolveMs"
                        is ResolveResult.Ok -> {
                            val stream = result.stream
                            val fetchStart = SystemClock.elapsedRealtime()
                            val request = Request.Builder().url(stream.revealUrl()).get().build()
                            val (status, bytes) =
                                try {
                                    container.mediaHttpClient.newCall(request).execute().use { response ->
                                        var total = 0L
                                        response.body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                val read = input.read(buffer)
                                                if (read < 0) break
                                                total += read
                                            }
                                        }
                                        response.code to total
                                    }
                                } catch (error: Exception) {
                                    -1 to -1L
                                }
                            val fetchMs = SystemClock.elapsedRealtime() - fetchStart
                            "itag=$itag resolve=Ok resolveMs=$resolveMs status=$status bytes=$bytes clen=${stream.contentLength} " +
                                "match=${bytes == stream.contentLength} over1MB=${bytes > 1_000_000} fetchMs=$fetchMs"
                        }
                    }
                Log.i(WaveApplication.LOG_TAG, "streamcheck $line")
                lines += line
            }
            val faraday = container.stream.faradayStatus()
            val faradayLine = "faraday tableLoaded=${faraday.tableLoaded} playerInTable=${faraday.playerInTable}"
            Log.i(WaveApplication.LOG_TAG, "streamcheck $faradayLine")
            lines += faradayLine
            lines
        }
}
