package xyz.mooma0750.wave.nowplaying

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import xyz.mooma0750.wave.core.music.LiveClient
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.MusicVideo
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.stream.StreamKind
import xyz.mooma0750.wave.core.stream.StreamResolver
import xyz.mooma0750.wave.playback.LiveStreamCache
import xyz.mooma0750.wave.playback.ResolvedStreamCache
import xyz.mooma0750.wave.playback.TrackUri
import xyz.mooma0750.wave.playback.WaveStreamDataSource

/**
 * 曲に重ねる映像。音は再生サービスのプレイヤーが鳴らし続け、映像は画面側の音の無いプレイヤーで出す（VideoSync で位置を合わせる）。
 * 映像の読み込みに失敗しても、音の再生には関わらない。
 */
class MusicVideos(
    private val music: MusicRepository,
    private val resolver: StreamResolver,
    private val cache: ResolvedStreamCache,
    private val http: OkHttpClient,
    /** ほかの端末で鳴っているライブの映像を、この端末で別に読み込むため。 */
    private val live: LiveClient,
    private val liveCache: LiveStreamCache,
) {
    sealed interface Result {
        data class Found(
            val video: MusicVideo,
        ) : Result

        /** 音源で、対応する MV が無い。 */
        data object None : Result

        data object Failed : Result
    }

    private val lock = Mutex()

    /** 曲ごとの結果（ログインの世代ごと）。失敗は覚えない。 */
    private val found = HashMap<Pair<Long, String>, Result>()

    suspend fun videoFor(videoId: String): Result {
        val key = music.currentLoginGeneration() to videoId
        lock.withLock { found[key] }?.let { return it }
        val result =
            when (val info = music.musicVideoInfo(videoId)) {
                is CallResult.Failed -> return Result.Failed
                is CallResult.Ok -> info.value.video?.let { Result.Found(it) } ?: Result.None
            }
        lock.withLock { found[key] = result }
        return result
    }

    /** 「動画」を選んで維持しているか（再生画面が決める）。ON のときだけ、次の曲の映像の URL を先に解決する。 */
    @Volatile
    var keepVideo: Boolean = false

    /**
     * 次の曲の MV の有無を先に引き（videoFor が覚える）、動画を維持しているなら映像の URL も解決して残す
     * （曲が替わってから読み込むと、最初の数秒が映らないため）。映像の先頭のバイトまでは取らない（URL の解決が待ちの大半）。
     */
    suspend fun prefetch(videoId: String) {
        val found = (videoFor(videoId) as? Result.Found)?.video ?: return
        if (!keepVideo) return
        val epoch = music.currentLoginGeneration()
        if (cache.get(found.videoId, epoch) != null) return
        val startedInEpoch = cache.epoch
        val result = resolver.resolve(found.videoId, expectedGeneration = epoch, kind = StreamKind.Video)
        if (result is xyz.mooma0750.wave.core.stream.ResolveResult.Ok) cache.put(result.stream, startedInEpoch)
    }

    /** 映像だけを出すプレイヤー。音声の再生とは別に、音声のフォーカスを取らず、音も出さない。 */
    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context): ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(WaveStreamDataSource.Factory(resolver, cache, http, StreamKind.Video).withLive(live, liveCache)))
            .build()
            .apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }

    /** 解決は今のログインの世代でだけ行う（ADR-0007）。 */
    fun mediaItem(videoId: String): MediaItem = MediaItem.fromUri(TrackUri.of(videoId, music.currentLoginGeneration()))

    /** ライブ配信を映像を見るための画質で（HLS）。 */
    fun liveMediaItem(videoId: String): MediaItem =
        MediaItem
            .Builder()
            .setUri(TrackUri.withLiveVideo(TrackUri.of(videoId, music.currentLoginGeneration(), live = true), video = true))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
}
