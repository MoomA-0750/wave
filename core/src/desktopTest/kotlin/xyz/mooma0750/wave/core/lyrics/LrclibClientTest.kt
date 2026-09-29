package xyz.mooma0750.wave.core.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import xyz.mooma0750.wave.core.jsonResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 合成の応答だけを使う（歌詞の本文は著作物なので fixture に残さない）。 */
class LrclibClientTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var get: MockRequestHandleScope.() -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }
    private var search: MockRequestHandleScope.() -> HttpResponseData = { jsonResponse("[]") }
    private val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/get" -> get()
                    "/api/search" -> search()
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
    private val client = LrclibClient(http, userAgent = "wave-test/1")

    @AfterTest
    fun tearDown() = http.close()

    private fun track(
        duration: Double,
        synced: String? = "[00:01.00]合成の歌詞",
        instrumental: Boolean = false,
    ): String = """{"trackName":"t","artistName":"a","duration":$duration,"instrumental":$instrumental,"syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun exactMatchIsUsedAndOnlySongInfoIsSent(): Unit =
        runBlocking {
            get = { jsonResponse(track(262.0)) }
            val result = client.synced("夜に駆ける", "YOASOBI", "THE BOOK", 262)
            assertEquals(LrclibClient.Result.Found(listOf(LyricLine(1_000, "合成の歌詞"))), result)
            val request = requests.single()
            assertEquals("wave-test/1", request.headers[HttpHeaders.UserAgent])
            assertEquals(setOf("track_name", "artist_name", "album_name", "duration"), request.url.parameters.names())
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun searchPicksTheClosestLengthAndIgnoresOddVersions(): Unit =
        runBlocking {
            // 同じ曲名で長さのおかしい版（4秒・40秒）や、時刻の無い版が混じる
            search = {
                jsonResponse(
                    "[${track(4.0)},${track(262.0, synced = null)},${track(265.0, synced = "[00:02.00]5秒ずれ")},${track(261.0, synced = "[00:03.00]1秒ずれ")}]",
                )
            }
            val result = assertIs<LrclibClient.Result.Found>(client.synced("t", "a", null, 262))
            assertEquals("1秒ずれ", result.lines.single().text)
            // 1件引き → 検索（曲名は添え書きが無いので1回）
            assertEquals(listOf("/api/get", "/api/search"), requests.map { it.url.encodedPath })
            assertTrue("album_name" !in requests.first().url.parameters.names())
        }

    @Test
    fun nothingCloseEnoughIsNotFound(): Unit =
        runBlocking {
            search = { jsonResponse("[${track(4.0)},${track(240.0)}]") }
            assertEquals(LrclibClient.Result.NotFound, client.synced("t", "a", null, 262))
        }

    @Test
    fun instrumentalOrUntimedExactMatchFallsBackToSearch(): Unit =
        runBlocking {
            get = { jsonResponse(track(262.0, instrumental = true)) }
            search = { jsonResponse("[${track(262.0)}]") }
            assertIs<LrclibClient.Result.Found>(client.synced("t", "a", null, 262))
        }

    @Test
    fun serverErrorsAreFailuresNotAbsence(): Unit =
        runBlocking {
            get = { respond("", HttpStatusCode.TooManyRequests) }
            assertEquals(LrclibClient.Result.Failed, client.synced("t", "a", null, 262))
            get = { respond("", HttpStatusCode.NotFound) }
            search = { respond("not json", HttpStatusCode.OK) }
            assertEquals(LrclibClient.Result.Failed, client.synced("t", "a", null, 262))
        }

    @Test
    fun searchTitlesDropDecorations() {
        assertEquals(listOf("夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"), LrclibClient.searchTitles("夜に駆ける (From \"THE FIRST TAKE\")"))
        assertEquals(listOf("オリオン - Orion", "オリオン"), LrclibClient.searchTitles("オリオン - Orion"))
        assertEquals(listOf("群青"), LrclibClient.searchTitles("群青"))
    }

    @Test
    fun withoutLengthTheFirstTimedOneIsUsed() {
        val tracks = listOf(LrclibClient.Track(duration = 1.0), LrclibClient.Track(duration = 100.0, syncedLyrics = "[00:01.00]x"))
        assertEquals(100.0, LrclibClient.pick(tracks, null)?.duration)
    }
}
