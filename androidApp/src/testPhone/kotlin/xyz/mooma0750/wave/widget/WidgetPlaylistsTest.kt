package xyz.mooma0750.wave.widget

import org.junit.Test
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import kotlin.test.assertEquals

class WidgetPlaylistsTest {
    private fun playlist(id: String) = PlaylistSummary(id, id, null, null)

    @Test
    fun likedSongsFirstThenRecentlyPlayedThenAddedOrder() {
        val arranged =
            WidgetPlaylists.arrange(
                listOf(playlist("a"), playlist("b"), playlist(MusicRepository.LIKED_SONGS), playlist("c"), playlist("d")),
                recentKeys = listOf("p-c", "al-x", "p-a"),
                likedTitle = "Liked",
            )
        assertEquals(listOf(MusicRepository.LIKED_SONGS, "c", "a", "b", "d"), arranged.map { it.id })
    }

    @Test
    fun likedSongsIsAddedWhenTheLibraryLacksIt() {
        val arranged = WidgetPlaylists.arrange(listOf(playlist("a")), emptyList(), likedTitle = "Liked")
        assertEquals(listOf(MusicRepository.LIKED_SONGS to "Liked", "a" to "a"), arranged.map { it.id to it.title })
    }
}
