package xyz.mooma0750.wave.search

import org.junit.Test
import kotlin.test.assertEquals

class SearchHistoryTest {
    private fun query(text: String) = SearchHistoryEntry.Query(text)

    @Test
    fun theNewestComesFirstAndTheSameEntryMovesUp() {
        var entries = SearchHistory.add(emptyList(), query("a"))
        entries = SearchHistory.add(entries, query("b"))
        assertEquals(listOf(query("b"), query("a")), entries)
        entries = SearchHistory.add(entries, query("a"))
        assertEquals(listOf(query("a"), query("b")), entries)
    }

    @Test
    fun theSameWordIsNotKeptTwiceEvenWithDifferentCaseOrSpaces() {
        var entries = SearchHistory.add(emptyList(), query("Hello"))
        entries = SearchHistory.add(entries, query("  hello  "))
        assertEquals(listOf(query("hello")), entries)
        // 空白だけの語は残さない
        assertEquals(entries, SearchHistory.add(entries, query("   ")))
    }

    @Test
    fun artistsAndAlbumsAreKeptByTheirId() {
        val artist = SearchHistoryEntry.Artist("名前", "UC1")
        val renamed = SearchHistoryEntry.Artist("別の名前", "UC1")
        val album = SearchHistoryEntry.Album("アルバム", "MPRE1")
        var entries = SearchHistory.add(emptyList(), artist)
        entries = SearchHistory.add(entries, album)
        entries = SearchHistory.add(entries, renamed)
        assertEquals(listOf(renamed, album), entries)
        // ID が無いものは残さない
        assertEquals(entries, SearchHistory.add(entries, SearchHistoryEntry.Artist("名前", "")))
    }

    @Test
    fun itKeepsAtMostThirtyEntries() {
        val entries = (1..40).fold(emptyList<SearchHistoryEntry>()) { acc, i -> SearchHistory.add(acc, query("q$i")) }
        assertEquals(SearchHistory.MAX_ENTRIES, entries.size)
        assertEquals(query("q40"), entries.first())
        assertEquals(query("q11"), entries.last())
    }

    @Test
    fun mergingKeepsTheNewerSideAndDropsDuplicates() {
        // 保存を読む前に足した分（新しい）を落とさない
        val newer = listOf(query("b"))
        val older = listOf(query("a"), query("b"))
        assertEquals(listOf(query("b"), query("a")), SearchHistory.merge(newer, older))
    }

    @Test
    fun removingTakesOutJustThatEntry() {
        val entries = listOf(query("a"), query("b"))
        assertEquals(listOf(query("a")), SearchHistory.remove(entries, query("b")))
    }
}
