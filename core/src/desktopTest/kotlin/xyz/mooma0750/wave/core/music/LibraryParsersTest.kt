package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryParsersTest {
    @Test
    fun playlistFirstPageHasSongsTitleAndContinuation() {
        val page = MusicParsers.playlistPage(fixture("browse_playlist"))
        assertEquals(3, page.songs.size)
        assertTrue(page.songs.all { it.videoId.isNotBlank() && it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationSeconds != null })
        assertNotNull(page.title)
        assertNotNull(page.continuation)
        assertFalse(page.endConfirmed)
    }

    @Test
    fun playlistContinuationPage() {
        val page = MusicParsers.playlistPage(fixture("browse_playlist_continuation"))
        assertEquals(3, page.songs.size)
        assertTrue(page.endConfirmed, "最後のページと確かめられない")
    }

    @Test
    fun playlistCards() {
        val playlists = MusicParsers.playlists(fixture("browse_home_playlists"))
        assertEquals(3, playlists.size)
        assertTrue(playlists.all { it.playlistId.isNotBlank() && !it.playlistId.startsWith("VL") && it.title.isNotBlank() && it.thumbnailUrl != null })
    }

    @Test
    fun loggedOutLibraryIsASignInPrompt() {
        assertTrue(MusicParsers.isSignInPrompt(fixture("browse_library_logged_out")))
        assertTrue(MusicParsers.isSignInPrompt(fixture("browse_liked_songs_logged_out")))
        assertFalse(MusicParsers.isSignInPrompt(fixture("browse_playlist")))
        assertFalse(MusicParsers.isSignInPrompt(fixture("browse_home_playlists")))
    }

    @Test
    fun rendererNamesCarryNoValues() {
        val names = MusicParsers.rendererNames(fixture("browse_playlist"))
        assertTrue("musicPlaylistShelfRenderer" in names)
        assertTrue(names.none { it.contains(" ") || it.startsWith("http") })
    }

    @Test
    fun onlyCardsWithAnEditMenuAreEditable() {
        // 合成: 自分のプレイリスト（EDIT あり）と、保存しただけのプレイリスト（BOOKMARK だけ）
        fun card(id: String, icons: List<String>) =
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$id"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VL$id"}},
            "menu":{"menuRenderer":{"items":[${icons.joinToString(",") { """{"menuNavigationItemRenderer":{"icon":{"iconType":"$it"}}}""" }}]}}}}"""
        val root = Json.parseToJsonElement("""{"items":[${card("PLmine", listOf("MUSIC_SHUFFLE", "EDIT", "DELETE"))},${card("PLsaved", listOf("BOOKMARK", "SHARE"))}]}""") as JsonObject
        val playlists = MusicParsers.playlists(root).associate { it.playlistId to it.editable }
        assertEquals(mapOf("PLmine" to true, "PLsaved" to false), playlists)
        assertTrue(MusicParsers.playlists(fixture("browse_home_playlists")).none { it.editable })
    }

    @Test
    fun emptyPlaylistIsNotASignInPrompt() {
        // 実アカウントの空のプレイリストの形（レンダラ名だけを実機で確認、2026-09-17）を合成する
        val empty =
            Json.parseToJsonElement(
                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"x"}]}}}}},
                "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"continuations":[{"nextContinuationData":{"continuation":"suggestions"}}],"contents":[{"musicPlaylistShelfRenderer":{"contents":[
                {"musicSideAlignedItemRenderer":{"startItems":[{"messageRenderer":{"text":{"runs":[{"text":"no songs"}]}}}]}}]}}]}}}}}""",
            ) as JsonObject
        assertFalse(MusicParsers.isSignInPrompt(empty))
        val page = MusicParsers.playlistPage(empty)
        assertTrue(page.songs.isEmpty())
        assertTrue(page.editable)
        assertTrue(page.endConfirmed)
    }

    @Test
    fun playlistTitleFallsBackToTheDetailHeader() {
        // 新しいヘッダ（musicResponsiveHeaderRenderer）が無い応答では、古いヘッダの題名を使う
        val root =
            Json.parseToJsonElement(
                """{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"古い"},{"text":"ヘッダ"}]}}},
                "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[]}}]}}}}]}}}""",
            ) as JsonObject
        assertEquals("古いヘッダ", MusicParsers.playlistPage(root).title)
        // 両方あれば新しい方
        val both =
            Json.parseToJsonElement(
                """{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"古い"}]}}},
                "x":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"新しい"}]}}},
                "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[]}}]}}}}]}}}""",
            ) as JsonObject
        assertEquals("新しい", MusicParsers.playlistPage(both).title)
    }

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText()) as JsonObject
}
