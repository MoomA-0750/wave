package xyz.mooma0750.wave.core.music

/**
 * 気分とジャンルに、アプリで足す Lo-Fi のタイル（日本の YouTube Music には無い）。
 * 押すと YouTube のジャンルのページの代わりに、ライブ配信と lofi のプレイリストを並べたページを組む。
 * Windows・Mac・iOS も同じ値・同じ並びにする。
 */
object LofiGenre {
    /** タイルの params の代わりの印（YouTube の params とぶつからない形）。 */
    const val PARAMS = "wave:lofi"
    const val TITLE = "Lo-Fi"

    /** タイルの色（ARGB）。落ち着いた紫。 */
    const val COLOR = 0xFF8E7CC3

    const val LIVE_QUERY = "lofi hip hop radio"
    const val PLAYLIST_QUERY = "lofi"
    const val LIVE_TITLE = "ライブ配信"
    const val FEATURED_TITLE = "YouTube Music のプレイリスト"
    const val COMMUNITY_TITLE = "みんなのプレイリスト"
    const val MAX_ITEMS = 12

    val category: MoodCategory get() = MoodCategory(TITLE, PARAMS, COLOR)

    /**
     * ジャンルの格子の先頭に足す。ジャンルの格子は見出しで探し（「ジャンル」「Genres」）、見つからなければ最後の格子にする
     * （応答は「ムードとアクティビティ」→「ジャンル」の順）。格子が無ければそのまま返す。
     */
    fun addTo(sections: List<MoodSection>): List<MoodSection> {
        if (sections.isEmpty() || sections.any { section -> section.categories.any { it.params == PARAMS } }) return sections
        val index = sections.indexOfFirst { GENRE_TITLE.containsMatchIn(it.title) }.takeIf { it >= 0 } ?: sections.lastIndex
        return sections.mapIndexed { i, section -> if (i == index) section.copy(categories = listOf(category) + section.categories) else section }
    }

    /** YouTube Music のおすすめのプレイリストのうち、lofi らしいもの。1つも無ければ上から順に。 */
    fun featured(playlists: List<PlaylistSummary>): List<PlaylistSummary> =
        playlists.filter { LOFI_TITLE.containsMatchIn(it.title) }.take(MAX_ITEMS).ifEmpty { playlists.take(MAX_ITEMS) }

    internal fun shelf(
        title: String,
        items: List<HomeItem>,
    ): HomeShelf? = items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title, strapline = null, items = it) }

    private val GENRE_TITLE = Regex("ジャンル|genre", RegexOption.IGNORE_CASE)
    private val LOFI_TITLE = Regex("lo-?fi|ローファイ|jazzhop|chill|beats", RegexOption.IGNORE_CASE)
}
