package xyz.mooma0750.wave.core.ai

import xyz.mooma0750.wave.core.lyrics.Lyrics

/**
 * Claude に送る歌詞と、Claude の答えの中の時刻（Windows の Wave.Core.Ai.AiText・Swift の AiText と同じ）。
 * 時刻付きの歌詞は行ごとに `[01:23] 本文` の形で送り、Claude には歌詞を引くときに同じ形で時刻を添えてもらう
 * （ai/agent.json の指示）。答えの中の `[mm:ss]` は、押すとその位置へ飛べるようにする。
 */
object AiText {
    /** 中継へ送る歌詞。時刻付きなら行ごとに `[mm:ss] 本文`（空の行は飛ばす）、時刻が無ければ本文のまま。 */
    fun lyrics(lyrics: Lyrics): String =
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.filter { it.text.isNotBlank() }.joinToString("\n") { "[${stamp(it.startMs)}] ${it.text.trim()}" }
            is Lyrics.Plain -> lyrics.text
        }

    /** 曲の頭からの時刻を `mm:ss` にする（秒より下は切り捨て。1時間を超えても分で数える）。 */
    fun stamp(positionMs: Long): String {
        val seconds = (positionMs / 1000).coerceAtLeast(0)
        return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** 答えの中の時刻 `[mm:ss]` の1つ。range は括弧を含む位置、label は括弧の中身。 */
    data class Timestamp(
        val range: IntRange,
        val positionMs: Long,
        val label: String,
    )

    private val TIMESTAMP = Regex("""\[(\d{1,3}):([0-5]\d)]""")

    /** 答えの中の時刻を、前から順に返す。 */
    fun timestamps(text: String): List<Timestamp> =
        TIMESTAMP.findAll(text).map { match ->
            val (minutes, seconds) = match.destructured
            Timestamp(match.range, (minutes.toLong() * 60 + seconds.toLong()) * 1000, "$minutes:$seconds")
        }.toList()
}
