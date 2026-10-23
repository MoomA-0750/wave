package xyz.mooma0750.wave.core.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult

/**
 * 曲の歌詞を選ぶ。YouTube Music の時刻付き（ANDROID_MUSIC として匿名で読む。曲の照合が要らず、出典は Musixmatch・LyricFind）、
 * 無ければ LRCLIB の時刻付き（曲名・アーティスト・長さで照らし合わせる）、それも無ければ YouTube Music の時刻なし。
 * 見つかった・無かった結果は曲ごとに覚えておく。失敗は覚えない（開き直せば探し直す）。時刻付きを探すのに失敗して時刻なしを出したときも
 * 覚えない（一時の失敗で、時刻なしが残り続けないように）。
 */
class LyricsRepository(
    private val music: MusicRepository,
    private val lrclib: LrclibClient,
    private val timed: YouTubeTimedLyricsClient? = null,
) {
    sealed interface Result {
        data class Found(
            val lyrics: Lyrics,
        ) : Result

        /** どちらにも無かった。 */
        data object None : Result

        /** 通信の失敗などで分からない。 */
        data object Failed : Result
    }

    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Result>()

    suspend fun lyrics(song: Song): Result {
        lock.withLock { cache[song.videoId] }?.let { return it }
        var timedFailed = false
        var syncedResult: LrclibClient.Result = LrclibClient.Result.NotFound
        val result =
            coroutineScope {
                val tab = music.lyricsBrowseId(song.videoId)
                val browseId = (tab as? CallResult.Ok)?.value
                val fromYouTube = browseId?.let { id -> timed?.let { client -> async { client.timed(id) } } }
                val plain = async { if (browseId == null) (tab as? CallResult.Failed) ?: CallResult.Ok(null) else music.plainLyrics(browseId) }
                when (val found = fromYouTube?.await()) {
                    is YouTubeTimedLyricsClient.Result.Found -> return@coroutineScope Result.Found(found.lyrics)
                    YouTubeTimedLyricsClient.Result.Failed -> timedFailed = true
                    else -> {}
                }
                val artist = song.artists.firstOrNull()?.name
                syncedResult = if (artist == null) LrclibClient.Result.NotFound else lrclib.synced(song.title, artist, song.album?.title, song.durationSeconds)
                choose(syncedResult, plain.await())
            }
        val provisional = (result as? Result.Found)?.lyrics is Lyrics.Plain && (timedFailed || syncedResult == LrclibClient.Result.Failed)
        if (result != Result.Failed && !provisional) {
            lock.withLock {
                cache[song.videoId] = result
                while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        return result
    }

    internal companion object {
        const val CACHE_SIZE = 50
        const val LRCLIB_SOURCE = "LRCLIB"

        /** 時刻付きを優先する。片方が失敗していて、もう片方に無ければ「分からない」（無いとは決めない）。 */
        fun choose(
            synced: LrclibClient.Result,
            plain: CallResult<Lyrics.Plain?>,
        ): Result {
            if (synced is LrclibClient.Result.Found) return Result.Found(Lyrics.Synced(synced.lines, LRCLIB_SOURCE))
            return when (plain) {
                is CallResult.Ok -> plain.value?.let { Result.Found(it) } ?: if (synced == LrclibClient.Result.Failed) Result.Failed else Result.None
                is CallResult.Failed -> Result.Failed
            }
        }
    }
}
