package xyz.mooma0750.wave.library

import org.junit.Test
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import kotlin.test.assertEquals

class LikedByArtistTest {
    private fun song(
        id: String,
        vararg artists: ArtistRef,
    ) = Song(id, "曲$id", artists.toList(), null, 200, null)

    @Test
    fun itMatchesByArtistIdWhenBothSidesHaveOne() {
        val songs =
            listOf(
                song("a", ArtistRef("名前が違う", "UC1")),
                song("b", ArtistRef("同じ名前", "UC2")),
            )
        assertEquals(listOf("a"), LikedByArtist.filter(songs, "UC1", "同じ名前").map { it.videoId })
    }

    @Test
    fun itFallsBackToTheNameWhenTheRowHasNoId() {
        val songs =
            listOf(
                song("a", ArtistRef("Adele", null)),
                song("b", ArtistRef("adele", null)),
                song("c", ArtistRef("別の人", null)),
            )
        assertEquals(listOf("a", "b"), LikedByArtist.filter(songs, "UC1", "Adele").map { it.videoId })
    }

    @Test
    fun aSongWithSeveralArtistsCountsIfAnyOfThemMatches() {
        val songs = listOf(song("a", ArtistRef("誰か", "UC9"), ArtistRef("本命", "UC1")))
        assertEquals(listOf("a"), LikedByArtist.filter(songs, "UC1", "本命").map { it.videoId })
        assertEquals(emptyList(), LikedByArtist.filter(songs, "UC5", "居ない人").map { it.videoId })
    }
}
