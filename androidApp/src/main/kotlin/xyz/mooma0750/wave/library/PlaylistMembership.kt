package xyz.mooma0750.wave.library

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistScan

/**
 * 「この曲がどのプレイリストに入っているか」。YouTube Music にそれを返す口が無いので、プレイリストを1つずつ読んで確かめる。
 * 読んだ結果（曲の ID の集合）はプレイリストごとに持ち、このアプリで足した・外したときはその1つだけ直す
 * （どれか1つを変えるたびに全部を読み直すと、追加の画面が毎回遅くなる）。ほかの端末での変更に追うため、古くなったら読み直す。
 */
class PlaylistMembership(
    private val music: MusicRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Status {
        /** 読んでいる途中。 */
        Checking,

        /** 入っている。 */
        In,

        /** 入っていない。 */
        NotIn,

        /** 最後まで読めなかったので分からない。 */
        Unknown,
    }

    private class Cached(
        val readAtMs: Long,
        val videoIds: Set<String>,
        /** 曲ごとの、プレイリスト内の項目の ID（外すのに使う）。このアプリで足した曲は分からないので持たない。 */
        val setVideoIds: Map<String, List<String>> = emptyMap(),
    )

    private val lock = Mutex()
    private val cache = mutableMapOf<String, Cached>()

    /** 再生中の曲と、それが入っている（と分かった）プレイリスト。 */
    data class NowPlaying(
        val videoId: String,
        val playlistIds: Set<String>,
    )

    private val mutableNowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** 再生中の曲がどのプレイリストに入っているか（[prefetch] で調べる。Spotify の、どこかに入っていれば✓の印に使う）。 */
    val nowPlaying: StateFlow<NowPlaying?> = mutableNowPlaying

    /**
     * 再生が始まった曲について、自分のプレイリストを先に読んでおく（追加の画面を開いたときに待たせない）。
     * 読んだ結果は覚えるので、次の曲からは古くなったプレイリストだけを読み直す。
     */
    suspend fun prefetch(
        playlistIds: List<String>,
        videoId: String,
    ) {
        // 同じ曲の読み直し（プレイリストを編集した後）では、分かっている印を消さない
        if (mutableNowPlaying.value?.videoId != videoId) mutableNowPlaying.value = NowPlaying(videoId, emptySet())
        val limit = Semaphore(CONCURRENCY)
        val startedMs = nowMs()
        kotlinx.coroutines.coroutineScope {
            playlistIds.forEach { playlistId ->
                launch {
                    val ids = limit.withPermit { videoIds(playlistId) }
                    if (ids != null && videoId in ids) mutableNowPlaying.update { it?.takeIf { now -> now.videoId == videoId }?.copy(playlistIds = it.playlistIds + playlistId) ?: it }
                }
            }
        }
        Log.i(WaveApplication.LOG_TAG, "membership prefetch playlists=${playlistIds.size} in=${mutableNowPlaying.value?.playlistIds?.size} ms=${nowMs() - startedMs}")
    }

    private fun noteNowPlaying(
        playlistId: String,
        videoId: String,
        isIn: Boolean,
    ) = mutableNowPlaying.update { now -> if (now?.videoId != videoId) now else now.copy(playlistIds = if (isIn) now.playlistIds + playlistId else now.playlistIds - playlistId) }

    /** 1つのプレイリストを読む。読めたら曲の ID の集合、読めなければ null。 */
    internal suspend fun videoIds(playlistId: String): Set<String>? = read(playlistId)?.videoIds

    private suspend fun read(
        playlistId: String,
        fresh: Boolean = false,
    ): Cached? {
        if (!fresh) lock.withLock { cache[playlistId] }?.takeIf { nowMs() - it.readAtMs < MAX_AGE_MS }?.let { return it }
        return when (val scan = music.scanPlaylist(playlistId)) {
            is PlaylistScan.Ok -> {
                val read = Cached(nowMs(), scan.videoIds, scan.setVideoIds)
                lock.withLock { cache[playlistId] = read }
                read
            }
            is PlaylistScan.Incomplete -> {
                Log.i(WaveApplication.LOG_TAG, "playlist scan incomplete kind=${scan.kind}")
                null
            }
        }
    }

    /** このアプリで曲を足した（読んだ集合に足す。読んでいなければ何もしない）。 */
    suspend fun noteAdded(
        playlistId: String,
        videoId: String,
    ) {
        lock.withLock { cache[playlistId]?.let { cache[playlistId] = Cached(it.readAtMs, it.videoIds + videoId, it.setVideoIds) } }
        noteNowPlaying(playlistId, videoId, isIn = true)
    }

    /**
     * 曲を外すのに使う、プレイリスト内の項目の ID。覚えていなければ（このアプリで足した曲など）読み直す。
     * 読めなければ null、入っていなければ空。
     */
    suspend fun setVideoIds(
        playlistId: String,
        videoId: String,
    ): List<String>? {
        read(playlistId)?.setVideoIds?.get(videoId)?.takeIf { it.isNotEmpty() }?.let { return it }
        val read = read(playlistId, fresh = true) ?: return null
        return read.setVideoIds[videoId].orEmpty()
    }

    /** このアプリで曲を外した（読んだ集合から除く）。 */
    suspend fun noteRemoved(
        playlistId: String,
        videoId: String,
    ) {
        lock.withLock { cache[playlistId]?.let { cache[playlistId] = Cached(it.readAtMs, it.videoIds - videoId, it.setVideoIds - videoId) } }
        noteNowPlaying(playlistId, videoId, isIn = false)
    }

    /**
     * プレイリストのページで、項目を1つ外した。同じ曲がほかにも入っていると分かっていれば、その項目だけを忘れて「入っている」のまま。
     * 曲が残っているか分からなければ（このアプリで足した曲など）次は読み直すが、再生中の曲の印は外したものとしてすぐ直す（iPhone と同じ）。
     */
    suspend fun noteRemovedItem(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) {
        val stillIn =
            lock.withLock {
                val cached = cache[playlistId] ?: return@withLock false
                val rest = cached.setVideoIds[videoId].orEmpty() - setVideoId
                if (rest.isNotEmpty()) {
                    cache[playlistId] = Cached(cached.readAtMs, cached.videoIds, cached.setVideoIds + (videoId to rest))
                    return@withLock true
                }
                if (cached.setVideoIds[videoId]?.contains(setVideoId) == true) {
                    cache[playlistId] = Cached(cached.readAtMs, cached.videoIds - videoId, cached.setVideoIds - videoId)
                } else {
                    cache.remove(playlistId)
                }
                false
            }
        if (!stillIn) noteNowPlaying(playlistId, videoId, isIn = false)
    }

    /** 結果が分からない変更をした（次は読み直す）。 */
    suspend fun invalidate(playlistId: String) {
        lock.withLock { cache.remove(playlistId) }
    }

    /** ログアウト。 */
    suspend fun clear() {
        lock.withLock { cache.clear() }
        mutableNowPlaying.value = null
    }

    /**
     * 画面1つ分の確認。プレイリストごとの状態を出しながら、順に読む（同時に読むのは CONCURRENCY 件まで）。
     * inner class にしない（Compose が画面の中で使う型の安定性の情報を作れず、実行時に落ちる）。
     */
    class Check(
        private val membership: PlaylistMembership,
        private val scope: CoroutineScope,
    ) {
        private val mutableStatus = MutableStateFlow<Map<String, Status>>(emptyMap())
        val status: StateFlow<Map<String, Status>> = mutableStatus
        private var job: Job? = null

        fun start(
            playlistIds: List<String>,
            videoId: String,
        ) {
            job?.cancel()
            mutableStatus.value = playlistIds.associateWith { Status.Checking }
            job =
                scope.launch {
                    val limit = Semaphore(CONCURRENCY)
                    playlistIds.forEach { playlistId ->
                        launch {
                            limit.withPermit {
                                val ids = membership.videoIds(playlistId)
                                val status =
                                    when {
                                        ids == null -> Status.Unknown
                                        videoId in ids -> Status.In
                                        else -> Status.NotIn
                                    }
                                mutableStatus.update { it + (playlistId to status) }
                            }
                        }
                    }
                }
        }

        /** 画面の中で足した（読み直さずに「入っている」にする）。 */
        fun markIn(playlistId: String) = mutableStatus.update { it + (playlistId to Status.In) }

        /** 画面の中で外した。 */
        fun markNotIn(playlistId: String) = mutableStatus.update { it + (playlistId to Status.NotIn) }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private companion object {
        /** 同時に読むプレイリストの数。 */
        const val CONCURRENCY = 6

        /** これより古い結果は読み直す（ほかの端末での変更を拾う。再生のたびに先読みするので、短すぎると毎回全部を読み直す）。 */
        const val MAX_AGE_MS = 30 * 60 * 1000L
    }
}

/** 再生中の曲がどのプレイリストに入っているかの覚え（再生画面の評価のボタンが✓を付けるのに使う）。 */
val LocalPlaylistMembership = androidx.compose.runtime.staticCompositionLocalOf<PlaylistMembership?> { null }
