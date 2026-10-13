package xyz.mooma0750.wave.core.music

import kotlinx.serialization.Serializable

/**
 * RSS で配られるポッドキャスト（ADR-0012）。番組の目印は `rss:` + フィードの URL、
 * エピソードの曲の ID は `ext:` + 音声の URL。
 */
object Feed {
    const val SHOW_PREFIX = "rss:"
    const val AUDIO_PREFIX = "ext:"

    fun showBrowseId(feedUrl: String): String = SHOW_PREFIX + feedUrl

    /** `rss:` の番組ならフィードの URL。 */
    fun feedUrlOf(browseId: String): String? = browseId.takeIf { it.startsWith(SHOW_PREFIX) }?.removePrefix(SHOW_PREFIX)

    fun isFeedShow(browseId: String): Boolean = browseId.startsWith(SHOW_PREFIX)

    fun audioVideoId(audioUrl: String): String = AUDIO_PREFIX + audioUrl

    /** `ext:` の曲なら音声の URL（https のものだけ）。 */
    fun audioUrlOf(videoId: String): String? = videoId.takeIf { it.startsWith(AUDIO_PREFIX) }?.removePrefix(AUDIO_PREFIX)?.takeIf(::isAllowedFeedUrl)

    /** YouTube の曲ではない（ストリームの解決・履歴・評価・歌詞・ラジオをしない）。 */
    fun isExternal(videoId: String): Boolean = videoId.startsWith(AUDIO_PREFIX)

    /** 取りに行ってよい URL（https だけ。ADR-0012）。 */
    fun isAllowedFeedUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) && url.length in 12..4096 && url.none { it.isWhitespace() }

    /** 打ち込まれた URL を整える（`feed://`・`http://`・スキーム無しを https にする）。使えなければ null。 */
    fun normalizeFeedUrl(input: String): String? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()
        val url =
            when {
                lower.startsWith("https://") -> trimmed
                lower.startsWith("http://") -> "https://" + trimmed.substring(7)
                lower.startsWith("feed://") -> "https://" + trimmed.substring(7)
                lower.startsWith("feed:") -> normalizeFeedUrl(trimmed.substring(5)) ?: return null
                "://" in trimmed -> return null
                '.' in trimmed -> "https://$trimmed"
                else -> return null
            }
        return url.takeIf(::isAllowedFeedUrl)
    }
}

/** RSS で登録した番組（端末ごとに覚える）。 */
@Serializable
data class FeedSubscription(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    /** 登録した時刻（エポックミリ秒）。 */
    val addedAtMs: Long = 0,
) {
    val summary: PodcastSummary get() = PodcastSummary(Feed.showBrowseId(feedUrl), title, author, thumbnailUrl)
}

/** エピソードの聴いたところ（音声の URL ごと）。 */
@Serializable
data class EpisodePosition(
    val positionSeconds: Int,
    val durationSeconds: Int? = null,
    /** 最後まで（残り30秒未満まで）聴いた。 */
    val finished: Boolean = false,
    val updatedAtMs: Long = 0,
) {
    /** 0〜100。分からなければ null。 */
    val percent: Int?
        get() =
            when {
                finished -> 100
                durationSeconds == null || durationSeconds <= 0 -> null
                else -> (positionSeconds * 100 / durationSeconds).coerceIn(0, 100).takeIf { it > 0 }
            }

    /** 続きから流す位置（聴き終えていれば頭から）。 */
    val resumeSeconds: Int get() = if (finished) 0 else positionSeconds

    companion object {
        const val FINISHED_MARGIN_SECONDS = 30

        fun of(
            positionSeconds: Int,
            durationSeconds: Int?,
            nowMs: Long,
        ): EpisodePosition {
            val finished = durationSeconds != null && durationSeconds > 0 && durationSeconds - positionSeconds < FINISHED_MARGIN_SECONDS
            return EpisodePosition(positionSeconds.coerceAtLeast(0), durationSeconds, finished, nowMs)
        }
    }
}

/**
 * 読んだ RSS の番組。エピソードは新しい順。
 * エピソードは要るところだけ読む（数百回分の番組を、開くたびに全部ほどかない。debug の端末では1回分に数 ms かかる）。
 */
class FeedShow(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val thumbnailUrl: String?,
    /** エピソードの元（新しい順）。 */
    private val items: List<XmlRange>,
) {
    private val parsed = HashMap<Int, FeedEpisode?>()

    /** エピソードの項目の数（音声の無いものも数える）。 */
    val itemCount: Int get() = items.size

    val subscription: FeedSubscription get() = FeedSubscription(feedUrl, title, author, thumbnailUrl)

    /** [from] 番目から [count] 件の項目のうち、エピソードとして読めたもの。 */
    fun episodes(
        from: Int,
        count: Int,
    ): List<FeedEpisode> = (from until minOf(items.size, from + count)).mapNotNull { index -> parsed.getOrPut(index) { FeedParser.episode(items[index]) } }

    /** 全部のエピソード（確かめ用）。 */
    val allEpisodes: List<FeedEpisode> get() = episodes(0, items.size)

    /** 番組のページの形にする。エピソードは [count] 件の項目まで（[saved] は登録しているか）。 */
    fun toPage(
        saved: Boolean?,
        count: Int = PAGE_SIZE,
        positions: (String) -> EpisodePosition? = { null },
    ): PodcastPage =
        PodcastPage(
            browseId = Feed.showBrowseId(feedUrl),
            title = title,
            author = author,
            description = description,
            thumbnailUrl = thumbnailUrl,
            episodes = episodes(0, count).map { it.toEpisode(this, positions(it.audioUrl)) },
            continuation = continuationAfter(count),
            saved = saved,
        )

    /** 続き（[token] は [toPage]・前の続きが返したもの）。 */
    fun more(
        token: String,
        positions: (String) -> EpisodePosition? = { null },
    ): SearchPage<PodcastEpisode> {
        val from = token.removePrefix(CONTINUATION_PREFIX).toIntOrNull()?.takeIf { it in 0..items.size } ?: return SearchPage(emptyList(), null)
        return SearchPage(episodes(from, PAGE_SIZE).map { it.toEpisode(this, positions(it.audioUrl)) }, continuationAfter(from + PAGE_SIZE))
    }

    private fun continuationAfter(count: Int): String? = if (count < items.size) CONTINUATION_PREFIX + count else null

    companion object {
        const val PAGE_SIZE = 30
        const val CONTINUATION_PREFIX = "feed:"
    }
}

data class FeedEpisode(
    val title: String,
    val audioUrl: String,
    /** 公開日（UTC のエポック秒）。読めなければ null。 */
    val publishedEpochSeconds: Long?,
    val durationSeconds: Int?,
    val description: String?,
    val thumbnailUrl: String?,
) {
    fun toEpisode(
        show: FeedShow,
        position: EpisodePosition?,
    ): PodcastEpisode =
        PodcastEpisode(
            song =
                Song(
                    videoId = Feed.audioVideoId(audioUrl),
                    title = title,
                    artists = listOf(ArtistRef(show.title, null)),
                    album = null,
                    durationSeconds = durationSeconds,
                    thumbnailUrl = thumbnailUrl ?: show.thumbnailUrl,
                ),
            publishedText = publishedEpochSeconds?.let(FeedParser::dateText),
            durationText = durationSeconds?.let(FeedParser::durationText),
            description = description,
            progressPercent = position?.percent,
            showBrowseId = Feed.showBrowseId(show.feedUrl),
        )
}

object FeedParser {
    /** RSS 2.0（iTunes の拡張つき）を読む。番組として読めなければ null。音声の無い項目は落とす。 */
    fun parse(
        feedUrl: String,
        xml: String,
    ): FeedShow? {
        val channel = FeedXml.root(xml, "channel") ?: return null
        val items = channel.children("item")
        // 番組の欄は最初のエピソードより前だけを見る（エピソードの itunes:author などを拾わない）
        val head = XmlRange(xml, channel.start, items.firstOrNull()?.let { it.start - 1 } ?: channel.end).detached()
        val title = head.childText("title", skip = "image") ?: return null
        val image = head.child("itunes:image")?.attributes?.get("href")?.takeIf(Feed::isAllowedFeedUrl)
            ?: head.child("image")?.childText("url")?.takeIf(Feed::isAllowedFeedUrl)
        // ほとんどのフィードは新しい順。古い順（連続ものの番組）なら逆にする（端の2つの日付だけを見る）
        fun dateOf(range: XmlRange) = range.detached().childText("pubDate")?.let(::parseRfc822)
        val firstDate = items.asSequence().take(5).firstNotNullOfOrNull(::dateOf)
        val lastDate = items.asReversed().asSequence().take(5).firstNotNullOfOrNull(::dateOf)
        val ordered = if (firstDate != null && lastDate != null && firstDate < lastDate) items.asReversed() else items
        return FeedShow(
            feedUrl = feedUrl,
            title = title,
            author = head.childText("itunes:author") ?: head.childText("author") ?: head.childText("managingEditor"),
            description = (head.childText("itunes:summary") ?: head.childText("description"))?.let(::plainText)?.takeIf { it.isNotEmpty() },
            thumbnailUrl = image,
            items = ordered,
        )
    }

    /** エピソード1回分。音声・題が無ければ null。 */
    internal fun episode(range: XmlRange): FeedEpisode? {
        val item = range.detached()
        val audio = item.child("enclosure")?.attributes?.get("url")?.trim()?.let(::upgradeToHttps)?.takeIf(Feed::isAllowedFeedUrl) ?: return null
        return FeedEpisode(
            title = item.childText("title") ?: item.childText("itunes:title") ?: return null,
            audioUrl = audio,
            publishedEpochSeconds = item.childText("pubDate")?.let(::parseRfc822),
            durationSeconds = item.childText("itunes:duration")?.let(::parseDuration),
            description = (item.childText("itunes:summary") ?: item.childText("description") ?: item.childText("content:encoded"))?.let(::plainText)?.takeIf { it.isNotEmpty() },
            thumbnailUrl = item.child("itunes:image")?.attributes?.get("href")?.takeIf(Feed::isAllowedFeedUrl),
        )
    }

    /** 音声は https でだけ取る。http の URL は同じ場所の https を試す（多くの配信元は両方で返す）。 */
    private fun upgradeToHttps(url: String): String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /** 一覧で使う説明の長さ（数百回分の番組で、長い HTML を全部ほどかない）。 */
    private const val DESCRIPTION_SOURCE_LIMIT = 600

    /** 改行にするタグ（閉じの p・li・見出しと br）。 */
    private val BREAK_TAGS = setOf("br", "br/", "/p", "/li", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/div")

    /**
     * HTML の説明を、段落を残した素の文にする。
     * 正規表現を使わずに & と < の間を飛ぶ（debug の端末では、数百回分の説明の正規表現に十数秒かかった）。
     */
    fun plainText(html: String): String {
        val source = html.take(DESCRIPTION_SOURCE_LIMIT)
        val stripped = StringBuilder(source.length)
        var from = 0
        while (true) {
            val open = source.indexOf('<', from)
            if (open < 0) {
                stripped.append(source, from, source.length)
                break
            }
            stripped.append(source, from, open)
            val close = source.indexOf('>', open)
            if (close < 0) break
            val name = source.substring(open + 1, close).trim().substringBefore(' ').lowercase()
            if (name in BREAK_TAGS) stripped.append('\n')
            from = close + 1
        }
        // 行ごとに前後の空白を落とし、空行は2つまで
        val out = StringBuilder(stripped.length)
        var blank = 0
        for (line in FeedXml.decodeEntities(stripped.toString()).split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                blank++
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(trimmed)
            blank = 0
        }
        return out.toString()
    }

    /** `HH:MM:SS`・`MM:SS`・秒。 */
    fun parseDuration(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.trim().substringBefore('.').toIntOrNull() ?: return null }
        return numbers.fold(0) { acc, v -> acc * 60 + v }.takeIf { it > 0 }
    }

    /** 「1:02:03」「42:05」。言語に依らない形にする。 */
    fun durationText(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "$h:${m.pad2()}:${s.pad2()}" else "$m:${s.pad2()}"
    }

    /** 「2026/10/13」。 */
    fun dateText(epochSeconds: Long): String {
        val (y, m, d) = civilFromDays(floorDiv(epochSeconds, 86_400))
        return "$y/${m.pad2()}/${d.pad2()}"
    }

    private val WHITESPACE = Regex("\\s+")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** RFC 822 の日時（`Mon, 13 Oct 2026 10:00:00 +0900`）。曜日・秒・時差は省けてもよい。 */
    fun parseRfc822(text: String): Long? {
        val tokens = text.replace(',', ' ').trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val start = if (tokens.firstOrNull()?.firstOrNull()?.isLetter() == true) 1 else 0
        val day = tokens.getOrNull(start)?.toIntOrNull() ?: return null
        val month = tokens.getOrNull(start + 1)?.lowercase()?.take(3)?.let { MONTHS.indexOf(it) + 1 }?.takeIf { it > 0 } ?: return null
        val year = tokens.getOrNull(start + 2)?.toIntOrNull()?.let { if (it < 100) it + 2000 else it } ?: return null
        val time = tokens.getOrNull(start + 3)?.split(':')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
        val offsetMinutes = tokens.getOrNull(start + 4)?.let(::zoneOffsetMinutes) ?: 0
        val days = daysFromCivil(year, month, day)
        val seconds = (time.getOrNull(0) ?: 0) * 3600L + (time.getOrNull(1) ?: 0) * 60L + (time.getOrNull(2) ?: 0)
        return days * 86_400L + seconds - offsetMinutes * 60L
    }

    private fun zoneOffsetMinutes(zone: String): Int? {
        if (zone.length == 5 && (zone[0] == '+' || zone[0] == '-')) {
            val h = zone.substring(1, 3).toIntOrNull() ?: return null
            val m = zone.substring(3, 5).toIntOrNull() ?: return null
            return (h * 60 + m) * if (zone[0] == '-') -1 else 1
        }
        return when (zone.uppercase()) {
            "GMT", "UT", "UTC", "Z" -> 0
            "EST" -> -300
            "EDT" -> -240
            "CST" -> -360
            "CDT" -> -300
            "MST" -> -420
            "MDT" -> -360
            "PST" -> -480
            "PDT" -> -420
            "JST" -> 540
            else -> null
        }
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')

    private fun floorDiv(
        a: Long,
        b: Long,
    ): Long = (a / b).let { q -> if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q }

    // Howard Hinnant の days_from_civil / civil_from_days
    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era.toInt() * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }
}
