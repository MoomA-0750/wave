package xyz.mooma0750.wave.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader.getResource("fixtures/$name")).readText()

    @Test
    fun parsesShowAndEpisodesNewestFirst() {
        val show = requireNotNull(FeedParser.parse("https://example.com/feed.xml", fixture("feed_rss.xml")))
        assertEquals("テストの番組 & 友だち", show.title)
        assertEquals("wave", show.author)
        assertEquals("説明の一行目\n二行目", show.description)
        assertEquals("https://example.com/show.jpg", show.thumbnailUrl)
        assertEquals(listOf("第2回", "第1回"), show.allEpisodes.map { it.title })
        val newest = show.allEpisodes[0]
        assertEquals("https://example.com/ep2.mp3?x=1&y=2", newest.audioUrl)
        assertEquals(2525, newest.durationSeconds)
        assertEquals("https://example.com/ep2.jpg", newest.thumbnailUrl)
        // 説明はエスケープされた HTML で来る（タグを落とし、文の中の実体は戻す）
        assertEquals("エピソードの説明 1 < 2", newest.description)
        assertEquals(3723, show.allEpisodes[1].durationSeconds)
    }

    @Test
    fun episodesBecomeExternalSongs() {
        val show = requireNotNull(FeedParser.parse("https://example.com/feed.xml", fixture("feed_rss.xml")))
        val page = show.toPage(saved = true) { if (it.endsWith("ep1.mp3")) EpisodePosition.of(600, 3723, 0) else null }
        assertEquals("rss:https://example.com/feed.xml", page.browseId)
        val first = page.episodes[0]
        assertEquals("ext:https://example.com/ep2.mp3?x=1&y=2", first.song.videoId)
        assertTrue(Feed.isExternal(first.song.videoId))
        assertEquals("https://example.com/ep2.mp3?x=1&y=2", Feed.audioUrlOf(first.song.videoId))
        assertEquals("2026/10/13", first.publishedText)
        assertEquals("42:05", first.durationText)
        assertEquals("テストの番組 & 友だち", first.song.artists.single().name)
        assertEquals("2026/10/05", page.episodes[1].publishedText)
        assertEquals("1:02:03", page.episodes[1].durationText)
        assertEquals(16, page.episodes[1].progressPercent)
        assertNull(first.progressPercent)
    }

    @Test
    fun parsesRfc822WithOffsets() {
        assertEquals(1_791_853_200L, FeedParser.parseRfc822("Tue, 13 Oct 2026 01:00:00 GMT"))
        assertEquals(1_791_853_200L, FeedParser.parseRfc822("Tue, 13 Oct 2026 10:00:00 +0900"))
        assertEquals(1_791_853_200L, FeedParser.parseRfc822("13 Oct 2026 01:00 GMT"))
        assertNull(FeedParser.parseRfc822("not a date"))
    }

    @Test
    fun normalizesFeedUrls() {
        assertEquals("https://example.com/feed", Feed.normalizeFeedUrl(" feed://example.com/feed "))
        assertEquals("https://example.com/feed", Feed.normalizeFeedUrl("http://example.com/feed"))
        assertEquals("https://example.com/feed", Feed.normalizeFeedUrl("example.com/feed"))
        assertNull(Feed.normalizeFeedUrl("ftp://example.com/feed"))
        assertNull(Feed.normalizeFeedUrl("ポッドキャスト"))
    }

    @Test
    fun finishedNearTheEnd() {
        assertTrue(EpisodePosition.of(3700, 3723, 0).finished)
        assertEquals(0, EpisodePosition.of(3700, 3723, 0).resumeSeconds)
        assertEquals(100, EpisodePosition.of(3700, 3723, 0).percent)
        assertEquals(600, EpisodePosition.of(600, 3723, 0).resumeSeconds)
    }

    @Test
    fun readsDirectoryResults() {
        val body = """{"resultCount":2,"results":[{"collectionName":"番組A","artistName":"A","feedUrl":"https://a.example/feed","artworkUrl600":"https://a.example/a.jpg"},{"collectionName":"フィード無し"}]}"""
        val results = PodcastFeedClient.directoryResults(body)
        assertEquals(1, results.size)
        assertEquals("rss:https://a.example/feed", results[0].browseId)
        assertEquals("番組A", results[0].title)
    }

    @Test
    fun toleratesBrokenXml() {
        val show = FeedParser.parse("https://x.example/f", "<rss><channel><title>T</title><item><title>a<enclosure url='https://x.example/a.mp3'/></item>")
        assertEquals("T", show?.title)
    }

    @Test
    fun libraryKeepsNewestFirstAndRoundTrips() {
        val a = FeedSubscription("https://a.example/f", "A")
        val b = FeedSubscription("https://b.example/f", "B")
        val state = FeedLibraryState().subscribe(a).subscribe(b).subscribe(a).remember("https://a.example/1.mp3", EpisodePosition.of(10, 100, 5))
        assertEquals(listOf("A", "B"), state.subscriptions.map { it.title })
        val decoded = FeedLibraryState.decode(FeedLibraryState.encode(state))
        assertEquals(state, decoded)
        assertEquals(listOf("B"), state.unsubscribe(a.feedUrl).subscriptions.map { it.title })
        assertEquals("A2", state.refresh(a.copy(title = "A2")).subscriptions.first().title)
    }

    @Test
    fun pagesThroughLongFeedsAndReversesOldestFirst() {
        val items = (1..70).joinToString("") { n -> "<item><title>回$n</title><pubDate>${n.coerceAtMost(28)} Oct 2026 00:00:00 GMT</pubDate><enclosure url=\"https://x.example/$n.mp3\"/></item>" }
        val show = requireNotNull(FeedParser.parse("https://x.example/f", "<rss><channel><title>連続</title>$items</channel></rss>"))
        val first = show.toPage(saved = false)
        assertEquals(30, first.episodes.size)
        // 古い順のフィードは新しい順に並べ直す
        assertEquals("回70", first.episodes.first().song.title)
        val second = show.more(requireNotNull(first.continuation))
        assertEquals("回40", second.items.first().song.title)
        val third = show.more(requireNotNull(second.continuation))
        assertEquals(10, third.items.size)
        assertNull(third.continuation)
    }
}
