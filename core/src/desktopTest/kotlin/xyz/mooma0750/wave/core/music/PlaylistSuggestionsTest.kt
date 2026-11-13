package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistSuggestionsTest {
    @Test
    fun firstPageKeepsTheSuggestionsContinuationApartFromTheSongs() {
        val page = MusicParsers.playlistPage(fixture("browse_playlist"))
        assertNotNull(page.suggestionsContinuation)
        assertNotEquals(page.continuation, page.suggestionsContinuation)
        assertNull(MusicParsers.playlistPage(fixture("browse_playlist_continuation")).suggestionsContinuation)
    }

    @Test
    fun suggestionsShelfAndReloadAreRead() {
        val first = assertNotNull(MusicParsers.playlistSuggestions(fixture("browse_playlist_suggestions")))
        assertEquals(7, first.songs.size)
        assertTrue(first.songs.all { it.videoId.isNotEmpty() && it.title.isNotEmpty() })
        assertNotNull(first.reloadContinuation)
        val again = assertNotNull(MusicParsers.playlistSuggestions(fixture("browse_playlist_suggestions_reload")))
        assertEquals(7, again.songs.size)
        assertNotNull(again.reloadContinuation)
        assertNotEquals(first.songs.map { it.videoId }, again.songs.map { it.videoId })
        // 候補の棚でない応答
        assertNull(MusicParsers.playlistSuggestions(fixture("browse_playlist")))
    }

    @Test
    fun songsToSaveKeepOrderAndDropEpisodesLivesAndRepeats() {
        fun song(
            id: String,
            live: Boolean = false,
        ) = Song(id, id, emptyList(), null, null, null, isLive = live)
        val queue = listOf(song("a"), song("b"), song(Feed.audioVideoId("https://example.com/e.mp3")), song("live", live = true), song("a"), song("c"))
        assertEquals(listOf("a", "b", "c"), songsToSave(queue).map { it.videoId })
        assertEquals(emptyList(), songsToSave(emptyList()))
    }

    private fun fixture(name: String): JsonObject = xyz.mooma0750.wave.core.Fixtures.json(name)
}
