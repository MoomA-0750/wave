package xyz.mooma0750.wave.core.ai

import xyz.mooma0750.wave.core.lyrics.LyricLine
import xyz.mooma0750.wave.core.lyrics.Lyrics
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class AiTextTest {
    @Test
    fun syncedLyricsGetTimestampsAndSkipBlankLines() {
        val lyrics = Lyrics.Synced(listOf(LyricLine(12_340, "first"), LyricLine(60_000, " "), LyricLine(83_999, " second ")), null)
        assertEquals("[00:12] first\n[01:23] second", AiText.lyrics(lyrics))
    }

    @Test
    fun plainLyricsStayAsIs() {
        assertEquals("a\n\nb", AiText.lyrics(Lyrics.Plain("a\n\nb", null)))
    }

    @Test
    fun stampCountsMinutesPastAnHour() {
        assertEquals("00:00", AiText.stamp(0))
        assertEquals("61:05", AiText.stamp(3_665_900))
    }

    @Test
    fun findsTimestampsInAnswer() {
        val text = "In [01:23]「x」 and **[2:05]** but not [1:75] or [abc]"
        val found = AiText.timestamps(text)
        assertEquals(listOf(83_000L, 125_000L), found.map { it.positionMs })
        assertEquals(listOf("01:23", "2:05"), found.map { it.label })
        assertEquals("[01:23]", text.substring(found[0].range))
    }

    @Test
    fun historyKeepsNewestFirstAndCaps() {
        var list = emptyList<AiConversation>()
        repeat(AiHistory.MAX + 5) { i -> list = AiHistory.upsert(list, conversation("c$i", updated = i.toLong())) }
        assertEquals(AiHistory.MAX, list.size)
        assertEquals("c${AiHistory.MAX + 4}", list.first().id)
        // 古い会話を続けると先頭へ
        list = AiHistory.upsert(list, conversation("c10", updated = 1_000))
        assertEquals("c10", list.first().id)
        assertEquals(AiHistory.MAX, list.size)
        assertEquals(AiHistory.MAX - 1, AiHistory.remove(list, "c10").size)
    }

    @Test
    fun historyRoundTripsAndTitles() {
        val song = Song("vid1", "Title", listOf(ArtistRef("A", "UC1")), null, 200, "https://example.com/t.jpg")
        val messages =
            listOf(
                AiSavedMessage(AiSavedMessage.USER, "  what\n does it   mean? "),
                AiSavedMessage(AiSavedMessage.ASSISTANT, "At [00:12]", videoId = "vid1"),
                AiSavedMessage(AiSavedMessage.SONGS, songs = listOf(song)),
            )
        assertEquals("what does it mean?", AiHistory.title(messages))
        val list = listOf(AiConversation("x", AiHistory.title(messages), 1, 2, messages))
        assertEquals(list, AiHistory.decode(AiHistory.encode(list)))
        assertEquals(emptyList(), AiHistory.decode("not json"))
        assertEquals(emptyList(), AiHistory.decode("""{"version":99,"conversations":[]}"""))
    }

    private fun conversation(
        id: String,
        updated: Long,
    ) = AiConversation(id, id, 0, updated, listOf(AiSavedMessage(AiSavedMessage.USER, id)))
}
