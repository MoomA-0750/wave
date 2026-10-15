package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonObject
import xyz.mooma0750.wave.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ライブ配信の検索・HLS の解決・Lo-Fi のタイル（匿名で取得した公開データ）。 */
class LiveParsersTest {
    private fun masterText(): String = requireNotNull(javaClass.getResource("/fixtures/hls_master.m3u8")).readText()

    @Test
    fun webLiveSearchKeepsOnlyLiveVideos() {
        val page = LiveParsers.webSearch(Fixtures.json("search_live_web"))
        assertEquals(listOf("a_1ezVmZzig", "2BG9FPatcEs", "Jo5fOHMS9WQ"), page.songs.map { it.videoId })
        val first = page.songs.first()
        assertTrue(first.isLive)
        assertNull(first.durationSeconds)
        assertEquals(listOf("The Japanese Town"), first.artists.map { it.name })
        assertTrue(first.title.startsWith("1980s & 90s"))
        assertTrue(first.thumbnailUrl.orEmpty().startsWith("https://i.ytimg.com/"))
        assertNotNull(page.continuation)
    }

    @Test
    fun musicSearchMarksLiveBadgeRows() {
        val songs = MusicParsers.searchSongs(Fixtures.json("search_videos_live")).songs
        assertEquals(listOf("rFZHOHl-L8A", "JD-kMIpDfnY", "ffGvaOKK4eM"), songs.map { it.videoId })
        assertEquals(listOf(true, true, false), songs.map { it.isLive })
        assertNull(songs[0].durationSeconds)
        assertEquals("Lofi Girl", songs[0].artists.single().name)
        assertEquals(67 * 60 + 10, songs[2].durationSeconds)
    }

    @Test
    fun playerResponseGivesManifestOnlyWhenPlayable() {
        val player = Fixtures.json("player_live_android")
        assertTrue(LiveParsers.isLive(player))
        val url = assertNotNull(LiveParsers.hlsManifestUrl(player))
        assertTrue(url.startsWith("https://manifest.googlevideo.com/api/manifest/hls_variant/"))
        assertEquals(21540L, LiveParsers.expiresInSeconds(player))
        val unplayable = JsonObject(player + ("playabilityStatus" to JsonObject(mapOf("status" to kotlinx.serialization.json.JsonPrimitive("UNPLAYABLE")))))
        assertNull(LiveParsers.hlsManifestUrl(unplayable))
        assertNull(LiveParsers.hlsManifestUrl(JsonObject(emptyMap())))
        assertFalse(LiveParsers.isLive(JsonObject(emptyMap())))
    }

    @Test
    fun masterPlaylistChoosesSmallestAacLcVariant() {
        val masterUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/file/index.m3u8"
        val variants = LiveParsers.variants(masterText(), masterUrl)
        assertEquals(6, variants.size)
        assertEquals(listOf(290288L, 546239L, 1209862L, 1568726L, 2969452L, 5420722L), variants.map { it.bandwidth })
        val chosen = assertNotNull(LiveParsers.chooseAudioVariant(variants))
        assertEquals(1209862L, chosen.bandwidth)
        assertTrue("/itag/93/" in chosen.url)
        assertTrue(isAllowedLiveManifestUrl(chosen.url))
    }

    @Test
    fun videoVariantIsTheBest720pOrLower() {
        val masterUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/file/index.m3u8"
        val variants = LiveParsers.variants(masterText(), masterUrl)
        assertEquals(listOf(144, 240, 360, 480, 720, 1080), variants.map { it.height })
        val chosen = assertNotNull(LiveParsers.chooseVideoVariant(variants))
        assertEquals(720, chosen.height)
        assertEquals(2969452L, chosen.bandwidth)
        // 高さが書かれていなければ、音のための1本と同じ
        val bare = variants.map { it.copy(height = null) }
        assertEquals(LiveParsers.chooseAudioVariant(bare), LiveParsers.chooseVideoVariant(bare))
    }

    @Test
    fun variantsResolveRelativeUrisAndFallBackToSmallest() {
        val master =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900,CODECS="mp4a.40.5,avc1.42C00B"
            low/index.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.5,avc1.4D4015",BANDWIDTH=500
            /abs/index.m3u8
            """.trimIndent()
        val variants = LiveParsers.variants(master, "https://manifest.googlevideo.com/api/manifest/hls_variant/master.m3u8?x=1")
        assertEquals(
            listOf("https://manifest.googlevideo.com/api/manifest/hls_variant/low/index.m3u8", "https://manifest.googlevideo.com/abs/index.m3u8"),
            variants.map { it.url },
        )
        assertEquals(500L, LiveParsers.chooseAudioVariant(variants)?.bandwidth)
        assertNull(LiveParsers.chooseAudioVariant(emptyList()))
    }

    @Test
    fun liveUrlPolicies() {
        assertTrue(isAllowedLiveManifestUrl("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/x"))
        assertFalse(isAllowedLiveManifestUrl("http://manifest.googlevideo.com/api/manifest/hls_playlist/x"))
        assertFalse(isAllowedLiveManifestUrl("https://evil.example.com/api/manifest/hls_playlist/x"))
        assertFalse(isAllowedLiveManifestUrl("https://manifest.googlevideo.com/videoplayback"))
        assertTrue(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/videoplayback/id/x/itag/93/sq/1/file/seg.ts"))
        assertTrue(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/videoplayback?id=1"))
        assertFalse(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/other"))
        assertFalse(isAllowedLiveSegmentUrl("https://googlevideo.com.evil.example/videoplayback/x"))
    }

    @Test
    fun featuredPlaylistsReadAndFilterLofi() {
        val page = MusicParsers.searchPlaylists(Fixtures.json("search_playlists_lofi"))
        assertEquals(listOf("Lofi Loft", "Cozy Jazz", "J-Lofi", "Living Room Lofi"), page.items.map { it.title })
        assertTrue(page.items.all { it.playlistId.startsWith("RDCLAK5uy_") })
        assertEquals(listOf("Lofi Loft", "J-Lofi", "Living Room Lofi"), LofiGenre.featured(page.items).map { it.title })
    }

    @Test
    fun lofiTileGoesFirstInGenres() {
        val sections = MusicParsers.moods(Fixtures.json("browse_moods"))
        val added = LofiGenre.addTo(sections)
        val genres = added.single { it.title == "Genres" }
        assertEquals(LofiGenre.category, genres.categories.first())
        assertEquals(sections.single { it.title == "Genres" }.categories, genres.categories.drop(1))
        assertEquals(sections.first(), added.first())
        // 二度足さない
        assertEquals(added, LofiGenre.addTo(added))
        // 見出しが分からなければ最後の格子に足す
        val unknown = sections.map { it.copy(title = "x") }
        assertEquals(LofiGenre.PARAMS, LofiGenre.addTo(unknown).last().categories.first().params)
        assertEquals(emptyList(), LofiGenre.addTo(emptyList()))
    }
}
