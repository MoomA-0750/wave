package xyz.mooma0750.wave.core.music

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import xyz.mooma0750.wave.core.session.WriteResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 合成値だけを使う。プレイリストの読み取りは匿名の fixture を返す。 */
class PlaylistWriteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = CopyOnWriteArrayList<Pair<String, String>>()
    private var editResponse: suspend MockRequestHandleScope.() -> HttpResponseData = { json("""{"status":"STATUS_SUCCEEDED"}""") }
    private var browseFails = false

    /** 1ページ目の続きの形を、知らない名前に変える（YouTube 側の変更の想定）。 */
    private var unknownContinuation = false

    private val engine =
        MockEngine { request: HttpRequestData ->
            val body = request.body.toByteArray().decodeToString()
            sent += request.url.encodedPath to body
            when {
                request.url.encodedPath.endsWith("/browse/edit_playlist") -> editResponse()
                request.url.encodedPath.endsWith("/playlist/create") -> json("""{"playlistId":"PLsynthetic"}""")
                request.url.encodedPath.endsWith("/browse") && browseFails -> respond("", HttpStatusCode.InternalServerError)
                request.url.encodedPath.endsWith("/browse") ->
                    if ("continuation" in body || request.url.parameters.names().any { it.contains("continuation") || it == "ctoken" }) {
                        json(fixture("browse_playlist_continuation"))
                    } else if (unknownContinuation) {
                        json(fixture("browse_playlist").replace("continuationItemRenderer", "futureContinuationRenderer").replace("nextContinuationData", "futureContinuationData").replace("\"continuations\"", "\"futureContinuations\""))
                    } else {
                        json(fixture("browse_playlist"))
                    }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    private val client = createWaveHttpClient(AuthGuardEngine(engine))
    private val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", null, 1)))

    @AfterTest
    fun tearDown() {
        scope.cancel()
        client.close()
    }

    private suspend fun repository(): MusicRepository {
        val session = WaveSession.create(httpClient = client, credentials = credentials, scope = scope)
        withTimeout(5_000) { session.state.first { it == SessionState.Active } }
        return MusicRepository(session)
    }

    private fun edits() = sent.count { it.first.endsWith("/browse/edit_playlist") }

    @Test
    fun addsOnlyAfterReadingEveryPage(): Unit =
        runBlocking {
            val repository = repository()
            assertEquals(AddToPlaylistResult.Added, repository.addToPlaylist("PLx", "not-in-playlist"))
            assertEquals(2, sent.count { it.first.endsWith("/browse") }, "全ページを読んでいない")
            assertEquals(1, edits())
            assertTrue(sent.last().second.contains("ACTION_ADD_VIDEO"))
        }

    @Test
    fun songsAlreadyInThePlaylistAreNotAddedAgain(): Unit =
        runBlocking {
            val repository = repository()
            // 2ページ目にある曲
            val videoId = requireNotNull(Regex("\"videoId\":\"([^\"]+)\"").find(fixture("browse_playlist_continuation"))).groupValues[1]
            assertEquals(AddToPlaylistResult.AlreadyInPlaylist, repository.addToPlaylist("PLx", videoId))
            assertEquals(0, edits())
        }

    @Test
    fun unknownContinuationShapeIsNotTreatedAsTheEnd(): Unit =
        runBlocking {
            unknownContinuation = true
            val repository = repository()
            assertIs<AddToPlaylistResult.NotChecked>(repository.addToPlaylist("PLx", "not-in-playlist"))
            assertEquals(0, edits(), "続きを読めないまま追加した")
        }

    @Test
    fun readFailuresPreventAdding(): Unit =
        runBlocking {
            browseFails = true
            val repository = repository()
            assertIs<AddToPlaylistResult.NotChecked>(repository.addToPlaylist("PLx", "v"))
            assertEquals(0, edits())
        }

    @Test
    fun onlySucceededStatusCountsAsDone(): Unit =
        runBlocking {
            val repository = repository()
            editResponse = { json("""{"status":"STATUS_FAILED"}""") }
            assertEquals(AddToPlaylistResult.Failed(FailureKind.Http, null), repository.addToPlaylist("PLx", "v"))
            editResponse = { json("""{"somethingElse":true}""") }
            assertEquals(AddToPlaylistResult.Unknown, repository.addToPlaylist("PLx", "v"))
            assertEquals(WriteResult.Unknown, repository.removeFromPlaylist("PLx", "v", "SETVIDEOID"))
            editResponse = { json("""{"status":"STATUS_SUCCEEDED"}""") }
            assertIs<WriteResult.Done<Unit>>(repository.removeFromPlaylist("PLx", "v", "SETVIDEOID"))
            assertTrue(sent.last().second.contains("SETVIDEOID"))
            assertEquals("PLsynthetic", assertIs<WriteResult.Done<String>>(repository.createPlaylist("wave test")).value)
        }

    @Test
    fun playlistPagesCarrySetVideoIds() {
        val page = MusicParsers.playlistPage(kotlinx.serialization.json.Json.parseToJsonElement(fixture("browse_playlist")) as kotlinx.serialization.json.JsonObject)
        assertEquals(page.songs.size, page.setVideoIds.size)
        assertTrue(page.setVideoIds.all { it != null })
    }

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText()
}
