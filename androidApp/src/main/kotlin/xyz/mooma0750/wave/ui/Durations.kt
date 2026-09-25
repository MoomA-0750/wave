package xyz.mooma0750.wave.ui

/** 曲の長さ・再生位置の表示（「分:秒」。1時間を超えても分で数える）。 */
object Durations {
    fun ofSeconds(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    fun ofMillis(ms: Long): String = ofSeconds(ms / 1000)
}
