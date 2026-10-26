package xyz.mooma0750.wave.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LrcTest {
    @Test
    fun readsTimesAndSkipsTagsAndUntimedLines() {
        val lines =
            Lrc.parse(
                """
                [ar:Someone]
                [ti:Song]
                [00:00.90] 一行目
                [00:05.5]二行目
                時刻の無い行
                [01:02.345]三行目
                """.trimIndent(),
            )
        assertEquals(listOf(LyricLine(900, "一行目"), LyricLine(5_500, "二行目"), LyricLine(62_345, "三行目")), lines)
    }

    @Test
    fun repeatedLineGetsEachTimeInOrder() {
        val lines = Lrc.parse("[00:10.00][00:30.00]サビ\n[00:20.00]間")
        assertEquals(listOf(10_000L to "サビ", 20_000L to "間", 30_000L to "サビ"), lines.map { it.startMs to it.text })
    }

    @Test
    fun offsetMovesEveryLineEarlierAndBlankLinesStay() {
        val lines = Lrc.parse("[offset:+500]\n[00:01.00]a\n[00:00.20]\n[00:03]b")
        assertEquals(listOf(LyricLine(0, ""), LyricLine(500, "a"), LyricLine(2_500, "b")), lines)
    }

    @Test
    fun currentIndexFollowsPositionWithASmallLead() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, LyricsTiming.currentIndex(lines, 0))
        assertEquals(0, LyricsTiming.currentIndex(lines, 1_000 - LyricsTiming.LEAD_MS))
        assertEquals(0, LyricsTiming.currentIndex(lines, 4_000))
        assertEquals(1, LyricsTiming.currentIndex(lines, 4_900))
        assertEquals(2, LyricsTiming.currentIndex(lines, 600_000))
        assertEquals(-1, LyricsTiming.currentIndex(emptyList(), 1_000))
    }

    @Test
    fun currentAndNextSkipsReadingsAndMarksBlankLines() {
        val lines = listOf(LyricLine(1_000, "原文"), LyricLine(1_000, "よみ"), LyricLine(3_000, " "), LyricLine(5_000, "次"))
        assertEquals(null to "原文", LyricsTiming.currentAndNext(lines, 0))
        assertEquals("原文" to "♪", LyricsTiming.currentAndNext(lines, 1_500))
        assertEquals("♪" to "次", LyricsTiming.currentAndNext(lines, 3_500))
        assertEquals("次" to null, LyricsTiming.currentAndNext(lines, 9_000))
    }
}
