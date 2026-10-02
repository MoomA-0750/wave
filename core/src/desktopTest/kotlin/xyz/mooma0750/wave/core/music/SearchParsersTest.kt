package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 検索の候補・アルバム・アーティストの検索（匿名で取得した公開データ）。 */
class SearchParsersTest {
    @Test
    fun suggestionsSplitQueriesAndRowsByKind() {
        val suggestions = MusicParsers.searchSuggestions(Fixtures.json("search_suggestions"))
        assertEquals("yoasobi", suggestions.queries.first())
        assertEquals(6, suggestions.queries.size)
        assertEquals(listOf("UCI6B8NkZKqlFWoiC_xE-hzA"), suggestions.artists.map { it.browseId })
        assertEquals("YOASOBI", suggestions.artists.single().name)
        assertEquals(4, suggestions.songs.size)
        assertEquals("夜に駆ける", suggestions.songs.first().title)
        assertTrue(suggestions.songs.all { it.videoId.isNotBlank() && it.thumbnailUrl.orEmpty().startsWith("https://") })
        assertTrue(suggestions.albums.isEmpty())
    }

    @Test
    fun albumSearchReadsCardsAndContinuation() {
        val page = MusicParsers.searchAlbums(Fixtures.json("search_albums"))
        assertTrue(page.items.size >= 10)
        val first = page.items.first()
        assertEquals("MPREb_hqiB0KumHYT", first.browseId)
        assertEquals("THE BOOK", first.title)
        assertEquals("EP • YOASOBI • 2021", first.subtitle)
        assertTrue(first.thumbnailUrl.orEmpty().startsWith("https://"))
        assertTrue(page.items.all { it.browseId.startsWith("MPREb_") })
        assertNotNull(page.continuation)
    }

    @Test
    fun artistSearchReadsRows() {
        val page = MusicParsers.searchArtists(Fixtures.json("search_artists"))
        assertEquals(listOf("YOASOBI", "Lilas", "Ayase"), page.items.take(3).map { it.name })
        assertTrue(page.items.all { it.browseId.startsWith("UC") })
        assertNull(page.continuation)
    }

    @Test
    fun unknownShapesGiveEmptyResults() {
        val empty = JsonObject(emptyMap())
        assertTrue(MusicParsers.searchSuggestions(empty).isEmpty)
        assertEquals(SearchPage<AlbumSummary>(emptyList(), null), MusicParsers.searchAlbums(empty))
        assertEquals(SearchPage<ArtistSummary>(emptyList(), null), MusicParsers.searchArtists(empty))
    }
}
