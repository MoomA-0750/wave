package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryParsersTest {
    @Test
    fun playlistFirstPageHasSongsTitleAndContinuation() {
        val page = MusicParsers.playlistPage(fixture("browse_playlist"))
        assertEquals(3, page.songs.size)
        assertTrue(page.songs.all { it.videoId.isNotBlank() && it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationSeconds != null })
        assertNotNull(page.title)
        assertNotNull(page.continuation)
    }

    @Test
    fun playlistContinuationPage() {
        val page = MusicParsers.playlistPage(fixture("browse_playlist_continuation"))
        assertEquals(3, page.songs.size)
    }

    @Test
    fun playlistCards() {
        val playlists = MusicParsers.playlists(fixture("browse_home_playlists"))
        assertEquals(3, playlists.size)
        assertTrue(playlists.all { it.playlistId.isNotBlank() && !it.playlistId.startsWith("VL") && it.title.isNotBlank() && it.thumbnailUrl != null })
    }

    @Test
    fun loggedOutLibraryIsASignInPrompt() {
        assertTrue(MusicParsers.isSignInPrompt(fixture("browse_library_logged_out")))
        assertTrue(MusicParsers.isSignInPrompt(fixture("browse_liked_songs_logged_out")))
        assertFalse(MusicParsers.isSignInPrompt(fixture("browse_playlist")))
        assertFalse(MusicParsers.isSignInPrompt(fixture("browse_home_playlists")))
    }

    @Test
    fun rendererNamesCarryNoValues() {
        val names = MusicParsers.rendererNames(fixture("browse_playlist"))
        assertTrue("musicPlaylistShelfRenderer" in names)
        assertTrue(names.none { it.contains(" ") || it.startsWith("http") })
    }

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText()) as JsonObject
}
