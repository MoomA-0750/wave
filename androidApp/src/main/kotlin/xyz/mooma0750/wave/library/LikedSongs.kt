package xyz.mooma0750.wave.library

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult

/** 高く評価した曲を、そのアーティストのものだけ選ぶ。ID が取れない行は名前で照らす。画面にも通信にも依らない。 */
object LikedByArtist {
    fun filter(
        songs: List<Song>,
        browseId: String?,
        name: String,
    ): List<Song> =
        songs.filter { song ->
            song.artists.any { artist ->
                if (browseId != null && artist.browseId != null) {
                    artist.browseId == browseId
                } else {
                    artist.name.equals(name, ignoreCase = true)
                }
            }
        }
}

/**
 * 高く評価した曲（プレイリスト LM）の読み込みと、アプリ内での使い回し。
 * アーティストのページを開くたびに読み直さないよう、評価が変わるまで（likedSongsVersion）持っておく。
 */
class LikedSongsCache(
    private val music: MusicRepository,
) {
    /** 読んだ結果。version は読んだときの likedSongsVersion。 */
    private class Cached(
        val version: Int,
        val songs: List<Song>,
        val complete: Boolean,
    )

    private val lock = Mutex()
    private var cached: Cached? = null

    data class Result(
        val songs: List<Song>,
        /** 上限まで読んでも続きがあった・途中で失敗した（全部は見ていない）。 */
        val complete: Boolean,
    )

    /** @param version 今の likedSongsVersion。変わっていれば読み直す。 */
    suspend fun songs(version: Int): Result {
        cached?.takeIf { it.version == version }?.let { return Result(it.songs, it.complete) }
        return lock.withLock {
            cached?.takeIf { it.version == version }?.let { return@withLock Result(it.songs, it.complete) }
            val songs = mutableListOf<Song>()
            var continuation: String? = null
            var complete = false
            var pages = 0
            while (pages < MAX_PAGES) {
                pages++
                when (val result = music.playlist(MusicRepository.LIKED_SONGS, continuation)) {
                    is CallResult.Ok -> {
                        songs += result.value.songs
                        continuation = result.value.continuation
                        if (continuation == null) {
                            complete = true
                            break
                        }
                    }
                    is CallResult.Failed -> break
                }
            }
            Log.i(WaveApplication.LOG_TAG, "liked songs loaded songs=${songs.size} pages=$pages complete=$complete")
            cached = Cached(version, songs, complete)
            Result(songs, complete)
        }
    }

    private companion object {
        /** 1ページ100曲。読み込む上限。 */
        const val MAX_PAGES = 20
    }
}
