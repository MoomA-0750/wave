package xyz.mooma0750.wave.core.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.music.SongPage
import xyz.mooma0750.wave.core.music.UpNextPage
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind

/** キューの項目。itemId は項目ごとに一意（同じ曲を2回入れても区別できる）。 */
@Serializable
data class QueueItem(
    val itemId: String,
    val song: Song,
    val origin: ItemOrigin,
)

@Serializable
enum class ItemOrigin {
    /** 利用者が選んだ曲、または選んだプレイリストの曲。 */
    Source,

    /** 自動で追加したサジェスト。 */
    Suggestion,

    /** 利用者が「次に再生」「キューに追加」した曲。 */
    UserAdded,
}

/** キューの出どころ。続きをどこから取るかを決める。 */
@Serializable
sealed interface QueueSource {
    /** ある曲から始まるラジオ。 */
    @Serializable
    data class Radio(
        val seedVideoId: String,
    ) : QueueSource

    /** プレイリスト。最後まで行ったらラジオにつなぐ（決定 E4）。 */
    @Serializable
    data class Playlist(
        val playlistId: String,
    ) : QueueSource
}

/** 端末間の受け渡しと、将来の永続化の土台になるキューのスナップショット（決定 E6）。 */
@Serializable
data class QueueSnapshot(
    val generation: Long,
    val items: List<QueueItem>,
    val currentIndex: Int,
    val source: QueueSource?,
    val continuation: String?,
    val exhausted: Boolean,
)

/** Media3 などのプレイヤーを、キューの操作だけに絞って扱うための境界。すべて同じスレッド（メインスレッド）から呼ぶ。 */
interface QueuePlayer {
    val items: List<QueueItem>

    /** 再生中の項目の位置。キューが空なら -1。 */
    val currentIndex: Int

    /** 再生が最後まで行って止まっているか。 */
    val ended: Boolean

    /** キュー全体を繰り返しているか。 */
    val repeatAll: Boolean

    fun replace(
        items: List<QueueItem>,
        startIndex: Int,
    )

    fun append(items: List<QueueItem>)

    fun insertAfterCurrent(items: List<QueueItem>)

    /** 保存したキューを戻す。再生は始めない（起動のたびにストリームを解決しない）。 */
    fun restore(
        items: List<QueueItem>,
        startIndex: Int,
        positionMs: Long,
    )

    /** 今の項目より後ろを、この並びに置き換える（今の項目の再生は続ける）。 */
    fun replaceAfterCurrent(items: List<QueueItem>)

    /** 先頭から count 件を取り除く（再生済みの項目を刈る）。今の項目の再生は続ける。 */
    fun removeFirst(count: Int)

    fun remove(itemId: String)

    fun jumpTo(itemId: String)

    /** 最後まで行って止まっているときに、追加した項目の先頭から再生を続ける。 */
    fun resumeAt(index: Int)

    fun clear()
}

/** 続きの取得元。MusicRepository を包む。 */
interface QueueContentSource {
    suspend fun upNext(
        videoId: String?,
        playlistId: String?,
        continuation: String?,
    ): CallResult<UpNextPage>

    suspend fun playlistPage(
        playlistId: String,
        continuation: String,
    ): CallResult<SongPage>
}

/**
 * 再生キューの文脈（出どころ・続き・尽きたか）と、サジェストの自動追加を受け持つ。
 * 並びの正本は QueuePlayer（Media3）。キューを置き換えるたびに世代を進め、古い世代の取得結果は捨てる。
 * すべての操作は scope のスレッド（メインスレッド）から呼ぶ。
 */
class QueueSession(
    private val player: QueuePlayer,
    private val content: QueueContentSource,
    private val scope: CoroutineScope,
    private val newItemId: () -> String,
    private val random: Random = Random.Default,
) {
    var generation: Long = 0
        private set
    private var source: QueueSource? = null
    private var continuation: String? = null
    private var radioPlaylistId: String? = null
    private var exhausted = true
    private var fetchJob: Job? = null

    /** 保存から戻した continuation を使っている。古くなって拒否されたら、最後の曲からラジオをやり直す。 */
    private var usingRestoredContinuation = false

    /** 1曲を選んで再生する。その曲から始まるラジオをサジェストとして追加していく。 */
    fun playSong(song: Song) {
        startGeneration()
        player.replace(listOf(item(song, ItemOrigin.Source)), startIndex = 0)
        startRadio(song.videoId)
        maybeFetch()
    }

    /**
     * 一覧（プレイリストなど）の途中から再生する。続きのページがあれば、終わりが近づいたら取る。
     * shuffle なら読み込み済みの曲を並べ替えて先頭から再生する（続きのページは並べ替えずに後ろに付く）。
     */
    fun playFrom(
        songs: List<Song>,
        startIndex: Int,
        playlistId: String?,
        playlistContinuation: String?,
        shuffle: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        startGeneration()
        if (shuffle) {
            player.replace(songs.shuffled(random).map { item(it, ItemOrigin.Source) }, 0)
        } else {
            player.replace(songs.map { item(it, ItemOrigin.Source) }, startIndex.coerceIn(songs.indices))
        }
        if (playlistId != null) {
            source = QueueSource.Playlist(playlistId)
            continuation = playlistContinuation
            radioPlaylistId = null
            exhausted = false
            if (playlistContinuation == null) switchToRadio()
        } else {
            startRadio(songs.last().videoId)
        }
        maybeFetch()
    }

    fun addNext(song: Song) {
        player.insertAfterCurrent(listOf(item(song, ItemOrigin.UserAdded)))
    }

    fun addToEnd(song: Song) {
        player.append(listOf(item(song, ItemOrigin.UserAdded)))
    }

    fun remove(itemId: String) {
        player.remove(itemId)
        maybeFetch()
    }

    fun jumpTo(itemId: String) {
        player.jumpTo(itemId)
        maybeFetch()
    }

    /**
     * 今の曲より後ろを並べ替える（決定 F-1）。キューを置き換えないので世代は進めず、取得中の続きもそのまま後ろに付く。
     * Media3 のシャッフルモードは並びと別の順序を持ち、キューの表示と続きの取得の判断が食い違うので使わない。
     */
    fun shuffleUpcoming() {
        val current = player.currentIndex
        if (current < 0) return
        val upcoming = player.items.drop(current + 1)
        if (upcoming.size < 2) return
        player.replaceAfterCurrent(upcoming.shuffled(random))
    }

    /** 曲が変わった・再生が最後まで行ったときに呼ぶ。残りが少なければ続きを取る。 */
    fun onPlaybackProgressed() {
        maybeFetch()
    }

    /** リピートを切り替えたときに呼ぶ。全体リピートを外したら、止めていた続きの取得を再開する。 */
    fun onRepeatModeChanged() {
        maybeFetch()
    }

    /** ログアウトなど。キューを消して、取得を止める。 */
    fun reset() {
        startGeneration()
        exhausted = true
        source = null
        player.clear()
    }

    /** 保存用。キューが空なら null（保存せずにファイルを消す）。 */
    fun saved(
        obtainedAtEpochMs: Long,
        positionMs: Long,
        repeatMode: Int,
    ): SavedQueue? {
        val items = player.items
        val index = player.currentIndex
        if (items.isEmpty() || index !in items.indices) return null
        return SavedQueue(SavedQueue.VERSION, obtainedAtEpochMs, items, index, positionMs.coerceAtLeast(0), repeatMode, source, continuation, radioPlaylistId, exhausted)
    }

    /** 保存したキューを、再生を始めずに戻す。ログインの照合は呼び出し側で済ませる。 */
    fun restore(saved: SavedQueue) {
        startGeneration()
        player.restore(saved.items, saved.currentIndex, saved.positionMs)
        source = saved.source
        continuation = saved.continuation
        radioPlaylistId = saved.radioPlaylistId
        exhausted = saved.exhausted
        usingRestoredContinuation = saved.continuation != null
    }

    fun snapshot(): QueueSnapshot = QueueSnapshot(generation, player.items, player.currentIndex, source, continuation, exhausted)

    private fun startGeneration() {
        generation++
        usingRestoredContinuation = false
        fetchJob?.cancel()
        fetchJob = null
    }

    private fun startRadio(seedVideoId: String) {
        source = QueueSource.Radio(seedVideoId)
        radioPlaylistId = radioPlaylistIdOf(seedVideoId)
        continuation = null
        exhausted = false
    }

    /** プレイリストの最後まで来たら、最後の曲から始まるラジオにつなぐ。キューが空で切り替えられなければ、尽きた扱いにする。 */
    private fun switchToRadio(): Boolean {
        val last = player.items.lastOrNull()
        if (last == null) {
            exhausted = true
            return false
        }
        radioPlaylistId = radioPlaylistIdOf(last.song.videoId)
        source = QueueSource.Radio(last.song.videoId)
        continuation = null
        return true
    }

    /**
     * 全体リピート中は今のキューを繰り返すので、ラジオへの切り替えとサジェストの続きは取らない。
     * 曲を選んだ直後のラジオの最初のページと、プレイリストの続き（利用者が選んだ一覧そのもの）は取る。
     */
    private fun blockedByRepeat(): Boolean {
        if (!player.repeatAll) return false
        return when (source) {
            is QueueSource.Radio -> continuation != null
            is QueueSource.Playlist -> continuation == null
            null -> false
        }
    }

    private fun maybeFetch() {
        if (exhausted || fetchJob?.isActive == true || blockedByRepeat()) return
        val remaining = player.items.size - 1 - player.currentIndex
        if (remaining > FETCH_WHEN_REMAINING_AT_MOST) return
        val requestGeneration = generation
        fetchJob =
            scope.launch {
                val added =
                    try {
                        fetchOnce(requestGeneration)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                if (requestGeneration != generation) return@launch
                fetchJob = null
                // 取れた（0件より多い）ときだけ続けて判断する。失敗・0件で連続して取りに行かない
                if (added != null && added > 0) maybeFetch()
            }
    }

    /** @return 追加した件数。失敗なら null */
    private suspend fun fetchOnce(requestGeneration: Long): Int? {
        val currentSource = source ?: return null
        return when (currentSource) {
            is QueueSource.Playlist -> {
                val token = continuation
                if (token == null) {
                    // プレイリストの最後まで来ている。ラジオに切り替えられたら、続けてその最初のページを取る
                    if (player.repeatAll) return 0
                    return if (switchToRadio()) 1 else 0
                }
                when (val result = content.playlistPage(currentSource.playlistId, token)) {
                    is CallResult.Failed -> restartFromStaleContinuation(requestGeneration, result.kind)
                    is CallResult.Ok -> {
                        if (requestGeneration != generation) return null
                        usingRestoredContinuation = false
                        continuation = result.value.continuation
                        val added = appendItems(result.value.songs.map { item(it, ItemOrigin.Source) })
                        val switched = continuation == null && !player.repeatAll && switchToRadio()
                        // プレイリストの続きは重複でも入れる（同じ曲を2回含むプレイリストがある）。0件でもラジオへ切り替えたら続けて取る
                        if (added == 0 && switched) 1 else added
                    }
                }
            }
            is QueueSource.Radio -> {
                val token = continuation
                val result =
                    content.upNext(
                        videoId = if (token == null) currentSource.seedVideoId else null,
                        playlistId = radioPlaylistId,
                        continuation = token,
                    )
                when (result) {
                    is CallResult.Failed -> restartFromStaleContinuation(requestGeneration, result.kind)
                    is CallResult.Ok -> {
                        if (requestGeneration != generation) return null
                        usingRestoredContinuation = false
                        val page = result.value
                        page.playlistId?.let { radioPlaylistId = it }
                        continuation = page.continuation
                        // サジェストだけは、既にキューにある曲を除く
                        val known = player.items.map { it.song.videoId }.toMutableSet()
                        val fresh = page.songs.filter { known.add(it.videoId) }.map { item(it, ItemOrigin.Suggestion) }
                        val added = appendItems(fresh)
                        if (page.continuation == null || added == 0) exhausted = true
                        added
                    }
                }
            }
        }
    }

    /**
     * 保存から戻した continuation が古くなって拒否された（HTTP のエラー・解析できない応答）なら、最後の曲からラジオをやり直す。
     * 通信の失敗やログインの問題では、continuation のせいとは限らないのでやり直さない。
     * @return 続けて取るなら 1、失敗として扱うなら null
     */
    private fun restartFromStaleContinuation(
        requestGeneration: Long,
        kind: FailureKind,
    ): Int? {
        if (requestGeneration != generation || !usingRestoredContinuation) return null
        if (kind != FailureKind.Http && kind != FailureKind.Parse) return null
        usingRestoredContinuation = false
        if (source is QueueSource.Playlist && player.repeatAll) return null
        return if (switchToRadio()) 1 else null
    }

    private fun appendItems(items: List<QueueItem>): Int {
        if (items.isEmpty()) return 0
        val wasEnded = player.ended
        val firstNewIndex = player.items.size
        player.append(items)
        if (wasEnded) player.resumeAt(firstNewIndex)
        prunePlayed()
        return items.size
    }

    /**
     * ラジオの続きを足し続けると、再生済みの項目が際限なく溜まる（保存・表示・コントローラーへの通知が重くなる）。
     * 自動で続きを足したときだけ、今の曲より前を MAX_PLAYED_ITEMS 件に刈る。
     */
    private fun prunePlayed() {
        val excess = player.currentIndex - MAX_PLAYED_ITEMS
        if (excess > 0) player.removeFirst(excess)
    }

    private fun item(
        song: Song,
        origin: ItemOrigin,
    ) = QueueItem(newItemId(), song, origin)

    companion object {
        /** 残りがこの件数以下になったら続きを取る。 */
        const val FETCH_WHEN_REMAINING_AT_MOST = 2

        /** 自動で続きを足したときに残す、今の曲より前の項目の数。 */
        const val MAX_PLAYED_ITEMS = 100

        fun radioPlaylistIdOf(videoId: String): String = "RDAMVM$videoId"
    }
}
