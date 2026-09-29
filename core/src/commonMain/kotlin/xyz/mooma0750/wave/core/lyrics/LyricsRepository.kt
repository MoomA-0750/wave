package xyz.mooma0750.wave.core.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult

/**
 * 曲の歌詞を選ぶ。時刻付きは LRCLIB から、無ければ YouTube Music の時刻なしの歌詞（公式の出典）。
 * YouTube Music の時刻付きの歌詞は ANDROID_MUSIC を名乗らないと返らないので使わない（ADR-0001 の WEB_REMIX の1経路を守る）。
 * 見つかった・無かった結果は曲ごとに覚えておく。失敗は覚えない（開き直せば探し直す）。
 */
class LyricsRepository(
    private val music: MusicRepository,
    private val lrclib: LrclibClient,
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
        val result =
            coroutineScope {
                val synced =
                    async {
                        val artist = song.artists.firstOrNull()?.name ?: return@async LrclibClient.Result.NotFound
                        lrclib.synced(song.title, artist, song.album?.title, song.durationSeconds)
                    }
                val plain = async { music.lyrics(song.videoId) }
                choose(synced.await(), plain.await())
            }
        if (result != Result.Failed) {
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
