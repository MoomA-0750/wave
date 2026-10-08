package xyz.mooma0750.wave.core.music

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseWatchTest {
    private fun page(
        albums: List<String>,
        videos: List<String> = emptyList(),
    ) = ArtistPage(
        name = "A",
        thumbnailUrl = null,
        topSongs = emptyList(),
        allSongsPlaylistId = null,
        albumShelves = listOf(AlbumShelf("Albums", albums.map { AlbumSummary(it, "title-$it", if (it.endsWith("new")) "Single • 2026" else "Album • 2019", null) })),
        videos = videos.map { Song(it, "video-$it", emptyList(), null, null, null) },
    )

    @Test
    fun theFirstLookOnlyRemembers() {
        val (fresh, state) = ReleaseWatchState().observe("UC1", page(listOf("MP1", "MP2"), listOf("v1")))
        assertEquals(emptyList(), fresh)
        assertEquals(setOf("a:MP1", "a:MP2", "v:v1"), state.seen["UC1"])
    }

    @Test
    fun newAlbumsAndVideosAreReported() {
        val (_, first) = ReleaseWatchState().observe("UC1", page(listOf("MP1"), listOf("v1")))
        val (fresh, _) = first.observe("UC1", page(listOf("MP3", "MP1"), listOf("v2", "v1")))
        assertEquals(listOf("MP3"), fresh.mapNotNull { it.albumBrowseId })
        assertEquals(listOf("v2"), fresh.mapNotNull { it.videoId })
    }

    @Test
    fun anEmptyPageDoesNotForget() {
        val (_, first) = ReleaseWatchState().observe("UC1", page(listOf("MP1")))
        val (fresh, second) = first.observe("UC1", page(emptyList()))
        assertEquals(emptyList(), fresh)
        assertEquals(first, second)
    }

    @Test
    fun recentReleasesAreKeptNewestFirstAndSeedsOnlyThisYear() {
        val (_, first) = ReleaseWatchState().observe("UC1", page(listOf("MPold", "MPnew")), nowEpochMs = 1, year = 2026)
        assertEquals(listOf("MPnew"), first.recent.mapNotNull { it.albumBrowseId })
        kotlin.test.assertTrue(first.recent.single().seeded)
        val (fresh, second) = first.observe("UC1", page(listOf("MP3", "MPold", "MPnew")), nowEpochMs = 2, year = 2026)
        assertEquals(listOf("MP3"), fresh.mapNotNull { it.albumBrowseId })
        assertEquals(listOf("MP3", "MPnew"), second.recent.mapNotNull { it.albumBrowseId })
        assertEquals(listOf("MP3"), second.expire(nowEpochMs = 2, maxAgeMs = 0).recent.mapNotNull { it.albumBrowseId })
    }
}
