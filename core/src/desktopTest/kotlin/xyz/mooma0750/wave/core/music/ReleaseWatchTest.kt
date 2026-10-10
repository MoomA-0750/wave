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
    fun onlyNewAlbumsOfThisYearAreReported() {
        val (_, first) = ReleaseWatchState().observe("UC1", page(listOf("MP1"), listOf("v1")), year = 2026)
        // 古い作品が棚に入り直した・動画が増えただけでは知らせない
        val (fresh, second) = first.observe("UC1", page(listOf("MP3new", "MP2", "MP1"), listOf("v2", "v1")), year = 2026)
        assertEquals(listOf("MP3new"), fresh.mapNotNull { it.albumBrowseId })
        assertEquals(emptyList(), fresh.mapNotNull { it.videoId })
        kotlin.test.assertTrue("a:MP2" in second.seen["UC1"]!! && "v:v2" in second.seen["UC1"]!!)
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
        val (fresh, second) = first.observe("UC1", page(listOf("MP3new", "MPold", "MPnew")), nowEpochMs = 2, year = 2026)
        assertEquals(listOf("MP3new"), fresh.mapNotNull { it.albumBrowseId })
        assertEquals(listOf("MP3new", "MPnew"), second.recent.mapNotNull { it.albumBrowseId })
        assertEquals(listOf("MP3new"), second.expire(nowEpochMs = 2, maxAgeMs = 0).recent.mapNotNull { it.albumBrowseId })
    }

    @Test
    fun releasesThatLeaveTheArtistPageAreDropped() {
        // 同じ名前の別人の作品がまとめられ、後でページから外された
        val (_, first) = ReleaseWatchState().observe("UC1", page(listOf("MPold")), nowEpochMs = 1, year = 2026)
        val (_, second) = first.observe("UC1", page(listOf("MPothernew", "MPold")), nowEpochMs = 2, year = 2026)
        assertEquals(listOf("MPothernew"), second.recent.mapNotNull { it.albumBrowseId })
        val (_, third) = second.observe("UC1", page(listOf("MPold")), nowEpochMs = 3, year = 2026)
        assertEquals(emptyList(), third.recent)
        // 今年の作品だけの棚からは、押し出されただけかもしれないので外さない
        assertEquals(second.recent, listedReleases(second.recent, "UC1", page(listOf("MP4new", "MP5new")), 2026))
        // ほかのアーティストの分は残す
        assertEquals(second.recent, listedReleases(second.recent, "UC2", page(listOf("MPold")), 2026))
    }
}
