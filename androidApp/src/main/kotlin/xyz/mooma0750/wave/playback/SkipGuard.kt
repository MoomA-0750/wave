package xyz.mooma0750.wave.playback

/** 再生エラーの種類。曲ごとの失敗だけを飛ばし、アカウントや仕組み全体の失敗では止める。 */
enum class PlaybackFailure {
    /** この曲だけの失敗（再生不可・フォーマット無し・拒否・パース・デコード）。飛ばして次へ。 */
    ThisSong,

    /** ネットワークの一時的な失敗。飛ばさずに止める（利用者が再生を押せば再開する）。 */
    Transient,

    /** ログインの失効・疑い・解読の失敗など、どの曲でも起きる失敗。飛ばすと /player を連打するので止める。 */
    Everywhere,
}

/**
 * 連続スキップの上限を数える。飛ばすためのシークで起きる曲の遷移では数えを戻さない
 * （戻すと上限に届かず、失敗する曲を飛ばし続ける）。
 *
 * 戻すのは、利用者が選んだときと、実際に一定時間再生できたとき。再生が始まった（READY）だけでは戻さない:
 * 最初のバイトは返るが途中から拒否される形の失敗では、曲ごとに READY になって上限に届かず、
 * cookie 付きの /player を叩き続けるため。さらに、時間の窓の中で飛ばした回数にも上限を置く。
 */
class SkipGuard(
    private val maxConsecutiveSkips: Int = 3,
    private val playedLongEnoughMs: Long = 10_000,
    private val windowMs: Long = 10 * 60 * 1000L,
    private val maxSkipsInWindow: Int = 6,
) {
    private var consecutiveSkips = 0
    private var playingSinceMs: Long? = null
    private val skipTimes = ArrayDeque<Long>()

    /** @return 飛ばしてよいか */
    fun onError(
        failure: PlaybackFailure,
        hasNext: Boolean,
        nowMs: Long,
    ): Boolean {
        creditPlayback(nowMs)
        playingSinceMs = null
        if (failure != PlaybackFailure.ThisSong || !hasNext) return false
        if (consecutiveSkips >= maxConsecutiveSkips) return false
        while (skipTimes.isNotEmpty() && nowMs - skipTimes.first() > windowMs) skipTimes.removeFirst()
        if (skipTimes.size >= maxSkipsInWindow) return false
        consecutiveSkips++
        skipTimes.addLast(nowMs)
        return true
    }

    /** 再生中になった。 */
    fun onPlaying(nowMs: Long) {
        if (playingSinceMs == null) playingSinceMs = nowMs
    }

    /** 再生中でなくなった（一時停止・バッファリング）。 */
    fun onNotPlaying(nowMs: Long) {
        creditPlayback(nowMs)
        playingSinceMs = null
    }

    /** 利用者が曲を選んだ・キューを置き換えた。 */
    fun onUserSelection() {
        consecutiveSkips = 0
        skipTimes.clear()
    }

    private fun creditPlayback(nowMs: Long) {
        val since = playingSinceMs ?: return
        if (nowMs - since >= playedLongEnoughMs) consecutiveSkips = 0
    }
}
