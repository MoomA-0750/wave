package xyz.mooma0750.wave.connect2

/**
 * 中継の接続の見張り（Android・Windows・Mac・iPhone・Linux・ブラウザの拡張で同じ規則。docs/decisions.md 2026-11-14）。
 * 死んだソケットを「つながっている」と思い込んだまま、ほかの端末の再生が届かないのを防ぐ。
 *
 * - つなぎ始めてから WELCOME_TIMEOUT_MS のうちに welcome が来なければ、切ってつなぎ直す
 * - つながっている間、IDLE_MS のあいだ何も届かなければ確かめ（PROBE）を送り、PROBE_TIMEOUT_MS のうちに何も届かなければ切ってつなぎ直す
 * - 前面に戻った・ネットにつながり直した・スリープから戻ったら（wake）、すぐ確かめを送る
 *
 * 時刻は呼ぶ側の単調時計（ミリ秒）。スレッドは呼ぶ側で1本にそろえる。
 */
class RelayWatch {
    enum class Action {
        None,

        /** 確かめを送る。 */
        Probe,

        /** このソケットは死んでいる。切ってつなぎ直す。 */
        Dead,
    }

    private var openedAt = 0L
    private var welcomed = false
    private var lastReceivedAt = 0L
    private var probeSentAt: Long? = null

    /** 新しいソケットでつなぎ始めた。 */
    fun opened(nowMs: Long) {
        openedAt = nowMs
        welcomed = false
        lastReceivedAt = nowMs
        probeSentAt = null
    }

    /** 中継から何か届いた（welcome なら welcome = true）。 */
    fun received(
        nowMs: Long,
        welcome: Boolean = false,
    ) {
        if (welcome) welcomed = true
        lastReceivedAt = nowMs
        probeSentAt = null
    }

    /** 前面に戻った・ネットにつながり直した。つながっていれば、すぐ確かめる。 */
    fun wake(nowMs: Long): Action {
        if (!welcomed || probeSentAt != null) return Action.None
        probeSentAt = nowMs
        return Action.Probe
    }

    /** 1秒ごとに呼ぶ。 */
    fun tick(nowMs: Long): Action {
        if (!welcomed) return if (nowMs - openedAt >= WELCOME_TIMEOUT_MS) Action.Dead else Action.None
        probeSentAt?.let { return if (nowMs - it >= PROBE_TIMEOUT_MS) Action.Dead else Action.None }
        if (nowMs - lastReceivedAt >= IDLE_MS) {
            probeSentAt = nowMs
            return Action.Probe
        }
        return Action.None
    }

    companion object {
        const val WELCOME_TIMEOUT_MS = 15_000L
        const val IDLE_MS = 20_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val TICK_MS = 1_000L

        /** 確かめに送るもの。鎖のずっと先を頼む（中継は空の鎖を返すだけで、どの版の中継も答える）。 */
        const val PROBE = """{"op":"chain_get","fromSeq":2147483647}"""

        /**
         * つなぎ直すまでの待ち。failedAttempts は welcome を受けてから続けて失敗した数（つながっていたソケットが切れた直後は 0）。
         * 1秒、2秒、4秒、8秒、16秒、以後30秒。
         */
        fun reconnectDelayMs(failedAttempts: Int): Long = if (failedAttempts <= 0) 1_000L else minOf(2_000L shl (failedAttempts - 1).coerceAtMost(4), 30_000L)
    }
}
