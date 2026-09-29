package xyz.mooma0750.wave.nowplaying

import kotlin.math.abs

/**
 * 映像だけのプレイヤーを、音の再生位置に合わせる判断。音は止めずに、映像の方を飛ばす・速さを少し変えて追いつかせる。
 * 画面にも Media3 にも依らない。
 */
internal object VideoSync {
    sealed interface Step {
        /** 映像に当たる部分が無い（MV に無い区間）。映像を止めて隠す。 */
        data object Hide : Step

        data class Seek(
            val toMs: Long,
            val play: Boolean,
        ) : Step

        data class Run(
            val speed: Float,
            val play: Boolean,
        ) : Step
    }

    /** これより離れたら飛ぶ。近ければ速さで合わせる（飛ぶと映像が一瞬止まるので）。 */
    const val SEEK_THRESHOLD_MS = 700L

    /** これ以内なら合っているとみなす。 */
    const val IN_SYNC_MS = 40L

    /** 止まっているときに合わせ直す差。 */
    const val PAUSED_TOLERANCE_MS = 80L

    /** 速さの変え幅の上限（音程は映像に無いので、見た目に不自然でない範囲）。 */
    const val MAX_SPEED_CHANGE = 0.1f

    /**
     * @param targetMs 今の音の位置に当たる映像の位置。null なら当たる部分が無い
     * @param videoReady 映像のプレイヤーが読み込み中でない（読み込み中に飛び直すと、いつまでも追いつかない）
     */
    fun step(
        targetMs: Long?,
        videoMs: Long,
        playing: Boolean,
        videoReady: Boolean,
    ): Step {
        if (targetMs == null) return Step.Hide
        val diff = videoMs - targetMs
        if (!playing) return if (abs(diff) > PAUSED_TOLERANCE_MS) Step.Seek(targetMs, play = false) else Step.Run(1f, play = false)
        if (!videoReady) return Step.Run(1f, play = true)
        if (abs(diff) > SEEK_THRESHOLD_MS) return Step.Seek(targetMs, play = true)
        if (abs(diff) <= IN_SYNC_MS) return Step.Run(1f, play = true)
        // 映像が進んでいれば遅く、遅れていれば速く。1秒で差を埋める速さ（上限あり）
        val speed = (1f - diff / 1000f).coerceIn(1f - MAX_SPEED_CHANGE, 1f + MAX_SPEED_CHANGE)
        return Step.Run(speed, play = true)
    }
}
