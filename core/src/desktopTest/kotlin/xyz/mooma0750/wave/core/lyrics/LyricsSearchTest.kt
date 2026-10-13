package xyz.mooma0750.wave.core.lyrics

import kotlinx.coroutines.runBlocking
import xyz.mooma0750.wave.core.Fixtures
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.MusicParsers
import xyz.mooma0750.wave.core.music.MusicVideoType
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 歌詞から曲を探す（決定 2026-10-14）。 */
class LyricsSearchTest {
    private fun song(
        id: String,
        title: String,
        artist: String = "A",
        kind: MusicVideoType? = MusicVideoType.Atv,
    ) = Song(id, title, listOf(ArtistRef(artist, null)), null, 200, null, kind = kind)

    @Test
    fun unfilteredSearchPutsTheTopCardFirstAndSkipsArtists() {
        // 歌詞の一節「沈むように溶けてゆくように」で匿名に取った応答（棚は itemSectionRenderer）
        val songs = MusicParsers.searchTopSongs(Fixtures.json("search_all_lyrics"))
        assertEquals("x8VYWazR5mE", songs.first().videoId)
        assertEquals("夜に駆ける", songs.first().title)
        assertEquals(MusicVideoType.Omv, songs.first().kind)
        assertEquals(listOf("YOASOBI"), songs.first().artists.map { it.name })
        assertEquals(4 * 60 + 36, songs.first().durationSeconds)
        assertEquals("by4SYYWlhEs", songs[1].videoId)
        assertTrue(songs.size >= 4)
        assertEquals(songs.size, songs.map { it.videoId }.toSet().size)
    }

    @Test
    fun normalizeIgnoresCaseWidthKanaAndPunctuation() {
        assertEquals(LyricsMatcher.normalize("I'm in love with the shape of you"), LyricsMatcher.normalize("im in LOVE with　the shape of you!"))
        assertEquals(LyricsMatcher.normalize("ＡＢＣ　カタカナ、です。"), LyricsMatcher.normalize("abc かたかなです"))
        assertEquals("", LyricsMatcher.normalize(" 、。!? "))
    }

    @Test
    fun findsTheLineEvenWhenThePhraseSpansTwoLines() {
        val lyrics = Lyrics.Synced(listOf(LyricLine(1_000, "沈むように"), LyricLine(3_000, "溶けてゆくように"), LyricLine(6_000, "二人だけの空が")), null)
        assertEquals(LyricsMatch("沈むように", 1_000, exact = true), LyricsMatcher.find(lyrics, "沈むように溶けてゆく"))
        assertEquals(LyricsMatch("二人だけの空が", 6_000, exact = true), LyricsMatcher.find(lyrics, "二人だけの"))
        val plain = Lyrics.Plain("Hello, it's me\n\nI was wondering if after all these years", null)
        assertEquals(LyricsMatch("I was wondering if after all these years", null, exact = true), LyricsMatcher.find(plain, "wondering if after"))
    }

    @Test
    fun aSlightlyWrongPhraseIsAnApproximateMatchAndUnrelatedTextIsNone() {
        val lyrics = Lyrics.Plain("to tell you I'm sorry for everything that I've done", null)
        val near = LyricsMatcher.find(lyrics, "to tell you sorry for everything i've done")
        assertEquals(false, near?.exact)
        assertNull(LyricsMatcher.find(lyrics, "never gonna give you up"))
        // 短すぎる言葉は大体の一致を取らない（何にでも合ってしまう）
        assertNull(LyricsMatcher.find(lyrics, "toz"))
    }

    @Test
    fun songsWithTheLyricsComeFirstAndTheSongReplacesItsVideo(): Unit =
        runBlocking {
            val video = song("v1", "夜に駆ける", "YOASOBI", MusicVideoType.Omv)
            val other = song("o1", "別の曲", "B")
            val atv = song("a1", "夜に駆ける", "YOASOBI")
            val search =
                LyricsSearch(
                    candidates = { CallResult.Ok(listOf(video, other, atv)) },
                    lyrics = {
                        when (it.videoId) {
                            "a1" -> LyricsRepository.Result.Found(Lyrics.Plain("沈むように溶けてゆくように", null))
                            else -> LyricsRepository.Result.None
                        }
                    },
                )
            val hits = (search.search("溶けてゆくように") as CallResult.Ok).value
            assertEquals(listOf("a1", "o1"), hits.map { it.song.videoId })
            assertEquals("沈むように溶けてゆくように", hits.first().match?.line)
            assertNull(hits[1].match)
        }

    @Test
    fun failedSearchIsPassedOnAndBlankQueryIsEmpty(): Unit =
        runBlocking {
            var asked = false
            val search =
                LyricsSearch(
                    candidates = {
                        asked = true
                        CallResult.Failed(FailureKind.Network)
                    },
                    lyrics = { LyricsRepository.Result.None },
                )
            assertEquals(emptyList(), (search.search(" 。") as CallResult.Ok).value)
            assertEquals(false, asked)
            assertIs<CallResult.Failed>(search.search("夜に駆ける"))
        }
}
