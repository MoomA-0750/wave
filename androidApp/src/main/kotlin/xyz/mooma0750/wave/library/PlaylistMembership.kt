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
 * 読んだ結果（曲の ID の集合）は、プレイリストが変わるまで（playlistsVersion）使い回す。
 */
class PlaylistMembership(
    private val music: MusicRepository,
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
        val version: Int,
        val videoIds: Set<String>,
    )

    private val lock = Mutex()
    private val cache = mutableMapOf<String, Cached>()

    /** 1つのプレイリストを読む。読めたら曲の ID の集合、読めなければ null。 */
    internal suspend fun videoIds(
        playlistId: String,
        version: Int,
    ): Set<String>? {
        lock.withLock { cache[playlistId] }?.takeIf { it.version == version }?.let { return it.videoIds }
        return when (val scan = music.scanPlaylist(playlistId)) {
            is PlaylistScan.Ok -> {
                lock.withLock { cache[playlistId] = Cached(version, scan.videoIds) }
                scan.videoIds
            }
            is PlaylistScan.Incomplete -> {
                Log.i(WaveApplication.LOG_TAG, "playlist scan incomplete kind=${scan.kind}")
                null
            }
        }
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
            version: Int,
        ) {
            job?.cancel()
            mutableStatus.value = playlistIds.associateWith { Status.Checking }
            job =
                scope.launch {
                    val limit = Semaphore(CONCURRENCY)
                    playlistIds.forEach { playlistId ->
                        launch {
                            limit.withPermit {
                                val ids = membership.videoIds(playlistId, version)
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

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private companion object {
        /** 同時に読むプレイリストの数。 */
        const val CONCURRENCY = 3
    }
}
