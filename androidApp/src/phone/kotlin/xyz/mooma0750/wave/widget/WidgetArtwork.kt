package xyz.mooma0750.wave.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.mooma0750.wave.WaveApplication

/**
 * ウィジェットに出す画像（ジャケット・プレイリストの表紙）を取る。認証情報を持たない音声用のクライアントで取り、
 * ウィジェットに渡せる大きさ（長辺 [MAX_PIXELS]）に縮めて、プロセスの中だけに少し持つ。
 */
object WidgetArtwork {
    private const val MAX_PIXELS = 400
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val cache = LruCache<String, Bitmap>(24)

    /** 持っていれば返す（取りには行かない）。 */
    fun cached(url: String?): Bitmap? = url?.let(cache::get)

    suspend fun load(
        client: OkHttpClient,
        url: String?,
    ): Bitmap? {
        if (url == null || !url.startsWith("https://")) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_BYTES) return@use null
                    decode(body.bytes())
                }
            }.onFailure { Log.i(WaveApplication.LOG_TAG, "widget artwork failed type=${it::class.simpleName}") }
                .getOrNull()
                ?.also { cache.put(url, it) }
        }
    }

    private val backdrops = LruCache<Bitmap, Bitmap>(4)

    /**
     * ウィジェットの地に敷く絵（iPhone のウィジェットと同じく、ぼかして暗くしたもの）。Glance はぼかせないので、
     * 小さく縮めてから広げ直してぼかし、黒を重ねる。
     */
    fun backdrop(source: Bitmap): Bitmap =
        backdrops.get(source) ?: run {
            var blurred = Bitmap.createScaledBitmap(source, BACKDROP_SMALL, BACKDROP_SMALL, true)
            // 一度に広げると升目が見えるので、2倍ずつ広げる
            while (blurred.width < BACKDROP_SIZE) blurred = Bitmap.createScaledBitmap(blurred, blurred.width * 2, blurred.height * 2, true)
            val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
            android.graphics.Canvas(out).drawColor(android.graphics.Color.argb(BACKDROP_DIM, 0, 0, 0))
            out.also { backdrops.put(source, it) }
        }

    private const val BACKDROP_SMALL = 12
    private const val BACKDROP_SIZE = 192

    /** 重ねる黒の濃さ（iPhone と同じ 35%）。 */
    private const val BACKDROP_DIM = 89

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_PIXELS) return decoded
        val scale = MAX_PIXELS.toFloat() / longest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    }
}

/** 画像を取って返す（取れるまでは持っている分か null）。 */
@androidx.compose.runtime.Composable
fun rememberWidgetArtwork(
    client: OkHttpClient,
    url: String?,
): Bitmap? {
    val state = androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(WidgetArtwork.cached(url)) }
    androidx.compose.runtime.LaunchedEffect(url) { if (state.value == null) state.value = WidgetArtwork.load(client, url) }
    return state.value
}

/** いくつかの画像をまとめて取って返す（URL ごと。取れた分だけ）。 */
@androidx.compose.runtime.Composable
fun rememberWidgetArtworks(
    client: OkHttpClient,
    urls: List<String>,
): Map<String, Bitmap> {
    val state = androidx.compose.runtime.remember(urls) { androidx.compose.runtime.mutableStateOf(urls.mapNotNull { url -> WidgetArtwork.cached(url)?.let { url to it } }.toMap()) }
    androidx.compose.runtime.LaunchedEffect(urls) {
        for (url in urls) {
            if (url in state.value) continue
            WidgetArtwork.load(client, url)?.let { state.value = state.value + (url to it) }
        }
    }
    return state.value
}
