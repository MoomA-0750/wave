package xyz.mooma0750.wave.core.music

/** アーティストの投稿のうち、ライブ・ツアー・出演の告知らしいもの。 */
object LiveNews {
    private val keywords =
        Regex(
            "ライブ|ﾗｲﾌﾞ|ツアー|公演|ワンマン|チケット|開催決定|出演決定|フェス|ドーム|アリーナ|ホール|\\bLIVE\\b|\\bTOUR\\b|\\bconcert|\\btickets?\\b|\\bfestival\\b",
            RegexOption.IGNORE_CASE,
        )

    // 「1 年前」「2 years ago」など、年で数える古さ（1年以上前）
    private val yearsAgo = Regex("\\d+\\s*(年前|years? ago)", RegexOption.IGNORE_CASE)

    fun isLiveRelated(text: String): Boolean = keywords.containsMatchIn(text)

    /** 1年以内の投稿か（投稿の時期の表示が無ければ、分からないので残す）。 */
    fun isRecent(publishedText: String?): Boolean = publishedText == null || !yearsAgo.containsMatchIn(publishedText)

    /** 1年以内の、告知らしい投稿だけ（新しい順のまま。何年も前の告知は終わっているので出さない）。 */
    fun of(posts: List<ArtistPost>): List<ArtistPost> = posts.filter { isRecent(it.publishedText) && isLiveRelated(it.text) }
}
