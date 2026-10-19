package xyz.mooma0750.wave.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.MutableSharedFlow
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffDecodeResult
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.v2.ControlAction
import xyz.mooma0750.wave.handoff.v2.CursorBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 合成値だけを使う。 */
class ApiTest {
    private fun song(id: String) = Song(id, "Title $id", listOf(ArtistRef("Artist", "UCartist")), null, 200, null)

    private fun videoId(n: Int) = "vid${n.toString().padStart(8, '0')}"

    @Test
    fun playTargetIsTheRequestedOrTheLastWriterWhenOnline() {
        val players = setOf("self", "pc", "phone")
        // 指定があればそれ（つながっている player のときだけ）
        assertEquals("phone", Views.target("phone", "self", players, setOf("phone", "pc"), "pc"))
        assertNull(Views.target("phone", "self", players, setOf("pc"), "pc"), "つながっていない端末を行き先にした")
        assertNull(Views.target("self", "self", players, setOf("self"), "pc"), "wave-api 自身を行き先にした")
        assertNull(Views.target("stranger", "self", players, setOf("stranger"), null), "家の player でない端末を行き先にした")
        // 無ければ最後に書いた端末
        assertEquals("pc", Views.target(null, "self", players, setOf("pc"), "pc"))
        assertNull(Views.target(null, "self", players, setOf("phone"), "pc"), "切れている最後の端末を行き先にした")
    }

    @Test
    fun builtQueuesPassTheReceiversValidation() {
        val songs = (1..350).map { song(videoId(it)) }
        val snapshot = QueueBuilder.snapshot(songs, 10, null, "synthetic-continuation", null)
        // 受け手が検証して受け付ける形（今の曲の前は 20 曲、後ろは 300 曲まで。後ろを切ったら続きは捨てる）
        assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(HandoffCodec.encode(snapshot), snapshot.handoffId))
        assertEquals(videoId(11), snapshot.items.first { it.itemId == snapshot.currentItemId }.videoId)
        assertNull(snapshot.continuation)
        assertTrue(snapshot.exhausted)
    }

    @Test
    fun upcomingIsTheSongsAfterTheCursor() {
        val snapshot = QueueBuilder.snapshot((1..5).map { song(videoId(it)) }, 0, null, null, null)
        val cursor = CursorBody("ref", snapshot.items[2].itemId, 0, true, HandoffRepeat.Off, 0, null, 1, 0, false, "tag", 0)
        assertEquals(listOf(videoId(4), videoId(5)), Views.upcoming(snapshot, cursor).map { it.videoId })
        assertEquals(videoId(3), Views.currentItem(snapshot, cursor)?.videoId)
    }

    @Test
    fun tokenIsCheckedExactly() {
        assertTrue(tokenMatches("Bearer abc", "abc"))
        assertTrue(!tokenMatches("Bearer abcd", "abc"))
        assertTrue(!tokenMatches("abc", "abc"))
        assertTrue(!tokenMatches(null, "abc"))
    }

    private class FakeBackend : ApiBackend {
        override var loggedIn = false
        override val changes = MutableSharedFlow<Unit>()
        val controls = ArrayList<Triple<ControlAction, Long?, Int?>>()

        override suspend fun playback() = PlaybackView(true, loggedIn, false, "off", null, null, "2026-10-19T00:00:00Z", null, null)

        override suspend fun queue() = emptyList<TrackView>()

        override suspend fun devices() = emptyList<DeviceView>()

        override suspend fun control(
            action: ControlAction,
            positionMs: Long?,
            volumePercent: Int?,
        ): Boolean {
            controls += Triple(action, positionMs, volumePercent)
            return true
        }

        override suspend fun transfer(deviceId: String) = false

        override suspend fun play(request: PlayRequest) = HttpStatusCode.Unauthorized to """{"error":"login_required"}"""

        override suspend fun queueAdd(request: QueueRequest) = HttpStatusCode.Unauthorized to """{"error":"login_required"}"""

        override suspend fun search(query: String): CallResult<List<TrackView>> = CallResult.Failed(FailureKind.NotLoggedIn)

        override suspend fun playlists(): CallResult<List<PlaylistView>> = CallResult.Failed(FailureKind.NotLoggedIn)

        override suspend fun playlistTracks(id: String): CallResult<List<TrackView>> = CallResult.Failed(FailureKind.NotLoggedIn)

        override suspend fun suggestions(): CallResult<List<ShelfView>> = CallResult.Failed(FailureKind.NotLoggedIn)

        override suspend fun pair(offer: String) = ApiHub.PairState.Failed("not_wave_pair")

        override fun pairState() = ApiHub.PairState.Idle
    }

    @Test
    fun everyEndpointButHealthNeedsTheToken() =
        testApplication {
            val backend = FakeBackend()
            application { apiModule(backend, "synthetic-token") }
            assertEquals(HttpStatusCode.OK, client.get("/v1/health").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/playback").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/playback") { header("Authorization", "Bearer wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/pair") { setBody("""{"offer":"x"}""") }.status)
            val ok = client.get("/v1/playback") { header("Authorization", "Bearer synthetic-token") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertTrue(ok.bodyAsText().contains("\"joined\":true"))
        }

    @Test
    fun loginOnlyEndpointsSayLoginRequiredAndControlsAreMapped() =
        testApplication {
            val backend = FakeBackend()
            application { apiModule(backend, "t") }
            suspend fun get(path: String) = client.get(path) { header("Authorization", "Bearer t") }
            suspend fun post(
                path: String,
                body: String,
            ) = client.post(path) {
                header("Authorization", "Bearer t")
                setBody(body)
            }
            for (path in listOf("/v1/playlists", "/v1/search?q=x", "/v1/suggestions", "/v1/playlists/PLx/tracks")) {
                val response = get(path)
                assertEquals(HttpStatusCode.Unauthorized, response.status, path)
                assertTrue(response.bodyAsText().contains("login_required"), path)
            }
            assertEquals(HttpStatusCode.Unauthorized, post("/v1/play", """{"videoId":"${videoId(1)}"}""").status)
            assertEquals(HttpStatusCode.OK, post("/v1/control", """{"action":"pause"}""").status)
            assertEquals(HttpStatusCode.OK, post("/v1/control", """{"action":"seek","positionMs":1000}""").status)
            assertEquals(HttpStatusCode.OK, post("/v1/control", """{"action":"volume","volumePercent":30}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/v1/control", """{"action":"volume","volumePercent":300}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/v1/control", """{"action":"seek"}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/v1/control", """{"action":"shuffle"}""").status)
            assertEquals(listOf(Triple(ControlAction.Pause, null, null), Triple(ControlAction.Seek, 1000L, null), Triple(ControlAction.Volume, null, 30)), backend.controls)
        }
}
