package xyz.mooma0750.wave.core.music

import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 日本語と英語の2つの応答（2026-11-07 に取って、使わない欄を落としたもの）から、元の表記を選ぶ。 */
class OriginalTitlesTest {
    private fun merged(
        name: String,
        lang: String,
        known: Map<String, String> = emptyMap(),
    ): OriginalTitles.Result =
        OriginalTitles.merge(Fixtures.json("bilingual_${name}_$lang"), Fixtures.json("bilingual_${name}_${OriginalTitles.otherLanguage(lang)}"), lang, known)

    private val taylor = "UCPC0L1d253x-KuMNwa05TpA"

    @Test
    fun westernSongsKeepTheirEnglishNamesInJapanese() {
        val songs = MusicParsers.searchSongs(merged("search_songs", "ja").json).songs
        val artists = songs.flatMap { s -> s.artists.map { it.name } }
        assertTrue("Taylor Swift" in artists)
        assertFalse(artists.any { it.contains("テイラー") })
        assertTrue(songs.any { it.title == "All Too Well" })
        // 英語の画面では、もともと英語なので変えない
        assertEquals(MusicParsers.searchSongs(Fixtures.json("bilingual_search_songs_en")), MusicParsers.searchSongs(merged("search_songs", "en").json))
    }

    @Test
    fun japaneseSongsKeepTheirJapaneseNamesInEnglish() {
        val album = MusicParsers.albumPage(merged("album_yonezu", "en").json, "MPREb_NnSQrGZDyMk")!!
        assertEquals(listOf("米津玄師"), album.artists.map { it.name })
        val titles = album.songs.map { it.title }
        assertTrue("アイネクライネ" in titles)
        assertTrue("花に嵐" in titles)
        assertFalse(titles.any { it.contains(" - ") }, titles.toString())
        // 日本語の画面では、アルバムの曲はそのまま
        assertEquals(
            MusicParsers.albumPage(Fixtures.json("bilingual_album_yonezu_ja"), "MPREb_NnSQrGZDyMk")!!.songs,
            MusicParsers.albumPage(merged("album_yonezu", "ja").json, "MPREb_NnSQrGZDyMk")!!.songs,
        )
    }

    @Test
    fun artistPageUsesTheArtistsOwnNames() {
        val ja = merged("artist_taylor", "ja")
        val page = MusicParsers.artistPage(ja.json)!!
        assertEquals("Taylor Swift", page.name)
        assertTrue(page.topSongs.any { it.title == "Shake It Off" }, page.topSongs.map { it.title }.toString())
        assertFalse(page.topSongs.any { s -> s.title.any { it in 'ァ'..'ヶ' } }, page.topSongs.map { it.title }.toString())
        assertEquals("en", ja.learned[taylor])
        // 英語の画面で、日本語の副題（「～気にしてなんかいられないっ！！」の漢字）を日本の曲と取り違えない
        val en = MusicParsers.artistPage(merged("artist_taylor", "en").json)!!
        assertEquals(MusicParsers.artistPage(Fixtures.json("bilingual_artist_taylor_en"))!!.topSongs, en.topSongs)
    }

    @Test
    fun queueUsesWhatWasLearnedAboutTheArtist() {
        // キューの動画の行はアーティスト名（チャンネル名）が同じで題しか違わないので、覚えたことが無ければ変えない
        val plain = MusicParsers.upNext(merged("next_taylor", "ja").json).songs.first()
        assertTrue(plain.title.startsWith("シェイク"))
        val learned = MusicParsers.upNext(merged("next_taylor", "ja", known = mapOf(taylor to "en")).json).songs.first()
        assertEquals("Shake It Off", learned.title)
        assertEquals("Shake It Off", MusicParsers.upNext(merged("next_taylor", "en").json).songs.first().title)
    }

    @Test
    fun katakanaJapaneseArtistsAndYouTubePlaylistsStay() {
        // サカナクション（カタカナ名の邦楽）は、曲名に日本の文字が残るので日本の曲と分かる。YouTube のプレイリスト名・棚の見出しは画面の言語のまま
        val en = MusicParsers.homePage(merged("home", "en").json)
        val plain = MusicParsers.homePage(Fixtures.json("bilingual_home_en"))
        assertEquals(plain.shelves.map { it.title }, en.shelves.map { it.title })
        // YouTube のプレイリストの補足（並ぶアーティストの名前）は画面の言語のまま
        val items = en.shelves.flatMap { it.items }.filterIsInstance<HomeItem.SongItem>().toString()
        assertTrue("サカナクション" in items)
        assertTrue("夜の踊り子" in items && "Yoru No Odoriko" !in items)
        assertTrue("米津玄師" in items && "Kenshi Yonezu" !in items)
        assertTrue("Official髭男dism" in items)
        val ja = MusicParsers.homePage(merged("home", "ja").json)
        assertEquals(MusicParsers.homePage(Fixtures.json("bilingual_home_ja")), ja)
    }

    @Test
    fun otherLanguageIsTheOppositeOfTheScreen() {
        assertEquals("en", OriginalTitles.otherLanguage("ja"))
        assertEquals("en", OriginalTitles.otherLanguage("ja-JP"))
        assertEquals("ja", OriginalTitles.otherLanguage("en-GB"))
    }
}
