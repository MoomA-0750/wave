package xyz.mooma0750.wave.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.net.isAllowedMediaUrl
import xyz.mooma0750.wave.core.stream.ResolveResult
import xyz.mooma0750.wave.core.stream.ResolvedStream
import xyz.mooma0750.wave.core.stream.StreamFailure
import xyz.mooma0750.wave.core.stream.StreamResolver
import java.io.IOException

/** 再生キューの項目の URI。署名付き URL の代わりに Media3 の中を流れる。 */
object TrackUri {
    private const val SCHEME = "wave"
    private const val HOST = "track"

    fun of(videoId: String): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(videoId).build()

    fun videoIdOf(uri: Uri): String? = uri.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment
}

/** 解決できなかった。例外文には失敗の種類だけを入れる（URL・原因の例外を持たない）。 */
class StreamResolveException(
    val reason: StreamFailure,
) : IOException("stream resolve failed reason=$reason") {
    /** 時間をおけば解決できるかもしれない失敗か。 */
    val transient: Boolean
        get() = reason == StreamFailure.Network || reason == StreamFailure.Http || reason == StreamFailure.PlayerConfigUnavailable
}

/** 解決済みの URL を、期限の少し前まで使い回す。複数のローダースレッドから呼ばれる。 */
class ResolvedStreamCache(
    private val resolver: StreamResolver,
) {
    private val entries = HashMap<String, ResolvedStream>()

    @Synchronized
    fun get(videoId: String): ResolvedStream? = entries[videoId]?.takeIf { resolver.isFresh(it, FRESH_MARGIN_MS) }

    @Synchronized
    fun put(stream: ResolvedStream) {
        entries[stream.videoId] = stream
    }

    @Synchronized
    fun invalidate(videoId: String) {
        entries.remove(videoId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** debug の確認用: キャッシュ中の URL を、取得すると 403 になるものに差し替える。 */
    @Synchronized
    fun corruptForDebug(videoId: String): Boolean {
        val stream = entries[videoId] ?: return false
        entries[videoId] = stream.corruptedForDebug()
        return true
    }

    private companion object {
        const val FRESH_MARGIN_MS = 5 * 60 * 1000L
    }
}

/**
 * wave://track/<videoId> を開くときに、ストリームを解決して匿名の OkHttp で取得する。
 * 403 と 3xx は、URL を捨てて同じ itag で1回だけ解決し直す（別の itag にすると、途中位置から別ファイルを継ぎ足すことになる）。
 * getUri は wave:// のままにして、署名付き URL を Media3 の観測経路（LoadEventInfo など）に出さない。
 */
@OptIn(UnstableApi::class)
class WaveStreamDataSource(
    private val resolver: StreamResolver,
    private val cache: ResolvedStreamCache,
    private val upstreamFactory: OkHttpDataSource.Factory,
) : DataSource {
    private var upstream: DataSource? = null
    private var openedUri: Uri? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val videoId = TrackUri.videoIdOf(dataSpec.uri) ?: throw IOException("unsupported uri scheme")
        openedUri = dataSpec.uri
        val first = cache.get(videoId) ?: resolve(videoId, sameAs = null)
        return try {
            openUpstream(first, dataSpec)
        } catch (rejected: RejectedException) {
            Log.i(WaveApplication.LOG_TAG, "stream rejected status=${rejected.status} itag=${first.itag}; resolving again")
            cache.invalidate(videoId)
            runBlocking { resolver.onStreamRejected() }
            val second = resolve(videoId, sameAs = first)
            try {
                openUpstream(second, dataSpec)
            } catch (again: RejectedException) {
                cache.invalidate(videoId)
                throw IOException("stream rejected again status=${again.status}")
            }
        }
    }

    private fun resolve(
        videoId: String,
        sameAs: ResolvedStream?,
    ): ResolvedStream =
        when (val result = runBlocking { resolver.resolve(videoId, sameAs) }) {
            is ResolveResult.Ok -> result.stream.also(cache::put)
            is ResolveResult.Failed -> throw StreamResolveException(result.reason)
        }

    private class RejectedException(
        val status: Int,
    ) : IOException("rejected status=$status")

    private fun openUpstream(
        stream: ResolvedStream,
        dataSpec: DataSpec,
    ): Long {
        val url = stream.revealUrl()
        // 解決時にも確かめているが、DataSource の組み替えで迂回されないよう、開く直前にも確かめる
        if (!isAllowedMediaUrl(url)) throw IOException("stream url rejected by media url policy")
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach(source::addTransferListener)
        upstream = source
        return try {
            source.open(dataSpec.buildUpon().setUri(Uri.parse(url)).build())
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            closeQuietly(source)
            if (error.responseCode == 403 || error.responseCode in 300..399) throw RejectedException(error.responseCode)
            // 例外の dataSpec には署名付き URL が入っているので、原因として持たない
            throw IOException("stream http status=${error.responseCode}")
        } catch (error: HttpDataSource.HttpDataSourceException) {
            closeQuietly(source)
            throw IOException("stream http failure type=${error.type}")
        }
    }

    private fun closeQuietly(source: DataSource) {
        runCatching { source.close() }
        if (upstream === source) upstream = null
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val source = upstream
        upstream = null
        openedUri = null
        source?.close()
    }

    class Factory(
        private val resolver: StreamResolver,
        private val cache: ResolvedStreamCache,
        client: OkHttpClient,
    ) : DataSource.Factory {
        private val upstreamFactory = OkHttpDataSource.Factory(client)

        override fun createDataSource(): DataSource = WaveStreamDataSource(resolver, cache, upstreamFactory)
    }
}
