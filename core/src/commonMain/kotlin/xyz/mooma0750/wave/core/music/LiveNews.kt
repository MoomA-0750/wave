package xyz.mooma0750.wave.core.music

/** アーティストの投稿のうち、ライブ・ツアー・出演の告知らしいもの。 */
object LiveNews {
    private val keywords =
        Regex(
            "ライブ|ﾗｲﾌﾞ|ツアー|公演|ワンマン|チケット|開催決定|出演決定|フェス|ドーム|アリーナ|ホール|\\bLIVE\\b|\\bTOUR\\b|\\bconcert|\\btickets?\\b|\\bfestival\\b",
            RegexOption.IGNORE_CASE,
        )

    fun isLiveRelated(text: String): Boolean = keywords.containsMatchIn(text)

    /** 告知らしい投稿だけ（新しい順のまま）。 */
    fun of(posts: List<ArtistPost>): List<ArtistPost> = posts.filter { isLiveRelated(it.text) }
}
