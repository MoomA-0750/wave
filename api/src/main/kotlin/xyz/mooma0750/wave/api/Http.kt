package xyz.mooma0750.wave.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.mooma0750.wave.core.music.HomeItem
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.handoff.v2.ControlAction
import java.security.MessageDigest

/** HTTP の口（docs/wave-api.md）。health 以外は `Authorization: Bearer <トークン>` が要る。 */
interface ApiBackend {
    val loggedIn: Boolean
    val changes: kotlinx.coroutines.flow.SharedFlow<Unit>

    suspend fun playback(): PlaybackView

    suspend fun queue(): List<TrackView>

    suspend fun devices(): List<DeviceView>

    suspend fun control(
        action: ControlAction,
        positionMs: Long?,
        volumePercent: Int?,
    ): Boolean

    suspend fun transfer(deviceId: String): Boolean

    suspend fun play(request: PlayRequest): Pair<HttpStatusCode, String>

    suspend fun queueAdd(request: QueueRequest): Pair<HttpStatusCode, String>

    suspend fun jump(request: JumpRequest): Pair<HttpStatusCode, String>

    suspend fun search(query: String): CallResult<List<TrackView>>

    suspend fun playlists(): CallResult<List<PlaylistView>>

    suspend fun playlistTracks(id: String): CallResult<List<TrackView>>

    suspend fun suggestions(): CallResult<List<ShelfView>>

    suspend fun pair(offer: String): ApiHub.PairState

    fun pairState(): ApiHub.PairState
}

@Serializable
data class ControlRequest(
    val action: String,
    val positionMs: Long? = null,
    val volumePercent: Int? = null,
)

@Serializable
data class TransferRequest(
    val deviceId: String,
)

@Serializable
data class PlayRequest(
    val videoId: String? = null,
    val playlistId: String? = null,
    val albumBrowseId: String? = null,
    val startVideoId: String? = null,
    val shuffle: Boolean = false,
    val deviceId: String? = null,
)

@Serializable
data class QueueRequest(
    val videoIds: List<String>,
    val next: Boolean = false,
)

@Serializable
data class JumpRequest(
    /** GET /v1/queue の何番目か（0 が次の曲）。 */
    val index: Int? = null,
    val videoId: String? = null,
)

@Serializable
data class PairRequest(
    val offer: String,
)

@Serializable
data class PairView(
    val state: String,
    val code: String? = null,
    val peerName: String? = null,
    val reason: String? = null,
)

@Serializable
data class OkView(
    val ok: Boolean = true,
    val deviceId: String? = null,
)

fun ApiHub.PairState.view(): PairView =
    when (this) {
        ApiHub.PairState.Idle -> PairView("idle")
        is ApiHub.PairState.Working -> PairView("working")
        is ApiHub.PairState.ShowCode -> PairView("show_code", code = code, peerName = peerName)
        is ApiHub.PairState.Joined -> PairView("joined", peerName = peerName)
        is ApiHub.PairState.Failed -> PairView("failed", reason = reason)
    }

private fun <T> encode(
    serializer: KSerializer<T>,
    value: T,
): String = Views.json.encodeToString(serializer, value)

private suspend fun ApplicationCall.json(
    text: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.error(
    status: HttpStatusCode,
    code: String,
) = json(encode(ErrorView.serializer(), ErrorView(code)), status)

/** 失敗の種類を HTTP の状態と短い名前にする（ログインが無ければ 401 login_required）。 */
fun failure(kind: FailureKind?): Pair<HttpStatusCode, String> =
    when (kind) {
        FailureKind.NotLoggedIn -> HttpStatusCode.Unauthorized to "login_required"
        null -> HttpStatusCode.NotFound to "not_found"
        else -> HttpStatusCode.BadGateway to kind.name.lowercase()
    }

private suspend fun <T> ApplicationCall.result(
    result: CallResult<T>,
    serializer: KSerializer<T>,
) = when (result) {
    is CallResult.Ok -> json(encode(serializer, result.value))
    is CallResult.Failed -> failure(result.kind).let { (status, code) -> error(status, code) }
}

private suspend fun <T> ApplicationCall.body(serializer: KSerializer<T>): T? = runCatching { Views.json.decodeFromString(serializer, receiveText()) }.getOrNull()

/** 長さに依らない比べ方（トークンの照合）。 */
fun tokenMatches(
    header: String?,
    token: String,
): Boolean {
    val given = header?.removePrefix("Bearer ")?.takeIf { header.startsWith("Bearer ") } ?: return false
    return MessageDigest.isEqual(given.encodeToByteArray(), token.encodeToByteArray())
}

fun Application.apiModule(
    backend: ApiBackend,
    token: String,
) {
    install(
        createApplicationPlugin("BearerToken") {
            onCall { call ->
                if (call.request.path() == "/v1/health") return@onCall
                if (!tokenMatches(call.request.headers["Authorization"], token)) call.error(HttpStatusCode.Unauthorized, "token_required")
            }
        },
    )
    routing {
        get("/v1/health") { call.json("""{"ok":true}""") }
        get("/v1/playback") { call.json(encode(PlaybackView.serializer(), backend.playback())) }
        get("/v1/queue") { call.json(encode(ListSerializer(TrackView.serializer()), backend.queue())) }
        get("/v1/devices") { call.json(encode(ListSerializer(DeviceView.serializer()), backend.devices())) }
        post("/v1/control") {
            val request = call.body(ControlRequest.serializer()) ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            val action =
                when (request.action) {
                    "play" -> ControlAction.Play
                    "pause" -> ControlAction.Pause
                    "next" -> ControlAction.Next
                    "previous" -> ControlAction.Previous
                    "seek" -> ControlAction.Seek.takeIf { request.positionMs != null }
                    "volume" -> ControlAction.Volume.takeIf { request.volumePercent in 0..100 }
                    // シャッフル・繰り返しは連携の取り決めに無い（鳴らしている端末の画面で切り替える）
                    "shuffle", "repeat" -> return@post call.error(HttpStatusCode.BadRequest, "unsupported")
                    else -> null
                } ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            if (backend.control(action, request.positionMs, request.volumePercent)) call.json(encode(OkView.serializer(), OkView())) else call.error(HttpStatusCode.Conflict, "not_sent")
        }
        post("/v1/transfer") {
            val request = call.body(TransferRequest.serializer()) ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            if (backend.transfer(request.deviceId)) call.json(encode(OkView.serializer(), OkView(deviceId = request.deviceId))) else call.error(HttpStatusCode.Conflict, "not_sent")
        }
        post("/v1/play") {
            val request = call.body(PlayRequest.serializer()) ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            val (status, text) = backend.play(request)
            call.json(text, status)
        }
        post("/v1/queue") {
            val request = call.body(QueueRequest.serializer())?.takeIf { it.videoIds.isNotEmpty() } ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            val (status, text) = backend.queueAdd(request)
            call.json(text, status)
        }
        post("/v1/queue/jump") {
            val request = call.body(JumpRequest.serializer())?.takeIf { (it.index ?: 0) >= 0 && (it.index != null || it.videoId != null) } ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            val (status, text) = backend.jump(request)
            call.json(text, status)
        }
        get("/v1/search") {
            val query = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return@get call.error(HttpStatusCode.BadRequest, "bad_request")
            call.result(backend.search(query), ListSerializer(TrackView.serializer()))
        }
        get("/v1/playlists") { call.result(backend.playlists(), ListSerializer(PlaylistView.serializer())) }
        get("/v1/playlists/{id}/tracks") {
            val id = call.parameters["id"]?.takeIf { Regex("^[A-Za-z0-9_-]{2,128}$").matches(it) } ?: return@get call.error(HttpStatusCode.BadRequest, "bad_request")
            call.result(backend.playlistTracks(id), ListSerializer(TrackView.serializer()))
        }
        get("/v1/suggestions") { call.result(backend.suggestions(), ListSerializer(ShelfView.serializer())) }
        get("/v1/events") {
            // 再生の変化のたびに playback を流す（多くても 0.5 秒に1回。15 秒ごとに空の行で接続を保つ）
            call.respondTextWriter(ContentType.Text.EventStream) {
                coroutineScope {
                    val keepAlive =
                        launch {
                            while (true) {
                                delay(15_000)
                                write(": keepalive\n\n")
                                flush()
                            }
                        }
                    try {
                        backend.changes.onStart { emit(Unit) }.sample(500).collectLatest {
                            write("event: playback\ndata: ${encode(PlaybackView.serializer(), backend.playback())}\n\n")
                            flush()
                        }
                    } finally {
                        keepAlive.cancel()
                    }
                }
            }
        }
        post("/v1/admin/pair") {
            val request = call.body(PairRequest.serializer()) ?: return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            call.json(encode(PairView.serializer(), backend.pair(request.offer).view()))
        }
        get("/v1/admin/pair") { call.json(encode(PairView.serializer(), backend.pairState().view())) }
    }
}

/** ApiHub を HTTP の口につなぐ。 */
class HubBackend(
    private val hub: ApiHub,
) : ApiBackend {
    private val builder = QueueBuilder(hub.music)
    override val loggedIn: Boolean get() = hub.loggedIn
    override val changes = hub.changes

    override suspend fun playback() = hub.playback()

    override suspend fun queue() = hub.queue()

    override suspend fun devices() = hub.devices()

    override suspend fun control(
        action: ControlAction,
        positionMs: Long?,
        volumePercent: Int?,
    ) = hub.control(action, positionMs, volumePercent)

    override suspend fun transfer(deviceId: String) = hub.transfer(deviceId)

    override suspend fun play(request: PlayRequest): Pair<HttpStatusCode, String> {
        if (!hub.loggedIn) return errorBody(HttpStatusCode.Unauthorized, "login_required")
        val built =
            when {
                request.playlistId != null -> builder.playlist(request.playlistId, request.startVideoId ?: request.videoId, request.shuffle)
                request.albumBrowseId != null -> builder.album(request.albumBrowseId, request.startVideoId ?: request.videoId, request.shuffle)
                request.videoId != null -> builder.radio(request.videoId)
                else -> return errorBody(HttpStatusCode.BadRequest, "bad_request")
            }
        val snapshot =
            when (built) {
                is QueueBuilder.Built.Ok -> built.snapshot
                is QueueBuilder.Built.Failed -> return failure(built.kind).let { (status, code) -> errorBody(status, code) }
            }
        return playResult(hub.play(snapshot, request.deviceId))
    }

    private fun playResult(result: ApiHub.PlayResult) =
        when (result) {
            is ApiHub.PlayResult.Sent -> HttpStatusCode.OK to encode(OkView.serializer(), OkView(deviceId = result.deviceId))
            ApiHub.PlayResult.NoDevice -> errorBody(HttpStatusCode.Conflict, "no_device")
            ApiHub.PlayResult.NotSent -> errorBody(HttpStatusCode.Conflict, "not_sent")
        }

    override suspend fun queueAdd(request: QueueRequest): Pair<HttpStatusCode, String> {
        if (!hub.loggedIn) return errorBody(HttpStatusCode.Unauthorized, "login_required")
        val items = builder.items(request.videoIds) ?: return errorBody(HttpStatusCode.Unauthorized, "login_required")
        if (items.isEmpty()) return errorBody(HttpStatusCode.NotFound, "not_found")
        return if (hub.queueAdd(items, request.next)) HttpStatusCode.OK to encode(OkView.serializer(), OkView()) else errorBody(HttpStatusCode.Conflict, "not_sent")
    }

    override suspend fun jump(request: JumpRequest): Pair<HttpStatusCode, String> =
        when (val result = hub.jump(request.index, request.videoId)) {
            ApiHub.JumpResult.NotFound -> errorBody(HttpStatusCode.NotFound, "not_found")
            is ApiHub.JumpResult.Played -> playResult(result.result)
        }

    override suspend fun search(query: String): CallResult<List<TrackView>> =
        when (val result = hub.music.searchSongs(query)) {
            is CallResult.Ok -> CallResult.Ok(result.value.songs.map(Views::track))
            is CallResult.Failed -> result
        }

    override suspend fun playlists(): CallResult<List<PlaylistView>> =
        when (val result = hub.music.libraryPlaylists()) {
            is CallResult.Ok -> CallResult.Ok(result.value.map(Views::playlist))
            is CallResult.Failed -> result
        }

    override suspend fun playlistTracks(id: String): CallResult<List<TrackView>> {
        val tracks = ArrayList<TrackView>()
        var continuation: String? = null
        do {
            when (val page = hub.music.playlist(id, continuation)) {
                is CallResult.Ok -> {
                    tracks += page.value.songs.map(Views::track)
                    continuation = page.value.continuation
                }
                is CallResult.Failed -> if (tracks.isEmpty()) return page else break
            }
        } while (continuation != null && tracks.size < QueueBuilder.MAX_READ)
        return CallResult.Ok(tracks)
    }

    override suspend fun suggestions(): CallResult<List<ShelfView>> =
        when (val result = hub.music.home()) {
            is CallResult.Ok -> CallResult.Ok(result.value.shelves.map(Views::shelf).filter { it.tracks.isNotEmpty() || it.playlists.isNotEmpty() })
            is CallResult.Failed -> result
        }

    override suspend fun pair(offer: String) = hub.pair(offer)

    override fun pairState() = hub.pairState

    private fun errorBody(
        status: HttpStatusCode,
        code: String,
    ) = status to encode(ErrorView.serializer(), ErrorView(code))
}
