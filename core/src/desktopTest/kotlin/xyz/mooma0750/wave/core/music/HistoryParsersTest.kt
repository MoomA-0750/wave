package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 再生履歴の読み取り。履歴はログインしていないと返らないので、fixture は取れない（匿名でしか取らない決まり）。
 * 実アカウントで確かめた形（区切りごとの musicShelfRenderer）を合成して確かめる。
 */
class HistoryParsersTest {
    private fun song(
        videoId: String,
        title: String,
    ) = """
        {"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"$videoId"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"アーティスト","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}}}
          ]}}
        """.trimIndent()

    private fun shelf(
        title: String,
        songs: List<String>,
    ) = """{"musicShelfRenderer":{"title":{"runs":[{"text":"$title"}]},"contents":[${songs.joinToString(",")}]}}"""

    private fun parse(text: String): HistoryPage = MusicParsers.historyPage(Json.parseToJsonElement(text) as JsonObject)

    @Test
    fun sectionsKeepTheirHeadingsAndOrder() {
        val page =
            parse(
                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                ${shelf("今日", listOf(song("v1", "曲1"), song("v2", "曲2")))},
                ${shelf("今週", listOf(song("v3", "曲3")))}
                ]}}}}]}}}""",
            )
        assertEquals(listOf("今日", "今週"), page.sections.map { it.title })
        assertEquals(listOf("v1", "v2", "v3"), page.songs.map { it.videoId })
        assertEquals("曲1", page.sections[0].songs[0].title)
        assertNull(page.continuation)
    }

    @Test
    fun theContinuationIsTakenFromTheSectionList() {
        val page =
            parse(
                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
                "continuations":[{"nextContinuationData":{"continuation":"token-1"}}],
                "contents":[${shelf("今日", listOf(song("v1", "曲1")))}]}}}}]}}}""",
            )
        assertEquals("token-1", page.continuation)
    }

    @Test
    fun theSecondPageComesFromTheContinuationContents() {
        val page =
            parse(
                """{"continuationContents":{"sectionListContinuation":{
                "contents":[${shelf("先月", listOf(song("v9", "曲9")))}],
                "continuations":[{"nextContinuationData":{"continuation":"token-2"}}]}}}""",
            )
        assertEquals(listOf("先月"), page.sections.map { it.title })
        assertEquals(listOf("v9"), page.songs.map { it.videoId })
        assertEquals("token-2", page.continuation)
    }

    @Test
    fun emptySectionsAndUnknownShapesAreDropped() {
        val page =
            parse(
                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                ${shelf("空", emptyList())},
                {"musicCarouselShelfRenderer":{"contents":[]}},
                ${shelf("今日", listOf(song("v1", "曲1")))}
                ]}}}}]}}}""",
            )
        assertEquals(listOf("今日"), page.sections.map { it.title })
        assertEquals(emptyList(), MusicParsers.historyPage(Json.parseToJsonElement("""{"x":1}""") as JsonObject).sections)
    }
}
