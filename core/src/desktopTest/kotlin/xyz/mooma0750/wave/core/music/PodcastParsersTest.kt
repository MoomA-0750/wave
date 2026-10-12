package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ポッドキャストの検索・番組のページ（匿名で取得した公開データ）。 */
class PodcastParsersTest {
    @Test
    fun podcastSearchReadsShows() {
        val page = MusicParsers.searchPodcasts(Fixtures.json("search_podcasts"))
        assertEquals(8, page.items.size)
        val first = page.items.first()
        assertEquals("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.browseId)
        assertEquals("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.playlistId)
        assertEquals("Rebuild", first.title)
        assertEquals("Rebuild Podcast", first.author)
        assertTrue(first.thumbnailUrl.orEmpty().startsWith("https://"))
        assertTrue(page.items.all { it.browseId.startsWith("MPSP") })
        assertNotNull(page.continuation)
    }

    @Test
    fun episodeSearchTakesDateAndShowNotArtist() {
        val page = MusicParsers.searchEpisodes(Fixtures.json("search_episodes"))
        assertEquals(8, page.items.size)
        val first = page.items.first()
        assertEquals("ZVIAE__9jhY", first.song.videoId)
        assertTrue(first.song.title.startsWith("How To Rebuild Your Character"))
        // 日付をアーティストと取り違えず、番組をアーティストの代わりにする（番組のページはアーティストとしては開かない）
        assertEquals(listOf(ArtistRef("Munroe Global Available Teachings", null)), first.song.artists)
        assertEquals("2018/04/28", first.publishedText)
        assertEquals("MPSPPLiSxXTy3ZWc52InDNx3QUGeewlp3jNmRW", first.showBrowseId)
        assertEquals(MusicVideoType.Other, first.song.kind)
        assertTrue(page.items.all { it.showBrowseId.orEmpty().startsWith("MPSP") && it.publishedText != null })
        assertNotNull(page.continuation)
    }

    @Test
    fun podcastPageReadsHeaderAndEpisodes() {
        val page = assertNotNull(MusicParsers.podcastPage(Fixtures.json("browse_podcast"), "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv"))
        assertEquals("Rebuild", page.title)
        assertEquals("Rebuild Podcast", page.author)
        assertTrue(page.description.orEmpty().startsWith("ウェブ開発、プログラミング"))
        assertTrue(page.thumbnailUrl.orEmpty().startsWith("https://"))
        assertEquals("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", page.playlistId)
        // ログインしていない応答では、保存しているかは分からない
        assertNull(page.saved)
        assertEquals(8, page.episodes.size)
        val first = page.episodes.first()
        assertEquals("mLtB1Fr7o64", first.song.videoId)
        assertEquals("433: That Meeting Was So Jev (らんぶる)", first.song.title)
        assertEquals(listOf(ArtistRef("Rebuild", null)), first.song.artists)
        assertEquals("6 日前", first.publishedText)
        assertEquals("1 時間 37 分", first.durationText)
        assertTrue(first.description.orEmpty().startsWith("らんぶるさんをゲストに迎えて"))
        assertNull(first.progressPercent)
        assertTrue(first.song.thumbnailUrl.orEmpty().startsWith("https://"))
        assertNotNull(page.continuation)
    }

    @Test
    fun podcastContinuationReadsEpisodes() {
        val show = ArtistRef("Rebuild", null)
        val page = MusicParsers.podcastEpisodes(Fixtures.json("browse_podcast_continuation"), show, "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv")
        assertEquals(4, page.items.size)
        val first = page.items.first()
        assertEquals("h61yHZbIQvk", first.song.videoId)
        assertEquals("413: Always Fully Hyped (yuka)", first.song.title)
        assertEquals(listOf(show), first.song.artists)
        assertEquals("2025/09/08", first.publishedText)
        assertEquals("1 時間 48 分", first.durationText)
        assertNull(page.continuation)
    }

    @Test
    fun libraryPodcastsReadCardsAndRows() {
        // ログインが要るので、検索の行と同じ形のカードで確かめる（番組以外のカードは除く）
        val card =
            """{"contents":{"gridRenderer":{"items":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"New episodes"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDPN","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Rebuild"}]},"subtitle":{"runs":[{"text":"Rebuild Podcast"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}}
            ]}}}"""
        val shows = MusicParsers.libraryPodcasts(kotlinx.serialization.json.Json.parseToJsonElement(card) as JsonObject)
        assertEquals(listOf(PodcastSummary("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", "Rebuild", "Rebuild Podcast", null)), shows)
        assertEquals(8, MusicParsers.libraryPodcasts(Fixtures.json("search_podcasts")).size)
    }

    @Test
    fun unknownShapesGiveEmptyResults() {
        val empty = JsonObject(emptyMap())
        assertEquals(SearchPage<PodcastSummary>(emptyList(), null), MusicParsers.searchPodcasts(empty))
        assertEquals(SearchPage<PodcastEpisode>(emptyList(), null), MusicParsers.searchEpisodes(empty))
        assertNull(MusicParsers.podcastPage(empty, "MPSPx"))
        assertTrue(MusicParsers.libraryPodcasts(empty).isEmpty())
    }
}
