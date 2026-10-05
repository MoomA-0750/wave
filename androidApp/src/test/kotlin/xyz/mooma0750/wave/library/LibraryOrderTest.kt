package xyz.mooma0750.wave.library

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.mooma0750.wave.core.music.AlbumSummary
import xyz.mooma0750.wave.core.music.ArtistSummary
import xyz.mooma0750.wave.core.music.PlaylistSummary

class LibraryOrderTest {
    private val b = LibraryEntry.Playlist(PlaylistSummary("PLb", "b", null, null))
    private val a = LibraryEntry.Artist(ArtistSummary("UCa", "A", null, null))
    private val c = LibraryEntry.Album(AlbumSummary("MPREb_c", "c", null, null))
    private val state = LibraryTabUiState(listOf(b, a, c), recentKeys = listOf("al-MPREb_c", "p-PLb"), loading = false)

    @Test
    fun recentsPutsRecentlyPlayedFirstAndKeepsTheRestInOrder() {
        assertEquals(listOf(c, b, a), LibraryOrder.arrange(state, null, LibrarySort.Recents))
    }

    @Test
    fun otherSortsAndFilters() {
        assertEquals(listOf(b, a, c), LibraryOrder.arrange(state, null, LibrarySort.Added))
        assertEquals(listOf(a, b, c), LibraryOrder.arrange(state, null, LibrarySort.Alphabetical))
        assertEquals(listOf(a), LibraryOrder.arrange(state, LibraryFilter.Artists, LibrarySort.Recents))
        assertEquals(listOf(c), LibraryOrder.arrange(state, LibraryFilter.Albums, LibrarySort.Added))
    }
}
