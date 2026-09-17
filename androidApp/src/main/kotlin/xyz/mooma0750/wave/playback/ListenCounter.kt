package xyz.mooma0750.wave.playback

/**
 * 1回の再生で聴いた時間（ADR-0006 の 1）。位置ではなく、単調時計で再生中の区間だけを足す。Android に依らない。
 */
class ListenCounter(
    private val thresholdMs: Long,
) {
    private var listenedMs = 0L
    private var playingSinceMs: Long? = null

    val running: Boolean
        get() = playingSinceMs != null

    /** 再生が始まった。送るまでの残り時間を返す。既に数えているなら null。 */
    fun start(nowMs: Long): Long? {
        if (playingSinceMs != null) return null
        playingSinceMs = nowMs
        return (thresholdMs - listenedMs).coerceAtLeast(0)
    }

    fun stop(nowMs: Long) {
        playingSinceMs?.let { listenedMs += (nowMs - it).coerceAtLeast(0) }
        playingSinceMs = null
    }

    fun listenedMs(nowMs: Long): Long = listenedMs + (playingSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

    companion object {
        const val THRESHOLD_MS = 30_000L
        const val MIN_THRESHOLD_MS = 5_000L

        /** 30秒。曲が30秒より短ければ長さの半分（5秒以上）。長さが分からなければ30秒。 */
        fun thresholdFor(durationMs: Long?): Long {
            val duration = durationMs?.takeIf { it > 0 } ?: return THRESHOLD_MS
            return if (duration < THRESHOLD_MS) (duration / 2).coerceAtLeast(MIN_THRESHOLD_MS) else THRESHOLD_MS
        }
    }
}
