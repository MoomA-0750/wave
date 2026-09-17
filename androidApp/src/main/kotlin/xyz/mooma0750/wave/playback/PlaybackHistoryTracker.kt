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
 * - 聴いた時間は位置ではなく、単調時計で isPlaying の区間だけを足す（シークで一気に達しない）
 * - 統計 URL は遷移後に最初に再生が始まったときに1回だけキャッシュから取る。無ければ送らない（統計のために解決しない）
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
        val videoId: String,
        val cpn: String,
        val durationHintMs: Long?,
    ) {
        var listenedMs = 0L
        var playingSinceMs: Long? = null
        var looked = false
        var stream: ResolvedStream? = null
        var done = false
    }

    private var play: Play? = null
    private var timer: Job? = null

    fun onMediaItemTransition() {
        stop()
        play =
            Media3QueuePlayer.songOf(player.currentMediaItem)?.let { song ->
                Play(song.videoId, PlaybackHistory.newCpn(random), song.durationSeconds?.let { it * 1000L })
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
        if (current.done || current.playingSinceMs != null) return
        if (!current.looked) {
            current.looked = true
            val cached = cache.peek(current.videoId)
            current.stream = cached?.takeIf { it.hasPlaybackStats }
            Log.i(WaveApplication.LOG_TAG, "history play started cached=${cached != null} stats=${cached?.hasPlaybackStats}")
        }
        if (current.stream == null) return
        current.playingSinceMs = elapsedMs()
        val remaining = (thresholdMs(current) - current.listenedMs).coerceAtLeast(0)
        timer = scope.launch {
            delay(remaining)
            send(current)
        }
    }

    private fun stop() {
        timer?.cancel()
        timer = null
        val current = play ?: return
        current.playingSinceMs?.let { current.listenedMs += elapsedMs() - it }
        current.playingSinceMs = null
    }

    private fun thresholdMs(current: Play): Long {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current.durationHintMs ?: return THRESHOLD_MS
        return if (duration < THRESHOLD_MS) (duration / 2).coerceAtLeast(MIN_THRESHOLD_MS) else THRESHOLD_MS
    }

    private suspend fun send(current: Play) {
        if (play !== current || current.done) return
        current.done = true
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

    private companion object {
        const val THRESHOLD_MS = 30_000L
        const val MIN_THRESHOLD_MS = 5_000L
    }
}
