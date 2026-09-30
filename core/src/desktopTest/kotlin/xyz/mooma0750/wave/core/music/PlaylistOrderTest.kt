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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mooma0750.wave.core.Fixtures
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.jsonResponse
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistOrderTest {
    @Test
    fun paramsMatchTheServersOwnSortMenu() {
        // プレイリストのページの並べ替えのメニュー（browse_playlist の sortFilterSubMenuRenderer）の値
        assertEquals("2ggECAIQAw==", PlaylistOrder(PlaylistSort.Added, descending = true).params)
        assertEquals("2ggECAEQAw==", PlaylistOrder(PlaylistSort.Added).params)
        assertEquals("2ggECAEQBQ==", PlaylistOrder(PlaylistSort.Title).params)
        assertEquals("2ggECAEQBg==", PlaylistOrder(PlaylistSort.Artist).params)
        assertEquals("2ggECAEQBw==", PlaylistOrder(PlaylistSort.Album).params)
        assertEquals("2ggECAIQAQ==", PlaylistOrder(PlaylistSort.Published, descending = true).params)
        // カスタムは Default ordering を明示する（自分のプレイリストでは、サーバーが最後に選んだ並べ方を覚えているため）
        assertEquals("2ggA", PlaylistOrder().params)
        assertEquals("2ggA", PlaylistOrder(PlaylistSort.Custom, descending = true).params)
        assertTrue(PlaylistOrder(PlaylistSort.Custom, descending = true).reversedLocally)
        assertFalse(PlaylistOrder(PlaylistSort.Title, descending = true).reversedLocally)
    }

    @Test
    fun storedFormRoundTrips() {
        PlaylistSort.entries.forEach { sort ->
            listOf(false, true).forEach { descending ->
                val order = PlaylistOrder(sort, descending)
                assertEquals(order, PlaylistOrder.decode(order.encode()))
            }
        }
        assertEquals("Published:desc", PlaylistOrder(PlaylistSort.Published, descending = true).encode())
        assertNull(PlaylistOrder.decode("Votes:asc"))
        assertNull(PlaylistOrder.decode("Title"))
    }

    @Test
    fun firstPageTellsWhetherTheServerCanSort() {
        assertTrue(MusicParsers.playlistPage(Fixtures.json("browse_playlist")).sortable)
        assertFalse(MusicParsers.playlistPage(Fixtures.json("browse_playlist_continuation")).sortable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bodies = CopyOnWriteArrayList<JsonObject>()
    private val client =
        createWaveHttpClient(
            AuthGuardEngine(
                MockEngine { request ->
                    if (!request.url.encodedPath.endsWith("/browse")) return@MockEngine respond("", HttpStatusCode.NotFound)
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    bodies += body
                    jsonResponse(Fixtures.text(if ("continuation" in body || request.url.parameters.names().any { it.contains("continuation") || it == "ctoken" }) "browse_playlist_continuation" else "browse_playlist"))
                },
            ),
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        client.close()
    }

    @Test
    fun onlyTheFirstPageCarriesTheOrder(): Unit =
        runBlocking {
            val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync", 1)))
            val session = WaveSession.create(httpClient = client, credentials = credentials, scope = scope)
            withTimeout(5_000) { session.state.first { it == SessionState.Active } }
            val music = MusicRepository(session)
            val first = music.playlist("PLx", order = PlaylistOrder(PlaylistSort.Title, descending = true))
            assertEquals("2ggECAIQBQ==", bodies.single()["params"]?.jsonPrimitive?.content)
            music.playlist("PLx", continuation = "token")
            assertNull(bodies.last()["params"], "続きのページに並べ方を付けた")
            music.playlist("PLx", order = PlaylistOrder())
            assertEquals("2ggA", bodies.last()["params"]?.jsonPrimitive?.content)
            music.playlist("PLx")
            assertNull(bodies.last()["params"], "並べ方を指定しない読み込みに並べ方を付けた")
            assertTrue(first is xyz.mooma0750.wave.core.session.CallResult.Ok)
        }
}
