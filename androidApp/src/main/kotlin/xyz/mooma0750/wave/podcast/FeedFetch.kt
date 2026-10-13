package xyz.mooma0750.wave.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.mooma0750.wave.core.music.PodcastFeedClient

/** RSS のフィードを OkHttp で直接取る（Ktor の受け取りは debug の端末で数 MB に10秒かかった）。 */
object FeedFetch {
    /** 状態コードと本文。本文が大きすぎれば null。 */
    suspend fun fetch(
        client: OkHttpClient,
        url: String,
        userAgent: String,
    ): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                if (!response.isSuccessful) return@use response.code to null
                val source = response.body.source()
                // 上限＋1バイトまで読めたら大きすぎる
                source.request(PodcastFeedClient.MAX_FEED_BYTES + 1)
                if (source.buffer.size > PodcastFeedClient.MAX_FEED_BYTES) return@use response.code to null
                response.code to source.buffer.readUtf8()
            }
        }
}
