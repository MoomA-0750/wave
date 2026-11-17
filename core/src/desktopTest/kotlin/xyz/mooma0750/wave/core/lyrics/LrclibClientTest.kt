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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 合成の応答だけを使う（歌詞の本文は著作物なので fixture に残さない）。 */
class LrclibClientTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var get: MockRequestHandleScope.() -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }
    private var search: MockRequestHandleScope.() -> HttpResponseData = { jsonResponse("[]") }

    /** アーティスト名を付けない（曲名だけの）検索。 */
    private var searchByTitle: MockRequestHandleScope.() -> HttpResponseData = { jsonResponse("[]") }
    private val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/api/get" -> get()
                    "/api/search" -> if ("artist_name" in request.url.parameters.names()) search() else searchByTitle()
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
        title: String = "t",
        artist: String = "a",
    ): String = """{"trackName":"$title","artistName":"$artist","duration":$duration,"instrumental":$instrumental,"syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"}}"""

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
    fun withoutLengthTheFirstMatchingTimedOneIsUsed() {
        val tracks =
            listOf(
                LrclibClient.Track(trackName = "t", artistName = "a", duration = 1.0),
                LrclibClient.Track(trackName = "別の曲", artistName = "a", duration = 50.0, syncedLyrics = "[00:01.00]x"),
                LrclibClient.Track(trackName = "t", artistName = "a", duration = 100.0, syncedLyrics = "[00:01.00]x"),
            )
        assertEquals(100.0, LrclibClient.pick(tracks, "t", "a", null)?.duration)
    }

    @Test
    fun aDifferentSongOfTheSameLengthIsNotPicked(): Unit =
        runBlocking {
            // 同じ長さの別の曲が先に並んでも、曲名とアーティストの合う方を選ぶ
            search = {
                jsonResponse(
                    "[${track(262.0, synced = "[00:01.00]別の曲", title = "別の曲", artist = "別の人")},${track(263.0, synced = "[00:02.00]群青", title = "群青", artist = "YOASOBI")}]",
                )
            }
            val result = assertIs<LrclibClient.Result.Found>(client.synced("群青", "YOASOBI", null, 262))
            assertEquals("群青", result.lines.single().text)
        }

    @Test
    fun noMatchingSongIsNotFound(): Unit =
        runBlocking {
            search = { jsonResponse("[${track(262.0, title = "別の曲", artist = "別の人")},${track(262.0, title = "群青", artist = "別の人")}]") }
            assertEquals(LrclibClient.Result.NotFound, client.synced("群青", "YOASOBI", null, 262))
            assertEquals(LrclibClient.Result.NotFound, client.synced("群青", "YOASOBI", null, null))
        }

    @Test
    fun decorationsAndWidthDoNotPreventAMatch() {
        val tracks = listOf(LrclibClient.Track(trackName = "Ｓｏｎｇ", artistName = "ＡＢＣ, Someone", duration = 200.0, syncedLyrics = "[00:01.00]x"))
        assertNotNull(LrclibClient.pick(tracks, "song (feat. X) [Remix]", "abc", 200))
        assertNotNull(LrclibClient.pick(tracks, "Song - From \"THE FIRST TAKE\"", "abc", 200))
        assertNotNull(LrclibClient.pick(tracks, "Song feat. Someone", "Someone", 200))
    }

    @Test
    fun aRomanizedTitleMatchesOnlyByArtistAndCloseLength() {
        val tracks = listOf(LrclibClient.Track(trackName = "Gunjo", artistName = "YOASOBI", duration = 250.0, syncedLyrics = "[00:01.00]x"))
        assertNotNull(LrclibClient.pick(tracks, "群青", "YOASOBI", 249))
        assertNull(LrclibClient.pick(tracks, "群青", "YOASOBI", 246))
        assertNull(LrclibClient.pick(tracks, "群青", "YOASOBI", null))
    }

    @Test
    fun otherLanguageNamesMatchWhenTheOriginalNamesDoNot(): Unit =
        runBlocking {
            // YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」。アーティスト名付きの検索では返らず、曲名だけの検索で返る
            searchByTitle = {
                jsonResponse("[${track(205.0, synced = "[00:01.00]別の人", title = "揺れる", artist = "ねごと")},${track(238.0, synced = "[00:02.00]合う", title = "揺れる", artist = "Ryokuoushoku Shakai")}]")
            }
            var calls = 0
            val result =
                client.synced("揺れる", "緑黄色社会", null, 238) {
                    calls++
                    LrclibClient.OtherNames("Yureru", "Ryokuoushoku Shakai")
                }
            assertEquals("合う", assertIs<LrclibClient.Result.Found>(result).lines.single().text)
            assertEquals(1, calls)
            // 曲名が別名（ローマ字）でだけ一致する
            searchByTitle = { jsonResponse("[${track(234.0, title = "Yureru", artist = "Ryokuoushoku Shakai")}]") }
            assertIs<LrclibClient.Result.Found>(client.synced("揺れる", "緑黄色社会", null, 238) { LrclibClient.OtherNames("Yureru", "Ryokuoushoku Shakai") })
        }

    @Test
    fun nothingIsPickedWhenTheOtherNamesDoNotMatchEither(): Unit =
        runBlocking {
            searchByTitle = { jsonResponse("[${track(205.0, title = "揺れる", artist = "ねごと")}]") }
            var calls = 0
            val result =
                client.synced("揺れる", "緑黄色社会", null, 238) {
                    calls++
                    LrclibClient.OtherNames("Yureru", "Ryokuoushoku Shakai")
                }
            assertEquals(LrclibClient.Result.NotFound, result)
            assertEquals(1, calls)
            // 別名が取れなければ今までどおり選ばない
            searchByTitle = { jsonResponse("[${track(238.0, title = "揺れる", artist = "Ryokuoushoku Shakai")}]") }
            assertEquals(LrclibClient.Result.NotFound, client.synced("揺れる", "緑黄色社会", null, 238) { null })
        }

    @Test
    fun otherNamesAreNotFetchedWhenATrackMatchesOrNoneIsSynced(): Unit =
        runBlocking {
            search = { jsonResponse("[${track(238.0, title = "揺れる", artist = "緑黄色社会")}]") }
            var calls = 0
            val other: suspend () -> LrclibClient.OtherNames? = {
                calls++
                LrclibClient.OtherNames("Yureru", "Ryokuoushoku Shakai")
            }
            assertIs<LrclibClient.Result.Found>(client.synced("揺れる", "緑黄色社会", null, 238, other))
            // 一致したら曲名だけの検索もしない
            assertEquals(listOf("/api/get", "/api/search"), requests.map { it.url.encodedPath })
            // 時刻付きの候補が無ければ取りに行かない
            search = { jsonResponse("[]") }
            searchByTitle = { jsonResponse("[${track(238.0, synced = null, title = "揺れる", artist = "Ryokuoushoku Shakai")}]") }
            assertEquals(LrclibClient.Result.NotFound, client.synced("揺れる", "緑黄色社会", null, 238, other))
            searchByTitle = { jsonResponse("[]") }
            assertEquals(LrclibClient.Result.NotFound, client.synced("揺れる", "緑黄色社会", null, 238, other))
            assertEquals(0, calls)
        }
}
