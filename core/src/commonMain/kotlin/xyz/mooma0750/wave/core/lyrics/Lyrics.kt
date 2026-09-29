package xyz.mooma0750.wave.core.lyrics

/** 歌詞の1行。startMs は曲の頭からの時刻。 */
data class LyricLine(
    val startMs: Long,
    val text: String,
)

/** 曲の歌詞。source は出典の表示（「LRCLIB」「Source: Musixmatch」など）。 */
sealed interface Lyrics {
    val source: String?

    /** 行ごとに時刻がある（再生位置に合わせて表示する）。 */
    data class Synced(
        val lines: List<LyricLine>,
        override val source: String?,
    ) : Lyrics

    /** 時刻の無い本文。 */
    data class Plain(
        val text: String,
        override val source: String?,
    ) : Lyrics
}

/** LRC 形式（`[mm:ss.xx]本文`）の読み取り。画面にも通信にも依らない。 */
object Lrc {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    /**
     * 時刻の付いた行だけを、時刻の順に返す。1行に時刻が複数あれば（繰り返しの行）、それぞれの時刻に同じ本文を置く。
     * `[offset:+500]` は全体を 500ms 早める（LRC の決まり）。題名などのタグ（`[ar:…]`）と時刻の無い行は読み飛ばす。
     */
    fun parse(text: String): List<LyricLine> {
        var offset = 0L
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            OFFSET.find(line)?.let {
                offset = it.groupValues[1].toLong()
                return@forEach
            }
            val times = mutableListOf<Long>()
            var rest = line
            while (true) {
                val match = TIME.find(rest)?.takeIf { it.range.first == 0 } ?: break
                times += millisOf(match)
                rest = rest.substring(match.range.last + 1)
            }
            val body = rest.trim()
            times.forEach { lines += LyricLine((it - offset).coerceAtLeast(0), body) }
        }
        return lines.sortedBy { it.startMs }
    }

    private fun millisOf(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        // 小数部は桁数で意味が変わる（.9 = 900ms、.90 = 900ms、.905 = 905ms）
        val fractionMs = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toLong()
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }
}

/** 再生位置と歌詞の行の対応。 */
object LyricsTiming {
    /** 行の開始より少し早く切り替える（読み始めが歌に遅れないように）。 */
    const val LEAD_MS = 150L

    /** positionMs で歌っている行の位置。最初の行より前なら -1。lines は時刻の順。 */
    fun currentIndex(
        lines: List<LyricLine>,
        positionMs: Long,
    ): Int {
        val at = positionMs + LEAD_MS
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= at) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
