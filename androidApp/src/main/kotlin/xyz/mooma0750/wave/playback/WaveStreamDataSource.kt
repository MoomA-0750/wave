package xyz.mooma0750.wave.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
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

/** 解決し直しても拒否された（または直前に拒否された曲）。同じ曲をすぐには再試行しない。 */
class StreamRejectedException(
    status: Int,
) : IOException("stream rejected status=$status")

/** 解決済みの URL を、期限の少し前まで使い回す。複数のローダースレッドから呼ばれる。 */
class ResolvedStreamCache(
    private val resolver: StreamResolver,
    private val nowMs: () -> Long,
) {
    // 挿入順を保つ（上限を超えたら古いものから捨てる）
    private val entries = LinkedHashMap<String, ResolvedStream>()
    private val rejectedAt = HashMap<String, Long>()

    /** clear のたびに進む。ログアウトの前に始まった解決の結果を、ログアウト後にキャッシュへ戻さないために使う。 */
    @get:Synchronized
    var epoch: Long = 0
        private set

    @Synchronized
    fun get(videoId: String): ResolvedStream? = entries[videoId]?.takeIf { resolver.isFresh(it, FRESH_MARGIN_MS) }

    @Synchronized
    fun put(
        stream: ResolvedStream,
        startedInEpoch: Long,
    ) {
        if (startedInEpoch != epoch) return
        entries.remove(stream.videoId)
        entries[stream.videoId] = stream
        // 上限を超えたら古いものから捨てる（署名付き URL をメモリに溜めない）
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun invalidate(videoId: String) {
        entries.remove(videoId)
    }

    /** 解決し直しても拒否された曲を覚える。しばらくは解決せずに失敗させる（/player の連打を防ぐ）。 */
    @Synchronized
    fun markRejected(videoId: String) {
        rejectedAt[videoId] = nowMs()
        entries.remove(videoId)
    }

    @Synchronized
    fun recentlyRejected(videoId: String): Boolean {
        val at = rejectedAt[videoId] ?: return false
        if (nowMs() - at < REJECTION_COOLDOWN_MS) return true
        rejectedAt.remove(videoId)
        return false
    }

    @Synchronized
    fun clear() {
        epoch++
        entries.clear()
        rejectedAt.clear()
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
        const val REJECTION_COOLDOWN_MS = 2 * 60 * 1000L
        const val MAX_ENTRIES = 64
    }
}

/**
 * wave://track/<videoId> を開くときに、ストリームを解決して匿名の OkHttp で取得する。
 * - 403 と 3xx は、URL を捨てて同じファイル（itag・長さ・更新日時）で1回だけ解決し直す。それでも拒否されたら
 *   StreamRejectedException（再試行しない）にして、しばらくその曲を解決しない
 * - 3xx の Location が googlevideo の /videoplayback なら、解決し直す前に1回だけ追う
 * - getUri と TransferListener に渡す DataSpec は wave:// のままにして、署名付き URL を Media3 の観測経路に出さない
 * - 上流の例外（署名付き URL を含む dataSpec を持つ）は原因として持たず、エラーコード（reason）だけを引き継ぐ
 */
@OptIn(UnstableApi::class)
class WaveStreamDataSource(
    private val resolver: StreamResolver,
    private val cache: ResolvedStreamCache,
    private val upstreamFactory: OkHttpDataSource.Factory,
) : DataSource {
    private var upstream: DataSource? = null
    private var openedSpec: DataSpec? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val videoId = TrackUri.videoIdOf(dataSpec.uri) ?: throw DataSourceException("unsupported uri scheme", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        openedSpec = dataSpec
        if (cache.recentlyRejected(videoId)) throw StreamRejectedException(403)
        val cached = cache.get(videoId)
        val first = cached ?: resolve(videoId, sameAs = null)
        return try {
            openUpstream(first, dataSpec)
        } catch (rejected: RejectedException) {
            val redirect = rejected.location?.takeIf(::isAllowedMediaUrl)
            if (redirect != null) {
                try {
                    return openUrl(redirect, dataSpec)
                } catch (_: RejectedException) {
                    // 追った先も拒否された。解決し直す
                }
            }
            Log.i(WaveApplication.LOG_TAG, "stream rejected status=${rejected.status} itag=${first.itag} cached=${cached != null}; resolving again")
            cache.invalidate(videoId)
            // 解決したばかりの URL が拒否されたときだけ、解読の設定が古いと考えて取り直す
            // （キャッシュの URL は、回線が変わった・期限切れなど解読と無関係な理由で拒否されうる）
            if (cached == null) runBlocking { resolver.onStreamRejected() }
            val second = resolve(videoId, sameAs = first)
            try {
                openUpstream(second, dataSpec)
            } catch (again: RejectedException) {
                cache.markRejected(videoId)
                // 解決したばかりの URL も拒否された。解読の設定が古い可能性があるので取り直す（初回がキャッシュ由来だった場合）
                if (cached != null) runBlocking { resolver.onStreamRejected() }
                throw StreamRejectedException(again.status)
            }
        }
    }

    private fun resolve(
        videoId: String,
        sameAs: ResolvedStream?,
    ): ResolvedStream {
        val startedInEpoch = cache.epoch
        return when (val result = runBlocking { resolver.resolve(videoId, sameAs) }) {
            is ResolveResult.Ok -> result.stream.also { cache.put(it, startedInEpoch) }
            is ResolveResult.Failed -> throw StreamResolveException(result.reason)
        }
    }

    private class RejectedException(
        val status: Int,
        val location: String?,
    ) : IOException("rejected status=$status")

    private fun openUpstream(
        stream: ResolvedStream,
        dataSpec: DataSpec,
    ): Long = openUrl(stream.revealUrl(), dataSpec)

    private fun openUrl(
        url: String,
        dataSpec: DataSpec,
    ): Long {
        // 解決時にも確かめているが、DataSource の組み替えで迂回されないよう、開く直前にも確かめる
        if (!isAllowedMediaUrl(url)) throw DataSourceException("stream url rejected by media url policy", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach { source.addTransferListener(RedactingTransferListener(it, dataSpec)) }
        upstream = source
        return try {
            source.open(dataSpec.buildUpon().setUri(Uri.parse(url)).build())
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            closeQuietly(source)
            // 404・410 は曲が無くなった。再試行しない種類として扱う
            if (error.responseCode == 404 || error.responseCode == 410) throw StreamRejectedException(error.responseCode)
            if (error.responseCode == 403 || error.responseCode in 300..399) {
                throw RejectedException(error.responseCode, error.headerFields["Location"]?.firstOrNull() ?: error.headerFields["location"]?.firstOrNull())
            }
            throw DataSourceException("stream http status=${error.responseCode}", null, error.reason)
        } catch (error: DataSourceException) {
            closeQuietly(source)
            throw redacted(error)
        } catch (error: IllegalArgumentException) {
            closeQuietly(source)
            throw DataSourceException("stream url could not be opened", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
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
    ): Int {
        val source = upstream ?: return C.RESULT_END_OF_INPUT
        return try {
            source.read(buffer, offset, length)
        } catch (error: DataSourceException) {
            throw redacted(error)
        }
    }

    override fun getUri(): Uri? = openedSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val source = upstream
        upstream = null
        openedSpec = null
        source?.close()
    }

    /** 上流の例外を、URL を持たずエラーコードだけを引き継ぐ例外に置き換える。 */
    private fun redacted(error: DataSourceException): DataSourceException = DataSourceException("stream io failure type=${error.javaClass.simpleName}", null, error.reason)

    /** 上流の転送の通知を、wave:// の DataSpec に差し替えて中継する。 */
    private class RedactingTransferListener(
        private val delegate: TransferListener,
        private val waveSpec: DataSpec,
    ) : TransferListener {
        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = delegate.onTransferInitializing(source, waveSpec, isNetwork)

        override fun onTransferStart(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = delegate.onTransferStart(source, waveSpec, isNetwork)

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) = delegate.onBytesTransferred(source, waveSpec, isNetwork, bytesTransferred)

        override fun onTransferEnd(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) = delegate.onTransferEnd(source, waveSpec, isNetwork)
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
