package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 匿名で取得した公開データの fixture（responseContext などは除去済み）で、形を固定する。 */
class MusicParsersTest {
    @Test
    fun searchSongsFirstPage() {
        val page = MusicParsers.searchSongs(fixture("search_songs"))
        assertEquals(3, page.songs.size)
        val song = page.songs.first()
        assertTrue(song.videoId.isNotBlank())
        assertTrue(song.title.isNotBlank())
        assertTrue(song.artists.isNotEmpty(), "アーティストが取れていない")
        assertNotNull(song.album)
        assertNotNull(song.durationSeconds)
        assertTrue(song.thumbnailUrl.orEmpty().startsWith("https://"))
        assertNotNull(page.continuation)
    }

    @Test
    fun searchSongsContinuationPage() {
        val page = MusicParsers.searchSongs(fixture("search_songs_continuation"))
        assertEquals(3, page.songs.size)
        assertTrue(page.songs.all { it.artists.isNotEmpty() && it.durationSeconds != null })
        assertNotNull(page.continuation)
    }

    @Test
    fun upNextFirstPage() {
        val page = MusicParsers.upNext(fixture("next_radio"))
        assertEquals(4, page.songs.size)
        assertTrue(page.songs.all { it.videoId.isNotBlank() && it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationSeconds != null })
        assertTrue(page.playlistId.orEmpty().startsWith("RDAMVM"))
        assertNotNull(page.continuation)
    }

    @Test
    fun upNextContinuationPrefersContinuationContents() {
        val first = MusicParsers.upNext(fixture("next_radio"))
        val page = MusicParsers.upNext(fixture("next_radio_continuation"))
        assertEquals(4, page.songs.size)
        assertTrue(page.songs.map { it.videoId } != first.songs.map { it.videoId }, "続きのページで最初のページの contents を読んでいる")
    }

    @Test
    fun unknownShapesReturnEmptyInsteadOfThrowing() {
        assertEquals(SongPage(emptyList(), null), MusicParsers.searchSongs(JsonObject(emptyMap())))
        assertEquals(UpNextPage(emptyList(), null, null), MusicParsers.upNext(JsonObject(emptyMap())))
        val broken = Json.parseToJsonElement("""{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"no id"}]}}}]}""") as JsonObject
        assertNull(MusicParsers.responsiveListItemSong(broken))
    }

    @Test
    fun durations() {
        assertEquals(262, MusicParsers.parseDuration("4:22"))
        assertEquals(3723, MusicParsers.parseDuration("1:02:03"))
        assertNull(MusicParsers.parseDuration("1.1B plays"))
        assertNull(MusicParsers.parseDuration("2021"))
    }

    @Test
    fun songRowsTakeTheLastThumbnailAndTheirDuration() {
        // 一覧の行: いちばん大きい（最後の）サムネイル、長さは固定の列、無ければ詳細の列から
        fun row(fixed: String?) =
            Json.parseToJsonElement(
                """{"playlistItemData":{"videoId":"abcdefghijk"},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"曲"}]}}},
                               {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"人"},{"text":" • "},{"text":"2:05"}]}}}],
                ${fixed?.let { """"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"$it"}]}}}],""" } ?: ""}
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://small"},{"url":"https://large"}]}}}}""",
            ) as JsonObject
        val withFixed = assertNotNull(MusicParsers.responsiveListItemSong(row("3:10")))
        assertEquals("https://large", withFixed.thumbnailUrl)
        assertEquals(190, withFixed.durationSeconds)
        assertEquals("曲", withFixed.title)
        assertEquals(125, assertNotNull(MusicParsers.responsiveListItemSong(row(null))).durationSeconds)
    }

    @Test
    fun queuePanelRowsTakeTheLastThumbnailAndLength() {
        val item =
            Json.parseToJsonElement(
                """{"videoId":"abcdefghijk","title":{"runs":[{"text":"曲"}]},"lengthText":{"runs":[{"text":"4:01"}]},
                "longBylineText":{"runs":[{"text":"人"}]},
                "thumbnail":{"thumbnails":[{"url":"https://small"},{"url":"https://large"}]}}""",
            ) as JsonObject
        val song = MusicParsers.playlistPanelSong(item)
        assertEquals(241, song?.durationSeconds)
        assertEquals("曲", song?.title)
        assertEquals("https://large", song?.thumbnailUrl)
    }

    @Test
    fun songsCarryTheirKind() {
        // 検索・ラジオの行には曲の種類（音源・MV・投稿者の動画）が付く。一覧の種類の絞り込みに使う
        val search = MusicParsers.searchSongs(fixture("search_songs")).songs
        assertTrue(search.isNotEmpty() && search.all { it.kind != null }, "検索の曲に種類が無い: ${search.map { it.kind }}")
        val radio = MusicParsers.upNext(fixture("next_radio")).songs
        assertTrue(radio.isNotEmpty() && radio.all { it.kind != null }, "ラジオの曲に種類が無い: ${radio.map { it.kind }}")
        val playlist = MusicParsers.playlistPage(fixture("browse_playlist")).songs
        assertTrue(playlist.isNotEmpty() && playlist.all { it.kind != null }, "プレイリストの曲に種類が無い: ${playlist.map { it.kind }}")
    }

    private fun fixture(name: String): JsonObject = xyz.mooma0750.wave.core.Fixtures.json(name)

    @Test
    fun artistPostsAreReadAndLiveAnnouncementsAreKept() {
        val root =
            Json.parseToJsonElement(
                """{"contents":{"tabs":[{"tabRenderer":{"content":{"items":[
                {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx1","contentText":{"runs":[{"text":"台北ドーム2DAYS開催決定！\n"},{"text":"チケット販売は9月16日から"}]},
                  "publishedTimeText":{"runs":[{"text":"2 日前"}]},
                  "backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"//yt3.ggpht.com/small"},{"url":"//yt3.ggpht.com/large"}]}}}}}}},
                {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx2","contentText":{"runs":[{"text":"新曲 10/14 配信リリース決定！"}]}}}}}
                ]}}}]}}""",
            ) as JsonObject
        val posts = MusicParsers.artistPosts(root)
        assertEquals(listOf("Ugkx1", "Ugkx2"), posts.map { it.postId })
        assertEquals("台北ドーム2DAYS開催決定！\nチケット販売は9月16日から", posts[0].text)
        assertEquals("2 日前", posts[0].publishedText)
        assertEquals("https://yt3.ggpht.com/large", posts[0].imageUrl)
        assertEquals("https://www.youtube.com/post/Ugkx1", posts[0].url)
        // 告知らしいものだけ（リリースの知らせは新作のほうで出す）
        assertEquals(listOf("Ugkx1"), LiveNews.of(posts).map { it.postId })
        assertTrue(LiveNews.isLiveRelated("ASIA DOME TOUR 2026"))
    }
}
