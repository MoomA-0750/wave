package xyz.mooma0750.wave.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.Song

/**
 * この端末で聴いた回数を数える（スマートダウンロードの「いつも聴いている」、ADR-0016 の 10）。メインスレッドだけから使う。
 * 数え方は再生履歴と同じ: 曲の遷移から次の遷移までを1回とし、再生中の時間が ListenCounter のしきい値に届いたら1回。
 * RSS のエピソードとライブ配信は数えない。再生履歴の送信の設定とは別（端末の中にだけ残す）。
 */
class LocalPlayCounter(
    private val player: Player,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val record: suspend (Song) -> Unit,
) {
    private var song: Song? = null
    private var counter: ListenCounter? = null
    private var counted = false
    private var waiting: Job? = null

    fun onMediaItemTransition() {
        stop()
        song = Media3QueuePlayer.songOf(player.currentMediaItem)?.takeUnless { Feed.isExternal(it.videoId) || it.isLive }
        counter = null
        counted = false
        if (player.isPlaying) start()
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) start() else stop()
    }

    fun reset() {
        stop()
        song = null
    }

    private fun start() {
        val current = song ?: return
        if (counted) return
        val listen = counter ?: ListenCounter(ListenCounter.thresholdFor(player.duration.takeIf { it != C.TIME_UNSET } ?: current.durationSeconds?.let { it * 1000L })).also { counter = it }
        val remaining = listen.start(elapsedMs()) ?: return
        waiting =
            scope.launch {
                delay(remaining)
                waiting = null
                counted = true
                record(current)
            }
    }

    private fun stop() {
        waiting?.cancel()
        waiting = null
        counter?.stop(elapsedMs())
    }
}
