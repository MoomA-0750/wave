package xyz.mooma0750.wave.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.session.StatsOutcome
import xyz.mooma0750.wave.core.stream.PlaybackHistory
import xyz.mooma0750.wave.core.stream.ResolvedStream
import kotlin.random.Random

/**
 * いつ再生履歴を送るかを決める（ADR-0006 の 1・3）。メインスレッドだけから使う。
 * - 再生1回は曲の遷移で始まり次の遷移で終わる（1曲リピートの繰り返しも遷移）。送るのは1回だけ
 * - 聴いた時間は ListenCounter（単調時計で再生中の区間だけ）
 * - 統計 URL は遷移後に最初に再生が始まったときに1回だけキャッシュから取る。無ければ送らない（統計のために解決しない）
 * - 待ちは一時停止・遷移で取り消すが、始まった送信は取り消さない（取り消すと送ったかどうか分からないまま失われる）。
 *   ログアウト・切り替えでの取り消しは WaveSession の世代のスコープが担う
 */
class PlaybackHistoryTracker(
    private val player: Player,
    private val cache: ResolvedStreamCache,
    private val history: PlaybackHistory,
    private val enabled: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val random: Random,
) {
    private class Play(
        val itemId: String,
        val videoId: String,
        val cpn: String,
        val durationHintMs: Long?,
        val carriedListenedMs: Long,
    ) {
        var counter: ListenCounter? = null
        var looked = false
        var stream: ResolvedStream? = null
        var done = false
    }

    private var play: Play? = null
    private var waiting: Job? = null

    /** 引き取ったときに、次の遷移で今の曲に引き継ぐ値（決定 K-8）。 */
    private var seed: Triple<String, Long, Boolean>? = null

    /** 引き取られる側: 今の曲について（聴いた時間, 履歴を送り済みか）。 */
    fun handoffState(): Pair<Long, Boolean> {
        val current = play ?: return 0L to false
        return (current.counter?.listenedMs(elapsedMs()) ?: current.carriedListenedMs) to current.done
    }

    /** 引き取る側: 置き換える直前に呼ぶ。itemId の曲の最初の再生に、聴いた時間と送り済みかを引き継ぐ。 */
    fun seedHandoff(
        itemId: String,
        listenedMs: Long,
        historySent: Boolean,
    ) {
        seed = Triple(itemId, listenedMs, historySent)
    }

    fun onMediaItemTransition() {
        stop()
        val mediaId = player.currentMediaItem?.mediaId
        val carried = seed?.takeIf { it.first == mediaId }
        seed = null
        play =
            Media3QueuePlayer.songOf(player.currentMediaItem)?.let { song ->
                Play(mediaId.orEmpty(), song.videoId, PlaybackHistory.newCpn(random), song.durationSeconds?.let { it * 1000L }, carried?.second ?: 0L).also {
                    // 他の端末で送り済みなら、この再生では送らない（二重に載せない）
                    if (carried?.third == true) it.done = true
                }
            }
        if (player.isPlaying) started()
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) started() else stop()
    }

    /** ログアウト・アカウントの切り替え。 */
    fun reset() {
        stop()
        play = null
    }

    private fun started() {
        val current = play ?: return
        if (current.done) return
        if (!current.looked) {
            current.looked = true
            val cached = cache.peek(current.videoId, TrackUri.epochOf(player.currentMediaItem?.localConfiguration?.uri))
            current.stream = cached?.takeIf { it.hasPlaybackStats }
            Log.i(WaveApplication.LOG_TAG, "history play started cached=${cached != null} stats=${cached?.hasPlaybackStats}")
        }
        if (current.stream == null) return
        val counter =
            current.counter ?: ListenCounter(ListenCounter.thresholdFor(player.duration.takeIf { it != C.TIME_UNSET } ?: current.durationHintMs), current.carriedListenedMs).also {
                current.counter = it
            }
        val remaining = counter.start(elapsedMs()) ?: return
        waiting =
            scope.launch {
                delay(remaining)
                waiting = null
                current.done = true
                // 待ちとは別の Job で送る（stop で取り消されないように）
                scope.launch { send(current) }
            }
    }

    private fun stop() {
        waiting?.cancel()
        waiting = null
        play?.counter?.stop(elapsedMs())
    }

    private suspend fun send(current: Play) {
        val stream = current.stream ?: return
        if (!enabled()) {
            Log.i(WaveApplication.LOG_TAG, "history skipped disabled")
            return
        }
        val outcome = history.report(stream, current.cpn, player.currentPosition / 1000.0)
        // 種類と status の数値だけ（URL・cpn・曲は出さない）
        Log.i(
            WaveApplication.LOG_TAG,
            "history outcome=${outcome::class.simpleName}" + if (outcome is StatsOutcome.Sent) " status=${outcome.status} html=${outcome.html}" else "",
        )
    }
}
