package xyz.mooma0750.wave.core.music

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.jsonResponse
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 登録したアーティストの一覧を最後まで読めたかを知らせる（新作の記録を捨ててよいかを決める）。合成値だけを使う。 */
class SubscribedArtistsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var secondPageFails = false

    private val engine =
        MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            when {
                !request.url.encodedPath.endsWith("/browse") -> respond("", HttpStatusCode.NotFound)
                "continuation" !in body && request.url.parameters.names().none { it.contains("continuation") || it == "ctoken" } ->
                    jsonResponse(page("UCone", "One", next = "synthetic-next"))
                secondPageFails -> respond("", HttpStatusCode.InternalServerError)
                else -> jsonResponse(page("UCtwo", "Two", next = null))
            }
        }
    private val client = createWaveHttpClient(AuthGuardEngine(engine))
    private val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync", 1)))

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

    @Test
    fun readingEveryPageIsComplete(): Unit =
        runBlocking {
            var incomplete = false
            val result = repository().subscribedArtists(onIncomplete = { incomplete = true })
            assertEquals(listOf("UCone", "UCtwo"), assertIs<CallResult.Ok<List<ArtistSummary>>>(result).value.map { it.browseId })
            assertFalse(incomplete)
        }

    @Test
    fun aFailedLaterPageReturnsWhatWasReadAndSaysItIsIncomplete(): Unit =
        runBlocking {
            secondPageFails = true
            var incomplete = false
            val result = repository().subscribedArtists(onIncomplete = { incomplete = true })
            assertEquals(listOf("UCone"), assertIs<CallResult.Ok<List<ArtistSummary>>>(result).value.map { it.browseId })
            assertTrue(incomplete)
        }

    private fun page(
        id: String,
        name: String,
        next: String?,
    ): String {
        val continuation = next?.let { ""","continuations":[{"nextContinuationData":{"continuation":"$it"}}]""" }.orEmpty()
        return """{"contents":{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
            "navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$name"}]}}}]}}]$continuation}}}"""
    }
}
