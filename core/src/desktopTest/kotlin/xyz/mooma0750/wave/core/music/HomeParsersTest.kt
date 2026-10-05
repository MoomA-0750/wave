package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ホームの棚と新作（匿名で取得した公開データ。ログイン中の棚は同じ部品の組み合わせ）。 */
class HomeParsersTest {
    @Test
    fun homeReadsCarouselShelvesAndSkipsOthers() {
        val page = MusicParsers.homePage(Fixtures.json("browse_home"))
        // 3つめの棚（musicTastebuilderShelfRenderer）は読まない
        assertEquals(2, page.shelves.size)
        page.shelves.forEach { shelf ->
            assertTrue(shelf.title.isNotBlank())
            assertEquals(10, shelf.items.size)
            assertTrue(shelf.items.all { it is HomeItem.PlaylistItem })
        }
        val playlist = (page.shelves.first().items.first() as HomeItem.PlaylistItem).playlist
        assertTrue(!playlist.playlistId.startsWith("VL"))
        assertTrue(playlist.thumbnailUrl.orEmpty().startsWith("https://"))
        assertNotNull(page.continuation)
        // 気分の絞り込み（見出しの上のチップ）
        assertEquals(10, page.chips.size)
        assertEquals("Sleep", page.chips.first().title)
        assertTrue(page.chips.all { it.params.isNotBlank() })
    }

    @Test
    fun cardKindsComeFromPageTypeAndWatchEndpoint() {
        fun card(endpoint: String) =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"もう一度聴く"}]}}},
                "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"t"}]},"subtitle":{"runs":[{"text":"a","navigationEndpoint":{"browseEndpoint":{"browseId":"UCa","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]},
                "navigationEndpoint":$endpoint}}]}}]}}}}]}}}""",
            ) as JsonObject
        fun browse(id: String, type: String) = """{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}"""
        fun only(endpoint: String) = MusicParsers.homePage(card(endpoint)).shelves.single().items.single()
        val song = only("""{"watchEndpoint":{"videoId":"v1"}}""") as HomeItem.SongItem
        assertEquals("v1", song.song.videoId)
        assertEquals(listOf(ArtistRef("a", "UCa")), song.song.artists)
        assertEquals("MPREb_x", (only(browse("MPREb_x", "MUSIC_PAGE_TYPE_ALBUM")) as HomeItem.AlbumItem).album.browseId)
        assertEquals("UCx", (only(browse("UCx", "MUSIC_PAGE_TYPE_ARTIST")) as HomeItem.ArtistItem).artist.browseId)
        assertEquals("PLx", (only(browse("VLPLx", "MUSIC_PAGE_TYPE_PLAYLIST")) as HomeItem.PlaylistItem).playlist.playlistId)
        assertTrue(MusicParsers.homePage(card(browse("x", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"))).shelves.isEmpty())
    }

    @Test
    fun newReleasesAreAlbums() {
        val albums = MusicParsers.newReleases(Fixtures.json("browse_new_releases"))
        assertEquals(6, albums.size)
        assertEquals("MPREb_zjXWoD4kzS1", albums.first().browseId)
        assertTrue(albums.all { it.title.isNotBlank() && it.thumbnailUrl.orEmpty().startsWith("https://") })
    }

    @Test
    fun unknownShapeIsEmpty() {
        assertEquals(HomePage(emptyList(), null), MusicParsers.homePage(JsonObject(emptyMap())))
    }

    @Test
    fun moodsAreTilesGroupedBySection() {
        val sections = MusicParsers.moods(Fixtures.json("browse_moods"))
        assertEquals(listOf("Moods & moments", "Genres"), sections.map { it.title })
        assertEquals(listOf(11, 27), sections.map { it.categories.size })
        val chill = sections.first().categories.first()
        assertEquals("Chill", chill.title)
        assertEquals("ggMPOg1uX1JOQWZFeDByc2Jm", chill.params)
        assertEquals(4288988671L, chill.color)
    }

    @Test
    fun moodCategoryPageIsShelves() {
        val page = MusicParsers.homePage(Fixtures.json("browse_mood_category"))
        assertEquals(2, page.shelves.size)
        assertTrue(page.shelves.all { it.items.size == 4 })
    }

    @Test
    fun libraryArtistRowsOpenTheChannel() {
        // ライブラリのアーティストの行（実アカウントで確かめた形: MUSIC_PAGE_TYPE_LIBRARY_ARTIST と MPLA + チャンネルの ID。値は合成）
        val root =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"n"}]}}}],
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPLAUCx","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_LIBRARY_ARTIST"}}}}}}]}}]}}}}]}}}""",
            ) as JsonObject
        val artist = (MusicParsers.libraryCards(root).single() as HomeItem.ArtistItem).artist
        assertEquals("UCx", artist.browseId)
        assertEquals("n", artist.name)
    }
}
