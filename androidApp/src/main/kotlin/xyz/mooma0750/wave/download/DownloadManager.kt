package xyz.mooma0750.wave.download

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.download.CollectionKind
import xyz.mooma0750.wave.core.download.DownloadManifest
import xyz.mooma0750.wave.core.download.DownloadPlan
import xyz.mooma0750.wave.core.download.DownloadedCollection
import xyz.mooma0750.wave.core.download.SmartCategory
import xyz.mooma0750.wave.core.download.SmartDownloads
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.queue.QueueSession
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.stream.StreamFailure
import xyz.mooma0750.wave.library.LikedSongsCache
import xyz.mooma0750.wave.playback.ResolvedStreamCache
import xyz.mooma0750.wave.playback.StreamRejectedException
import xyz.mooma0750.wave.playback.StreamResolveException
import xyz.mooma0750.wave.playback.TrackUri
import xyz.mooma0750.wave.settings.AppSettings

/** 取っている途中の曲。 */
data class DownloadProgress(
    val videoId: String,
    val title: String?,
    val bytes: Long,
    val total: Long?,
)

/** ダウンロードの画面・ボタンに出す今の状態。 */
data class DownloadsState(
    val manifest: DownloadManifest = DownloadManifest(),
    /** 取り終えた曲。 */
    val complete: Set<String> = emptySet(),
    val active: DownloadProgress? = null,
    /** 取るものがあるのに、合う通信が無くて待っている。 */
    val waitingForNetwork: Boolean = false,
    /** 取れなかった曲（再生できない・拒否された）。次に起動するか、選び直すまで取り直さない。 */
    val failed: Set<String> = emptySet(),
    val bytesUsed: Long = 0,
    /** スマートダウンロードを選び直している途中。 */
    val refreshingSmart: Boolean = false,
) {
    /** まだ取り終えていない曲の数（取れなかった曲を除く）。 */
    val pending: Int get() = DownloadPlan.missing(manifest, complete).count { it !in failed }

    fun isDownloaded(videoId: String): Boolean = videoId in complete

    fun collectionState(
        kind: CollectionKind,
        id: String,
    ): CollectionDownload {
        val collection = manifest.collection(kind, id) ?: return CollectionDownload.None
        val (done, total) = DownloadPlan.progress(collection, complete)
        return if (done >= total) CollectionDownload.Done(total) else CollectionDownload.InProgress(done, total)
    }
}

/** プレイリスト・アルバム・高く評価した曲のページの頭のボタンの3つの見た目（ADR-0016 の 15）。 */
sealed interface CollectionDownload {
    data object None : CollectionDownload

    data class InProgress(
        val done: Int,
        val total: Int,
    ) : CollectionDownload

    data class Done(
        val total: Int,
    ) : CollectionDownload
}

/**
 * ダウンロード（ADR-0016）。何を取るかの目録を持ち、取るのは1つずつ待ち行列で、アプリが動いている間に行う。
 * - 音は再生と同じ道（WaveStreamDataSource）で、範囲を区切って取る。403 なら URL を調べ直して取り直す（再生と同じ）
 * - 使う通信は設定に従う。合わない通信の間は待ち、合う通信になったら続ける
 * - プレイリスト・アルバム・高く評価した曲は丸ごとを覚え、起動したとき・ページを開いたとき・1日1回読み直す
 * - ログアウトすると全部消す
 */
@OptIn(UnstableApi::class)
class DownloadManager(
    private val scope: CoroutineScope,
    val store: DownloadStore,
    val playCounts: PlayCountStore,
    val network: NetworkMonitor,
    private val settings: AppSettings,
    private val music: MusicRepository,
    private val session: WaveSession,
    private val authManager: AuthManager,
    private val streamCache: ResolvedStreamCache,
    /** 曲の音を開くもの（WaveStreamDataSource。速く鳴らすための置き場は通さない）。 */
    private val sourceFactory: DataSource.Factory,
    private val likedSongs: LikedSongsCache,
    private val likedVersion: () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var manifest: DownloadManifest = DownloadManifest()
    private val mutableState = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = mutableState.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var anyNetwork = false
    private var started = false

    /** 時計では呼ばない（時計には取らない、ADR-0016 の 19）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                manifest = store.readManifest()
                // 持ち主のいないファイル（目録を書く前に終わった・消した後に残った）を消す
                DownloadPlan.orphans(manifest, store.completeIds()).forEach(store::delete)
                publishLocked()
            }
            worker()
        }
        // 通信と設定が変わったら、待っている取得を見直す
        scope.launch {
            combine(network.state, settings.downloadOnAnyNetwork) { net, any -> net to any }.collect { (_, any) ->
                anyNetwork = any
                wake.trySend(Unit)
            }
        }
        // ログアウトしたら全部消す。ログインが反映されたら、丸ごとのものとスマートダウンロードを合わせる（起動したとき）
        scope.launch {
            authManager.state.collect { auth ->
                when (auth) {
                    AuthState.LoggedOut, is AuthState.Unreadable -> clearAccount()
                    is AuthState.LoggedIn ->
                        scope.launch {
                            session.appliedObtainedAtEpochMs.first { it == auth.obtainedAtEpochMs }
                            maintain()
                        }
                    else -> Unit
                }
            }
        }
        // スマートダウンロードを切ったら外す。入れたら選ぶ
        scope.launch {
            var previous: Boolean? = null
            settings.smartDownloads.collect { enabled ->
                val before = previous
                previous = enabled
                if (before == null) return@collect
                if (enabled) refreshSmart(force = true) else update { it.withoutSmart() }
            }
        }
        scope.launch {
            var previous: Int? = null
            settings.smartDownloadCount.collect { count ->
                val before = previous
                previous = count
                if (before != null && before != count && settings.smartDownloads.first()) refreshSmart(force = true)
            }
        }
    }

    // ---- 目録を変える操作 ----

    fun downloadSong(
        song: Song,
        episode: Boolean = Feed.isExternal(song.videoId),
    ) {
        scope.launch { update { it.withSingle(song, nowMs(), episode) } }
    }

    /** 曲・エピソードの「ダウンロードを削除」。丸ごとのものの中の曲は、丸ごとのものを消すまで残る。 */
    fun removeSong(videoId: String) {
        scope.launch { update { it.withoutSong(videoId) } }
    }

    /** 丸ごと取る。songs は画面で読んだぶん（すぐ取り始める）。裏で全部を読み直して合わせる。 */
    fun downloadCollection(
        kind: CollectionKind,
        id: String,
        title: String,
        thumbnailUrl: String?,
        songs: List<Song>,
    ) {
        scope.launch {
            update { it.withCollection(DownloadedCollection(kind, id, title, thumbnailUrl, nowMs(), songs)) }
            syncCollection(kind, id)
        }
    }

    fun removeCollection(
        kind: CollectionKind,
        id: String,
    ) {
        scope.launch { update { it.withoutCollection(kind, id) } }
    }

    /** ページを開いたとき: 取っているものなら、一覧を読み直して合わせる。 */
    fun syncIfDownloaded(
        kind: CollectionKind,
        id: String,
    ) {
        if (manifest.collection(kind, id) == null) return
        scope.launch { syncCollection(kind, id) }
    }

    fun offlineCollection(
        kind: CollectionKind,
        id: String,
    ): DownloadedCollection? = manifest.collection(kind, id)

    /** 「ダウンロードをすべて削除」・ログアウト。目録もファイルも消す（スマートダウンロードの入り切りは残す）。 */
    suspend fun deleteAll() {
        lock.withLock {
            if (manifest.isEmpty && withContext(Dispatchers.IO) { store.dir.list().isNullOrEmpty() }) return
            manifest = DownloadManifest()
            withContext(Dispatchers.IO) { store.clear() }
            mutableState.update { it.copy(failed = emptySet()) }
            publishLocked()
        }
        Log.i(WaveApplication.LOG_TAG, "downloads cleared")
    }

    /** ログアウト: ダウンロードと、この端末の再生回数を消す。 */
    suspend fun clearAccount() {
        deleteAll()
        playCounts.clear()
    }

    // ---- 合わせる ----

    /** 起動したとき・1日1回: 丸ごとのものを読み直し、スマートダウンロードを選び直す（日が変わっていれば）。 */
    suspend fun maintain() {
        if (!network.online) return
        manifest.collections.map { it.kind to it.id }.forEach { (kind, id) -> syncCollection(kind, id) }
        refreshSmart(force = false)
    }

    /** JobScheduler から: 合わせてから、取り残しを取り終えるか通信が合わなくなるまで待つ。 */
    suspend fun runInBackground(maxWaitMs: Long) {
        val auth = authManager.state.first { it !is AuthState.Restoring }
        if (auth !is AuthState.LoggedIn) return
        withTimeoutOrNull(30_000) { session.appliedObtainedAtEpochMs.first { it == auth.obtainedAtEpochMs } } ?: return
        maintain()
        withTimeoutOrNull(maxWaitMs) { state.first { it.active == null && (it.pending == 0 || it.waitingForNetwork) } }
    }

    private suspend fun syncCollection(
        kind: CollectionKind,
        id: String,
    ) {
        val songs = fetchSongs(kind, id) ?: return
        var added = 0
        var removed = 0
        update { current ->
            val before = current.collection(kind, id) ?: return@update current
            val (plus, minus) = DownloadPlan.diff(before.songs, songs)
            added = plus.size
            removed = minus.size
            current.withCollection(before.copy(songs = songs))
        }
        Log.i(WaveApplication.LOG_TAG, "downloads sync kind=$kind songs=${songs.size} added=$added removed=$removed")
    }

    private suspend fun fetchSongs(
        kind: CollectionKind,
        id: String,
    ): List<Song>? =
        when (kind) {
            CollectionKind.Album -> (music.album(id) as? CallResult.Ok)?.value?.songs
            CollectionKind.Playlist, CollectionKind.Liked -> {
                val songs = mutableListOf<Song>()
                var continuation: String? = null
                var pages = 0
                var ok = true
                do {
                    when (val page = music.playlist(id, continuation)) {
                        is CallResult.Ok -> {
                            songs += page.value.songs
                            continuation = page.value.continuation
                        }
                        is CallResult.Failed -> {
                            ok = false
                            continuation = null
                        }
                    }
                    pages++
                } while (continuation != null && pages < MAX_PLAYLIST_PAGES)
                // 途中で読めなければ合わせない（読めたぶんだけで外すと、取った曲を消してしまう）
                songs.takeIf { ok }
            }
        }

    /**
     * スマートダウンロードを選び直す（ADR-0016 の 10〜12）。外れた曲は消し、新しく入った曲を取る（手で取った曲は消さない）。
     * @param force 1日たっていなくても選び直す（入れたとき・曲数を変えたとき・画面から）
     */
    suspend fun refreshSmart(force: Boolean) {
        if (!settings.smartDownloads.first()) return
        if (!force && !SmartDownloads.refreshDue(manifest.smartRefreshedAtMs, nowMs())) return
        if (!network.online || mutableState.value.refreshingSmart) return
        mutableState.update { it.copy(refreshingSmart = true) }
        try {
            val total = settings.smartDownloadCount.first()
            val counts = playCounts.all()
            val history = (music.history() as? CallResult.Ok)?.value?.songs.orEmpty()
            val frequent = SmartDownloads.frequent(counts, history)
            val liked = runCatching { likedSongs.songs(likedVersion()).songs }.getOrDefault(emptyList())
            val seeds = SmartDownloads.seeds(frequent)
            val known = HashSet<String>().apply {
                counts.forEach { add(it.song.videoId) }
                history.forEach { add(it.videoId) }
                liked.forEach { add(it.videoId) }
                seeds.forEach { add(it.videoId) }
            }
            val radios = seeds.map { seed -> (music.upNext(seed.videoId, QueueSession.radioPlaylistIdOf(seed.videoId)) as? CallResult.Ok)?.value?.songs.orEmpty() }
            val discover = SmartDownloads.discover(radios, known)
            val home = (music.home() as? CallResult.Ok)?.value
            val shelves = home?.shelves.orEmpty().map { shelf -> shelf.items.mapNotNull { (it as? HomeItem.SongItem)?.song } }.filter { it.isNotEmpty() }
            val mix =
                if (shelves.sumOf { it.size } < total) {
                    home?.shelves.orEmpty().firstNotNullOfOrNull { shelf -> shelf.items.firstNotNullOfOrNull { (it as? HomeItem.PlaylistItem)?.playlist } }?.let { playlist ->
                        (music.playlist(playlist.playlistId) as? CallResult.Ok)?.value?.songs
                    }.orEmpty()
                } else {
                    emptyList()
                }
            val recommended = SmartDownloads.recommended(shelves, mix)
            val picked =
                SmartDownloads.pick(
                    mapOf(SmartCategory.Frequent to frequent, SmartCategory.Discover to discover, SmartCategory.Recommended to recommended),
                    total,
                )
            Log.i(WaveApplication.LOG_TAG, "smart downloads picked=${picked.size} frequent=${frequent.size} discover=${discover.size} recommended=${recommended.size}")
            // 何も読めなかった（通信が切れた）なら、前の選びを残す
            if (picked.isEmpty() && manifest.smart.isNotEmpty()) return
            if (!settings.smartDownloads.first()) return
            update { it.withSmart(picked, nowMs()) }
            mutableState.update { it.copy(failed = it.failed - picked.map { p -> p.song.videoId }.toSet()) }
        } finally {
            mutableState.update { it.copy(refreshingSmart = false) }
        }
    }

    // ---- 取る ----

    /** 目録を変えて書き、持ち主のいないファイルを消し、取る側を起こす。 */
    private suspend fun update(change: (DownloadManifest) -> DownloadManifest) {
        lock.withLock {
            val next = change(manifest)
            if (next == manifest) return
            manifest = next
            withContext(Dispatchers.IO) {
                store.writeManifest(next)
                DownloadPlan.orphans(next, store.completeIds()).forEach(store::delete)
            }
            publishLocked()
        }
        wake.trySend(Unit)
    }

    private suspend fun publishLocked() {
        val used = withContext(Dispatchers.IO) { store.bytesUsed() }
        mutableState.update { it.copy(manifest = manifest, complete = store.completeIds(), bytesUsed = used) }
    }

    private fun allowed(): Boolean {
        val net = network.state.value
        return net.online && (anyNetwork || net.unmetered)
    }

    private suspend fun worker() {
        while (true) {
            val failed = mutableState.value.failed
            val next = DownloadPlan.missing(manifest, store.completeIds()).firstOrNull { it !in failed }
            if (next == null || authManager.state.value !is AuthState.LoggedIn) {
                mutableState.update { it.copy(active = null, waitingForNetwork = false) }
                wake.receive()
                continue
            }
            if (!allowed()) {
                mutableState.update { it.copy(active = null, waitingForNetwork = true) }
                wake.receive()
                continue
            }
            mutableState.update { it.copy(waitingForNetwork = false) }
            val outcome =
                try {
                    download(next)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    // 例外文には URL が入りうるので、種類だけを記録する
                    Log.i(WaveApplication.LOG_TAG, "download error type=${error.javaClass.simpleName}")
                    Outcome.Retry
                }
            store.partFile(next).takeIf { outcome != Outcome.Done }?.delete()
            mutableState.update { it.copy(active = null) }
            when (outcome) {
                Outcome.Done -> lock.withLock { publishLocked() }
                Outcome.Failed -> mutableState.update { it.copy(failed = it.failed + next) }
                // 通信の失敗: 少し待ってからやり直す（通信が変われば、すぐ見直す）
                Outcome.Retry -> withTimeoutOrNull(RETRY_DELAY_MS) { wake.receive() }
                Outcome.Cancelled -> Unit
            }
        }
    }

    private enum class Outcome { Done, Failed, Retry, Cancelled }

    private suspend fun download(videoId: String): Outcome =
        withContext(Dispatchers.IO) {
            val song = manifest.songOf(videoId)
            val external = Feed.isExternal(videoId)
            val generation = music.currentLoginGeneration()
            val uri = Feed.audioUrlOf(videoId)?.let(TrackUri::episode) ?: if (external) return@withContext Outcome.Failed else TrackUri.of(videoId, generation)
            val part = store.partFile(videoId)
            part.delete()
            var position = 0L
            var total: Long? = null
            val buffer = ByteArray(64 * 1024)
            mutableState.update { it.copy(active = DownloadProgress(videoId, song?.title, 0, null)) }
            Log.i(WaveApplication.LOG_TAG, "download start external=$external")
            try {
                FileOutputStream(part).use { out ->
                    while (true) {
                        if (!manifest.isWanted(videoId)) return@withContext Outcome.Cancelled
                        if (!allowed()) return@withContext Outcome.Retry
                        val length = if (external) C.LENGTH_UNSET.toLong() else total?.let { minOf(CHUNK_BYTES, it - position) } ?: CHUNK_BYTES
                        val source = sourceFactory.createDataSource()
                        var readThis = 0L
                        try {
                            val opened = source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
                            if (total == null) {
                                total = if (external) opened.takeIf { it != C.LENGTH_UNSET.toLong() }?.let { position + it } else streamCache.peek(videoId, generation)?.contentLength
                            }
                            while (true) {
                                val read = source.read(buffer, 0, buffer.size)
                                if (read == C.RESULT_END_OF_INPUT) break
                                out.write(buffer, 0, read)
                                position += read
                                readThis += read
                                mutableState.update { it.copy(active = DownloadProgress(videoId, song?.title, position, total)) }
                                if (!manifest.isWanted(videoId)) return@withContext Outcome.Cancelled
                            }
                        } finally {
                            runCatching { source.close() }
                        }
                        val known = total
                        val finished =
                            when {
                                known != null -> position >= known
                                // 長さが分からない: 区切りより短く返ったら終わり（エピソードは1回で最後まで読む）
                                else -> external || readThis < length
                            }
                        if (finished) break
                        if (readThis == 0L) return@withContext Outcome.Retry
                        // 区切りの間に別のファイル（itag・長さ）へ変わったら、継ぎ足さずに取り直す
                        if (!external && known != null && streamCache.peek(videoId, generation)?.contentLength?.let { it != known } == true) return@withContext Outcome.Retry
                    }
                }
            } catch (rejected: StreamRejectedException) {
                return@withContext Outcome.Failed
            } catch (resolve: StreamResolveException) {
                return@withContext if (resolve.transient || resolve.reason == StreamFailure.NotLoggedIn) Outcome.Retry else Outcome.Failed
            } catch (io: IOException) {
                Log.i(WaveApplication.LOG_TAG, "download io failure type=${io.javaClass.simpleName}")
                return@withContext Outcome.Retry
            }
            if (position == 0L) return@withContext Outcome.Failed
            val resolved = if (external) null else streamCache.peek(videoId, generation)
            val ok =
                lock.withLock {
                    // 取っている間に消された（ログアウト・削除）なら残さない
                    if (!manifest.isWanted(videoId)) return@withLock false
                    store.finish(videoId, part, DownloadedTrack(videoId, resolved?.mimeType, resolved?.itag, position, complete = true, song = song, downloadedAtMs = nowMs()))
                }
            Log.i(WaveApplication.LOG_TAG, "download done ok=$ok bytes=$position")
            if (ok) Outcome.Done else Outcome.Cancelled
        }

    private companion object {
        /** 1回に取る範囲。googlevideo は範囲を区切らないと遅くされるので、区切って取る。 */
        const val CHUNK_BYTES = 4L * 1024 * 1024
        const val RETRY_DELAY_MS = 60_000L
        const val MAX_PLAYLIST_PAGES = 50
    }
}
