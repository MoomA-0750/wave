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
    fun playTargetIsTheRequestedOrTheLastWriterOrAnyOnlinePlayer() {
        val players = setOf("self", "pc", "phone")
        // 指定があればそれ（つながっている player のときだけ）
        assertEquals("phone", Views.target("phone", "self", players, setOf("phone", "pc"), "pc"))
        assertNull(Views.target("phone", "self", players, setOf("pc"), "pc"), "つながっていない端末を行き先にした")
        assertNull(Views.target("self", "self", players, setOf("self"), "pc"), "wave-api 自身を行き先にした")
        assertNull(Views.target("stranger", "self", players, setOf("stranger"), null), "家の player でない端末を行き先にした")
        // 無ければ最後に書いた端末
        assertEquals("pc", Views.target(null, "self", players, setOf("pc"), "pc"))
        // 最後に書いた端末が切れていれば、つながっている家の端末（wave-api 自身は選ばない）
        assertEquals("phone", Views.target(null, "self", players, setOf("self", "phone"), "pc"))
        assertNull(Views.target(null, "self", players, setOf("self"), "pc"), "つながっている端末が無いのに行き先を選んだ")
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
    fun jumpRebuildsTheQueueFromTheChosenUpcomingSong() {
        val snapshot = QueueBuilder.snapshot((1..40).map { song(videoId(it)) }, 0, null, "synthetic-continuation", null)
        val cursor = CursorBody("ref", snapshot.items[0].itemId, 5_000, true, HandoffRepeat.All, 0, null, 1, 3_000, true, "tag", 0)
        // index は GET /v1/queue の何番目か（0 が次の曲）。選んだ曲の頭から、繰り返しはそのまま
        val handoffId = "00000000-0000-4000-8000-000000000002"
        val jumped = Views.jump(snapshot, cursor, 29, null, handoffId, 0)!!
        val current = jumped.items.first { it.itemId == jumped.currentItemId }
        assertEquals(videoId(31), current.videoId)
        assertEquals(snapshot.items.first { it.videoId == videoId(31) }.itemId, current.itemId, "項目の ID を振り直した")
        assertEquals(0, jumped.positionMs)
        assertEquals(0, jumped.listenedMs)
        assertEquals(HandoffRepeat.All, jumped.repeat)
        // 前の曲を 20 曲までに切り直して、受け手の検査を通る
        assertEquals(videoId(11), jumped.items.first().videoId)
        assertIs<HandoffDecodeResult.Ok>(HandoffCodec.decode(HandoffCodec.encode(jumped), handoffId))
        // videoId だけでも、両方でも（合わなければ飛ばない）
        assertEquals(videoId(3), Views.jump(snapshot, cursor, null, videoId(3), "h3", 0)!!.let { j -> j.items.first { it.itemId == j.currentItemId }.videoId })
        assertNull(Views.jump(snapshot, cursor, 1, videoId(5), "h4", 0), "番号と曲が合わないのに飛んだ")
        assertNull(Views.jump(snapshot, cursor, 39, null, "h5", 0), "キューの外へ飛んだ")
        assertNull(Views.jump(snapshot, cursor, null, videoId(1), "h6", 0), "今の曲より前へ飛んだ")
    }

    @Test
    fun nowPlayingArtworkIsLarge() {
        val item = QueueBuilder.item(song(videoId(1)).copy(thumbnailUrl = "https://lh3.googleusercontent.com/synthetic=w120-h120-l90-rj"), xyz.mooma0750.wave.handoff.HandoffOrigin.Source)
        assertEquals("https://lh3.googleusercontent.com/synthetic=w544-h544", Views.nowPlaying(item).artUrl)
        // 一覧（キュー）は小さいまま
        assertEquals("https://lh3.googleusercontent.com/synthetic=w120-h120-l90-rj", Views.track(item).artUrl)
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

        override suspend fun jump(request: JumpRequest) = HttpStatusCode.NotFound to """{"error":"not_found"}"""

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
            assertEquals(HttpStatusCode.BadRequest, post("/v1/queue/jump", """{}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/v1/queue/jump", """{"index":-1}""").status)
            assertEquals(HttpStatusCode.NotFound, post("/v1/queue/jump", """{"index":3}""").status)
            assertEquals(listOf(Triple(ControlAction.Pause, null, null), Triple(ControlAction.Seek, 1000L, null), Triple(ControlAction.Volume, null, 30)), backend.controls)
        }
}
