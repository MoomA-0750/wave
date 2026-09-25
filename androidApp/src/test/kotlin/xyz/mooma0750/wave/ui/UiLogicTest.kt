package xyz.mooma0750.wave.ui

import org.junit.Test
import xyz.mooma0750.wave.core.music.HistorySection
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.library.HistoryRow
import xyz.mooma0750.wave.library.HistoryUiState
import kotlin.test.assertEquals

class UiLogicTest {
    @Test
    fun durations() {
        assertEquals("0:00", Durations.ofSeconds(0))
        assertEquals("0:59", Durations.ofSeconds(59))
        assertEquals("1:00", Durations.ofSeconds(60))
        assertEquals("60:00", Durations.ofSeconds(3600))
        assertEquals("3:05", Durations.ofMillis(185_999))
    }

    private fun song(id: String) = Song(id, id, emptyList(), null, 200, null)

    @Test
    fun historyRowsCarryThePlayIndexWithoutHeadings() {
        val state = HistoryUiState(listOf(HistorySection("今日", listOf(song("a"), song("b"))), HistorySection(null, listOf(song("c"))), HistorySection("先週", listOf(song("d")))))
        assertEquals(
            listOf(HistoryRow.Heading("今日"), HistoryRow.Item(song("a"), 0), HistoryRow.Item(song("b"), 1), HistoryRow.Item(song("c"), 2), HistoryRow.Heading("先週"), HistoryRow.Item(song("d"), 3)),
            state.rows,
        )
        // 番号は songs の並びと一致する
        state.rows.filterIsInstance<HistoryRow.Item>().forEach { assertEquals(it.song, state.songs[it.songIndex]) }
    }
}
