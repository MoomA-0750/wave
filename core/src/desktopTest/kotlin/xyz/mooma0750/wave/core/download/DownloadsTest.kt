package xyz.mooma0750.wave.core.download

import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun song(
    id: String,
    artist: String = "a-$id",
    live: Boolean = false,
) = Song(id, "t-$id", listOf(ArtistRef(artist, "UC$artist")), null, 200, null, isLive = live)

class DownloadManifestTest {
    @Test
    fun aSongStaysWhileAnyOwnerRemains() {
        val manifest =
            DownloadManifest()
                .withCollection(DownloadedCollection(CollectionKind.Playlist, "P1", "p", null, 1, listOf(song("a"), song("b"))))
                .withSingle(song("a"), 2)
        assertEquals(emptySet(), DownloadPlan.orphans(manifest, setOf("a", "b")))
        // プレイリストを消しても、1曲ずつ取った曲は残る
        val withoutPlaylist = manifest.withoutCollection(CollectionKind.Playlist, "P1")
        assertEquals(setOf("b"), DownloadPlan.orphans(withoutPlaylist, setOf("a", "b")))
        // 1曲ずつも外すと消してよい
        assertEquals(setOf("a", "b"), DownloadPlan.orphans(withoutPlaylist.withoutSingle("a"), setOf("a", "b")))
    }

    @Test
    fun removingASongKeepsItWhileACollectionOwnsIt() {
        val manifest =
            DownloadManifest()
                .withCollection(DownloadedCollection(CollectionKind.Album, "MPREb_1", "al", null, 1, listOf(song("a"))))
                .withSingle(song("a"), 2)
                .withSmart(listOf(SmartDownload(song("s"), SmartCategory.Frequent)), 3)
        val removed = manifest.withoutSong("a").withoutSong("s")
        assertTrue(removed.isWanted("a"))
        assertFalse(removed.isWanted("s"))
    }

    @Test
    fun liveStreamsAreNotDownloaded() {
        val manifest =
            DownloadManifest()
                .withCollection(DownloadedCollection(CollectionKind.Playlist, "P1", "p", null, 1, listOf(song("a"), song("live", live = true), song("a"))))
                .withSingle(song("l2", live = true), 1)
        assertEquals(listOf("a"), manifest.wantedIds().toList())
    }

    @Test
    fun syncReplacesSongsAndKeepsAddedTime() {
        val first = DownloadManifest().withCollection(DownloadedCollection(CollectionKind.Liked, "LM", "liked", null, 10, listOf(song("a"), song("b"))))
        val synced = first.withCollection(DownloadedCollection(CollectionKind.Liked, "LM", "liked", null, 99, listOf(song("b"), song("c"))))
        val collection = synced.collection(CollectionKind.Liked, "LM")!!
        assertEquals(10, collection.addedAtMs)
        assertEquals(listOf("b", "c"), collection.songs.map { it.videoId })
        assertEquals(listOf("c") to listOf("a"), DownloadPlan.diff(first.collection(CollectionKind.Liked, "LM")!!.songs, collection.songs))
        assertEquals(setOf("a"), DownloadPlan.orphans(synced, setOf("a", "b", "c")))
    }

    @Test
    fun missingFollowsPriorityOrderAndProgressCounts() {
        val manifest =
            DownloadManifest()
                .withSmart(listOf(SmartDownload(song("s"), SmartCategory.Discover)), 1)
                .withCollection(DownloadedCollection(CollectionKind.Playlist, "P", "p", null, 1, listOf(song("c1"), song("c2"))))
                .withSingle(song("one"), 1)
        assertEquals(listOf("one", "c2", "s"), DownloadPlan.missing(manifest, setOf("c1")))
        assertEquals(1 to 2, DownloadPlan.progress(manifest.collections.single(), setOf("c1")))
    }

    @Test
    fun manifestRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true }
        val manifest =
            DownloadManifest()
                .withSingle(song("e"), 5, episode = true)
                .withSmart(listOf(SmartDownload(song("s"), SmartCategory.Recommended)), 7)
        assertEquals(manifest, json.decodeFromString(DownloadManifest.serializer(), json.encodeToString(DownloadManifest.serializer(), manifest)))
    }
}

class SmartDownloadsTest {
    @Test
    fun frequentOrdersByCountThenRecency() {
        val counts = listOf(PlayCount(song("x"), 3, 100), PlayCount(song("y"), 3, 200), PlayCount(song("z"), 1, 300))
        // 履歴に出る曲は1回足す: x は 4 回になる。h は履歴だけ（1回）で、端末の z（1回・時刻あり）より後
        val history = listOf(song("x"), song("h"))
        assertEquals(listOf("x", "y", "z", "h"), SmartDownloads.frequent(counts, history).map { it.videoId })
    }

    @Test
    fun playCountsKeepNewestWithinLimit() {
        var counts = emptyList<PlayCount>()
        counts = PlayCounts.record(counts, song("a"), 1, limit = 2)
        counts = PlayCounts.record(counts, song("b"), 2, limit = 2)
        counts = PlayCounts.record(counts, song("a"), 3, limit = 2)
        counts = PlayCounts.record(counts, song("c"), 4, limit = 2)
        assertEquals(listOf("c" to 1, "a" to 2), counts.map { it.song.videoId to it.count })
    }

    @Test
    fun discoverTakesUnknownSongsRoundRobinAcrossSeeds() {
        val radios = listOf(listOf(song("seed1"), song("k"), song("r1"), song("r2")), listOf(song("q1"), song("r1"), song("q2")))
        val result = SmartDownloads.discover(radios, known = setOf("seed1", "k"))
        assertEquals(listOf("r1", "q1", "r2", "q2"), result.map { it.videoId })
    }

    @Test
    fun recommendedFallsBackToMix() {
        assertEquals(listOf("a", "b", "m"), SmartDownloads.recommended(listOf(listOf(song("a")), listOf(song("b"), song("a"))), listOf(song("m"))).map { it.videoId })
    }

    @Test
    fun pickInterleavesCategoriesWithArtistLimitAndFillsGaps() {
        val candidates =
            mapOf(
                SmartCategory.Frequent to listOf(song("f1", "A"), song("f2", "A"), song("f3", "A"), song("f4")),
                SmartCategory.Discover to listOf(song("f1", "A"), song("d1")),
                SmartCategory.Recommended to emptyList(),
            )
        val picked = SmartDownloads.pick(candidates, total = 10)
        // 1曲ずつ順番に。f1 は先に入った「いつも聴いている」に。A は2曲まで（f3 は飛ばす）。足りない分はほかで埋める
        assertEquals(
            listOf("f1" to SmartCategory.Frequent, "d1" to SmartCategory.Discover, "f2" to SmartCategory.Frequent, "f4" to SmartCategory.Frequent),
            picked.map { it.song.videoId to it.category },
        )
    }

    @Test
    fun pickTakesEqualShares() {
        val candidates = SmartCategory.entries.associateWith { category -> (1..10).map { song("${category.name}$it") } }
        val picked = SmartDownloads.pick(candidates, total = 6)
        assertEquals(SmartCategory.entries.associateWith { 2 }, picked.groupingBy { it.category }.eachCount())
    }

    @Test
    fun refreshIsDueOncePerDay() {
        assertTrue(SmartDownloads.refreshDue(null, 0))
        assertFalse(SmartDownloads.refreshDue(1000, 1000 + SmartDownloads.REFRESH_INTERVAL_MS - 1))
        assertTrue(SmartDownloads.refreshDue(1000, 1000 + SmartDownloads.REFRESH_INTERVAL_MS))
    }
}
