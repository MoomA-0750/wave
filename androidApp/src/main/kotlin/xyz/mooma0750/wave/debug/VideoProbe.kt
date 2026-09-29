package xyz.mooma0750.wave.debug

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.stream.ResolveResult
import xyz.mooma0750.wave.core.stream.StreamKind

/**
 * 映像の表示の前提の実機確認（debug ビルドだけ）。曲の種類・対応する MV の有無・映像のストリームが解決でき取得できるか。
 * 出すのは種類・件数・itag・mimeType・status・真偽値だけ。URL は出さない。
 */
object VideoProbe {
    suspend fun run(
        container: AppContainer,
        videoId: String?,
    ) = withContext(Dispatchers.IO) {
        if (videoId == null) {
            Log.i(WaveApplication.LOG_TAG, "videoprobe no song")
            return@withContext
        }
        val info = container.music.musicVideoInfo(videoId)
        val video =
            when (info) {
                is CallResult.Failed -> {
                    Log.i(WaveApplication.LOG_TAG, "videoprobe info failed=${info.kind}")
                    null
                }
                is CallResult.Ok -> {
                    val v = info.value.video
                    Log.i(
                        WaveApplication.LOG_TAG,
                        "videoprobe type=${info.value.type} hasVideo=${v != null} self=${v?.videoId == videoId} segments=${v?.segments?.size} " +
                            "firstOffsetMs=${v?.segments?.firstOrNull()?.let { it.counterpartStartMs - it.primaryStartMs }}",
                    )
                    v
                }
            } ?: return@withContext
        when (val result = container.stream.resolver.resolve(video.videoId, kind = StreamKind.Video)) {
            is ResolveResult.Failed -> Log.i(WaveApplication.LOG_TAG, "videoprobe resolve failed=${result.reason}")
            is ResolveResult.Ok -> {
                val stream = result.stream
                val status =
                    try {
                        val request = Request.Builder().url(stream.revealUrl()).header("Range", "bytes=0-262143").get().build()
                        container.mediaHttpClient.newCall(request).execute().use { response -> "${response.code} bytes=${response.body.bytes().size}" }
                    } catch (_: Exception) {
                        "error"
                    }
                Log.i(WaveApplication.LOG_TAG, "videoprobe resolve itag=${stream.itag} mime=${stream.mimeType.substringBefore(';')} codec=${stream.mimeType.substringAfter("codecs=", "")} clen=${stream.contentLength} fetch=$status")
            }
        }
    }
}
