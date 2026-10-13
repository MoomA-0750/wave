package xyz.mooma0750.wave.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.core.music.Feed
import xyz.mooma0750.wave.core.music.FeedLibrary

/**
 * RSS のエピソードの聴いたところ（ADR-0012）。流し始めたら覚えている位置へ飛び、流している間は数秒ごとに覚える。
 * 次へ進んだ（最後まで流れた）エピソードは「聴いた」にする。
 */
class EpisodePositions(
    private val player: Player,
    private val library: FeedLibrary,
    private val scope: CoroutineScope,
) {
    /** 今のエピソード（音声の URL・位置・長さ）。YouTube の曲なら null。 */
    private var current: Triple<String, Long, Long?>? = null

    /** 今のエピソードの位置を一度でも拾ったか（拾う前に曲が変わったら書かない。覚えていた続きの位置を 0 で消さない）。 */
    private var sampled = false

    /** 今の曲が変わった。前のエピソードの位置を書き、新しいエピソードなら続きへ飛ぶ。 */
    fun onMediaItemTransition(reason: Int) {
        current?.takeIf { sampled || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO }?.let { (url, position, duration) ->
            // 最後まで流れて次へ進んだ（またはリピートで頭へ戻った）なら聴いた
            val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val end = if (finished && duration != null) duration else position
            scope.launch { library.remember(url, (end / 1000).toInt(), duration?.let { (it / 1000).toInt() }) }
        }
        val url = currentUrl()
        current = url?.let { Triple(it, 0L, null) }
        sampled = false
        if (url == null) return
        // 端末の間の受け渡し・キューの復元で位置が付いて来たら、そちらを使う
        val resume = library.position(url)?.resumeSeconds ?: 0
        if (resume > RESUME_AT_LEAST_SECONDS && player.currentPosition < 1_000) player.seekTo(resume * 1000L)
    }

    /** 止めたら、その位置を書く。 */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) return
        sample()?.let { (url, position, duration) -> scope.launch { library.remember(url, (position / 1000).toInt(), duration?.let { (it / 1000).toInt() }) } }
    }

    /** 位置を読み、時々書く。 */
    private suspend fun sampleAndSave() {
        val (url, position, duration) = sample() ?: return
        library.remember(url, (position / 1000).toInt(), duration?.let { (it / 1000).toInt() })
    }

    private fun sample(): Triple<String, Long, Long?>? {
        val url = current?.first ?: return null
        if (currentUrl() != url) return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        return Triple(url, player.currentPosition, duration).also {
            current = it
            sampled = true
        }
    }

    private var ticks = 0

    init {
        // 数秒ごとに位置を拾い、SAVE_EVERY_TICKS 回に1回書く
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (player.isPlaying && current != null) {
                    if (++ticks % SAVE_EVERY_TICKS == 0) sampleAndSave() else sample()
                }
            }
        }
    }

    private fun currentUrl(): String? = Media3QueuePlayer.songOf(player.currentMediaItem)?.videoId?.let(Feed::audioUrlOf)

    private companion object {
        const val TICK_MS = 5_000L
        const val SAVE_EVERY_TICKS = 3
        const val RESUME_AT_LEAST_SECONDS = 5
    }
}
