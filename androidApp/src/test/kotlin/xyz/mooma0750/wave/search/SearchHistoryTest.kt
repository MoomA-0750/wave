package xyz.mooma0750.wave.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    @Test
    fun reAddingWithoutAnImageKeepsTheEarlierImage() {
        val rich = SearchHistoryEntry.Artist("YOASOBI", "UC1", "Artist", "https://img/a")
        // 曲のメニューから同じアーティストを開くと、画像の無い記録が来る
        val entries = SearchHistory.add(listOf(query("x"), rich), SearchHistoryEntry.Artist("YOASOBI", "UC1"))
        assertEquals(listOf(rich, query("x")), entries)
        val song = SearchHistoryEntry.Song("v1", "夜に駆ける", "YOASOBI", "https://img/s")
        assertEquals(listOf(song), SearchHistory.add(listOf(song), SearchHistoryEntry.Song("v1", "夜に駆ける")))
    }

    @Test
    fun songsAreKeptByVideoId() {
        var entries = SearchHistory.add(emptyList(), SearchHistoryEntry.Song("v1", "a"))
        entries = SearchHistory.add(entries, SearchHistoryEntry.Song("v2", "b"))
        entries = SearchHistory.add(entries, SearchHistoryEntry.Song("v1", "a"))
        assertEquals(listOf("v1", "v2"), entries.map { (it as SearchHistoryEntry.Song).videoId })
        assertEquals(entries, SearchHistory.add(entries, SearchHistoryEntry.Song("", "c")))
    }

    @Test
    fun savedDataFromTheEarlierVersionStillLoads() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(SearchHistoryEntry.serializer())
        val old = """[{"type":"query","text":"a"},{"type":"artist","name":"n","browseId":"UC1"},{"type":"album","title":"t","browseId":"MPREb_1"}]"""
        assertEquals(
            listOf(query("a"), SearchHistoryEntry.Artist("n", "UC1"), SearchHistoryEntry.Album("t", "MPREb_1")),
            json.decodeFromString(serializer, old),
        )
        val now = listOf(SearchHistoryEntry.Song("v1", "s", "a", "https://img"), SearchHistoryEntry.Artist("n", "UC1", null, "https://img2"))
        assertEquals(now, json.decodeFromString(serializer, json.encodeToString(serializer, now)))
    }
}
