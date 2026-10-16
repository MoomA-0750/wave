package xyz.mooma0750.wave.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 曲の音のバイトを端末に残す（Mac・Windows の AudioCache と同じ役目。Media3 の SimpleCache を使う）。
 * 曲送り・戻しでは残した分から鳴らし、続きは鳴らしながら取る。上限を超えたら、最後に使ったのが古い曲から消す。
 * 残すのは曲（wave://track）だけ。ライブ配信と RSS のエピソードは残さない。鍵は videoId だけで、署名付き URL は書かない。
 */
@OptIn(UnstableApi::class)
class AudioCache(
    context: Context,
) {
    private val cache = SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(context))

    /** 曲だけをキャッシュに通し、ほかはそのまま upstream で開く。 */
    fun factory(upstream: DataSource.Factory): DataSource.Factory {
        val cached = cacheFactory(upstream)
        return DataSource.Factory { RoutingDataSource(cached.createDataSource(), upstream.createDataSource()) }
    }

    private fun cacheFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> keyOf(spec.uri) ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** 曲の頭から途切れずに残っているバイト数。 */
    fun cachedBytes(uri: Uri): Long = keyOf(uri)?.let { cache.getCachedLength(it, 0, C.LENGTH_UNSET.toLong()).coerceAtLeast(0) } ?: 0

    /**
     * 曲の頭から bytes バイトまでを取って残す（次の曲を先に用意する）。呼んだスレッドで取り終えるまで待つ。
     * 戻り値の cancel で止められる。
     */
    fun warm(
        upstream: DataSource.Factory,
        uri: Uri,
        bytes: Long,
    ): Warm? {
        val key = keyOf(uri) ?: return null
        val spec =
            DataSpec
                .Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(bytes)
                .setKey(key)
                .build()
        return Warm(CacheWriter(cacheFactory(upstream).createDataSource(), spec, null, null))
    }

    class Warm(
        private val writer: CacheWriter,
    ) {
        fun run() = writer.cache()

        fun cancel() = writer.cancel()
    }

    /** ログアウトのとき（聴いた曲を残さない）。 */
    fun clear() {
        cache.keys.toList().forEach(cache::removeResource)
    }

    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024

        fun keyOf(uri: Uri): String? = TrackUri.videoIdOf(uri)?.let { "track:$it" }
    }
}

/** 開くたびに、曲ならキャッシュを通す側、ほかはそのままの側へ振り分ける。 */
@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (AudioCache.keyOf(dataSpec.uri) != null) cached else direct
        current = target
        return target.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
