package xyz.mooma0750.wave.core.music

/**
 * ライブラリの「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）。
 * YouTube の回は「6 日前」「2025/09/08」のような表示しか持たないので、だいたいの日時に直して新しい順に並べる。
 */
object NewEpisodes {
    /** RSS の番組ごとに混ぜる回の数。 */
    const val PER_FEED = 10

    /** これより古い RSS の回は混ぜない（YouTube の新着も数か月分まで）。 */
    const val MAX_AGE_DAYS = 90

    private const val DAY = 86_400L

    fun merge(
        youtube: List<PodcastEpisode>,
        feeds: List<FeedShow>,
        nowEpochSeconds: Long,
        positions: (String) -> EpisodePosition? = { null },
    ): List<PodcastEpisode> {
        // 読めない表示の YouTube の回は、一つ前の回と同じ日時にする（並びを崩さない）
        var previous = nowEpochSeconds
        val dated =
            youtube.map { episode ->
                val at = episode.publishedText?.let { approximateEpochSeconds(it, nowEpochSeconds) } ?: previous
                previous = at
                at to episode
            }
        val rss =
            feeds.flatMap { show ->
                show.episodes(0, PER_FEED).mapNotNull { episode ->
                    val at = episode.publishedEpochSeconds ?: return@mapNotNull null
                    if (nowEpochSeconds - at > MAX_AGE_DAYS * DAY) return@mapNotNull null
                    at to episode.toEpisode(show, positions(episode.audioUrl))
                }
            }
        // 同じ時刻なら YouTube を先に（安定な並べ替え）
        return (dated + rss).sortedByDescending { it.first }.map { it.second }
    }

    private val RELATIVE = Regex("(\\d+)\\s*(分|時間|日|週間|か月|ヶ月|年)前")
    private val ABSOLUTE = Regex("(\\d{4})[/年.-](\\d{1,2})[/月.-](\\d{1,2})")

    /** 「6 日前」「2 時間前」「2025/09/08」「2025年9月8日」をだいたいの日時（UTC のエポック秒）にする。読めなければ null。 */
    fun approximateEpochSeconds(
        text: String,
        nowEpochSeconds: Long,
    ): Long? {
        RELATIVE.find(text)?.let { match ->
            val n = match.groupValues[1].toLongOrNull() ?: return null
            val unit =
                when (match.groupValues[2]) {
                    "分" -> 60L
                    "時間" -> 3_600L
                    "日" -> DAY
                    "週間" -> 7 * DAY
                    "か月", "ヶ月" -> 30 * DAY
                    else -> 365 * DAY
                }
            return nowEpochSeconds - n * unit
        }
        ABSOLUTE.find(text)?.let { match ->
            val (y, m, d) = match.destructured
            return FeedParser.parseRfc822("$d ${MONTH_NAMES.getOrNull(m.toInt() - 1) ?: return null} $y 00:00:00 GMT")
        }
        return null
    }

    private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
