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

    @Test
    fun searchMatchesTitleAndSubtitleIgnoringCaseTogetherWithTheChips() {
        val mix = LibraryEntry.Playlist(PlaylistSummary("PLm", "Morning Mix", "Playlist • Someone", null))
        val album = LibraryEntry.Album(AlbumSummary("MPREb_x", "Blue", "Album • Mixer", null))
        val withSearch = state.copy(entries = listOf(b, a, c, mix, album))
        assertEquals(listOf(mix, album), LibraryOrder.arrange(withSearch, null, LibrarySort.Added, "MIX"))
        assertEquals(listOf(album), LibraryOrder.arrange(withSearch, LibraryFilter.Albums, LibrarySort.Added, "mix"))
        // 語はすべて含まれていれば残す（順は問わない）
        assertEquals(listOf(mix), LibraryOrder.arrange(withSearch, null, LibrarySort.Added, "someone morning"))
        assertEquals(listOf(a), LibraryOrder.arrange(withSearch, LibraryFilter.Artists, LibrarySort.Added, "  a "))
        assertEquals(LibraryOrder.arrange(withSearch, null, LibrarySort.Added), LibraryOrder.arrange(withSearch, null, LibrarySort.Added, " "))
    }
}
