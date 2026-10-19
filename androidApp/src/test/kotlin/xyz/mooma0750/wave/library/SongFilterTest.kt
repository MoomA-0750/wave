package xyz.mooma0750.wave.library

import org.junit.Test
import xyz.mooma0750.wave.core.music.AlbumRef
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongFilterTest {
    private fun song(
        title: String,
        artist: String = "Artist",
        album: String? = null,
    ) = Song("v-$title", title, listOf(ArtistRef(artist, null)), album?.let { AlbumRef(it, null) }, 200, null)

    private val songs =
        listOf(
            song("Hello World", artist = "Adele"),
            song("Blue Bird", artist = "Ikimono", album = "Best"),
            song("ブルー", artist = "藍坊主"),
        )

    @Test
    fun anEmptyQueryKeepsEverythingInOrder() {
        assertEquals(listOf(0, 1, 2), SongFilter.matchingIndices(songs, ""))
        assertEquals(listOf(0, 1, 2), SongFilter.matchingIndices(songs, "   "))
    }

    @Test
    fun itMatchesTitleArtistAndAlbumIgnoringCase() {
        assertEquals(listOf(0), SongFilter.matchingIndices(songs, "hello"))
        assertEquals(listOf(1), SongFilter.matchingIndices(songs, "IKIMONO"))
        assertEquals(listOf(1), SongFilter.matchingIndices(songs, "best"))
        assertEquals(listOf(2), SongFilter.matchingIndices(songs, "ブルー"))
    }

    @Test
    fun spacesMeanAllOfThem() {
        // 半角でも全角でも区切る。どちらの語も含む曲だけが残る
        assertEquals(listOf(1), SongFilter.matchingIndices(songs, "blue bird"))
        assertEquals(listOf(1), SongFilter.matchingIndices(songs, "bird　best"))
        assertEquals(emptyList(), SongFilter.matchingIndices(songs, "blue hello"))
    }

    @Test
    fun theReturnedPositionsAreTheOriginalOnes() {
        // 絞り込んでも元の位置で返す（再生と「外す」を別の曲に当てないため）
        val many = songs + song("Hello Again", artist = "Adele")
        assertEquals(listOf(0, 3), SongFilter.matchingIndices(many, "adele"))
        assertTrue(SongFilter.matches(many[3], "hello"))
        assertFalse(SongFilter.matches(many[1], "hello"))
    }

    @Test
    fun widthAndKanaTypeAreIgnoredLikeTheOtherDevices() {
        // 全角の英字・ひらがな・半角のカタカナでも当たる（Mac・iPhone の fold、Windows の IgnoreWidth・IgnoreKanaType と同じ）
        assertEquals(listOf(0), SongFilter.matchingIndices(songs, "ＨＥＬＬＯ"))
        assertEquals(listOf(2), SongFilter.matchingIndices(songs, "ぶるー"))
        assertEquals(listOf(2), SongFilter.matchingIndices(songs, "ﾌﾞﾙｰ"))
        assertTrue(LibraryOrder.matches("ブルー", null, "ぶるー"))
        assertTrue(LibraryOrder.matches("Lo-Fi Girl", "ＹｏｕＴｕｂｅ", "lo-fi　youtube"))
        assertFalse(LibraryOrder.matches("ブルー", null, "ぶるう"))
    }
}
