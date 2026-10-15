package xyz.mooma0750.wave.core.music

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.mooma0750.wave.core.net.isPlainHttps443

/** HLS のマスタープレイリストの1本（画質ごとの媒体プレイリスト）。 */
data class HlsVariant(
    val bandwidth: Long,
    val codecs: String?,
    val url: String,
    /** 映像の高さ（RESOLUTION の縦）。書いていなければ null。 */
    val height: Int? = null,
)

/**
 * ライブ配信（YouTube の生配信）の検索と、再生に使う HLS の解決（応答の読み取りは純粋な関数）。
 * - 検索は YouTube（www.youtube.com）の「ライブ」の絞り込み。YouTube Music の検索にはライブの絞り込みが無い
 * - 再生は ANDROID クライアントの /player の hlsManifestUrl。WEB_REMIX・WEB は匿名では再生できず、IOS は HLS を返さない（2026-10 に確かめた）
 */
object LiveParsers {
    /** YouTube の検索（ライブで絞り込み）の結果と続き。ライブのバッジが付いた動画だけを取る。 */
    fun webSearch(root: JsonObject): SongPage {
        val songs =
            findAll(root, "videoRenderer").mapNotNull { video ->
                if (!isLiveNow(video)) return@mapNotNull null
                val videoId = video.path("videoId").string() ?: return@mapNotNull null
                val title = video.path("title", "runs").arrayItems().joinToString("") { it.path("text").string().orEmpty() }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val owner = video.path("ownerText", "runs").firstArrayItem().path("text").string()
                Song(
                    videoId = videoId,
                    title = title,
                    artists = listOfNotNull(owner?.let { ArtistRef(it, null) }),
                    album = null,
                    durationSeconds = null,
                    thumbnailUrl = video.path("thumbnail", "thumbnails").arrayItems().lastOrNull().path("url").string(),
                    kind = MusicVideoType.Ugc,
                    isLive = true,
                )
            }
        val continuation = findAll(root, "continuationItemRenderer").firstNotNullOfOrNull { it.path("continuationEndpoint", "continuationCommand", "token").string() }
        return SongPage(songs.distinctBy { it.videoId }, continuation)
    }

    private fun isLiveNow(video: JsonObject): Boolean =
        video.path("badges").arrayItems().any { it.path("metadataBadgeRenderer", "style").string() == "BADGE_STYLE_TYPE_LIVE_NOW" } ||
            video.path("thumbnailOverlays").arrayItems().any { it.path("thumbnailOverlayTimeStatusRenderer", "style").string() == "LIVE" }

    /** /player の応答から、再生できるライブの HLS のマスタープレイリストの URL。ライブでない・再生できないなら null。 */
    fun hlsManifestUrl(player: JsonObject): String? {
        if (player.path("playabilityStatus", "status").string() != "OK") return null
        val url = player.path("streamingData", "hlsManifestUrl").string() ?: return null
        return url.takeIf(::isAllowedLiveManifestUrl)
    }

    /** /player の応答の URL の寿命（秒）。 */
    fun expiresInSeconds(player: JsonObject): Long? = (player.path("streamingData", "expiresInSeconds") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    /** /player の応答の videoDetails がライブ配信中を示すか。 */
    fun isLive(player: JsonObject): Boolean = player.path("videoDetails", "isLive").asBoolean() == true

    /** マスタープレイリストの画質の一覧。相対の URL はマスターの URL を基準に直す。 */
    fun variants(
        master: String,
        masterUrl: String,
    ): List<HlsVariant> {
        val lines = master.lines().map { it.trim() }
        val variants = mutableListOf<HlsVariant>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attributes = attributesOf(line.removePrefix("#EXT-X-STREAM-INF:"))
                val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
                val height = attributes["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
                if (uri != null && bandwidth != null) variants += HlsVariant(bandwidth, attributes["CODECS"], resolveUrl(masterUrl, uri), height)
            }
            index++
        }
        return variants
    }

    /**
     * 音を聴くための1本を選ぶ。AAC-LC（mp4a.40.2）の中で最も帯域の小さいもの（映像も同じ流れに入っているので、小さい画質で通信量を抑える）。
     * AAC-LC が無ければ、全体で最も帯域の小さいもの。
     */
    fun chooseAudioVariant(variants: List<HlsVariant>): HlsVariant? =
        variants.filter { it.codecs?.contains("mp4a.40.2") == true }.minByOrNull { it.bandwidth }
            ?: variants.minByOrNull { it.bandwidth }

    /**
     * 映像を見るための1本を選ぶ。高さ MAX_VIDEO_HEIGHT（720）以下で最も帯域の大きいもの（AAC-LC を先に）。
     * 高さが書かれていなければ音のための1本と同じ（小さい画質のまま）。
     */
    fun chooseVideoVariant(variants: List<HlsVariant>): HlsVariant? {
        val fitting = variants.filter { it.height != null && it.height <= MAX_VIDEO_HEIGHT }
        return fitting.filter { it.codecs?.contains("mp4a.40.2") == true }.maxByOrNull { it.bandwidth }
            ?: fitting.maxByOrNull { it.bandwidth }
            ?: chooseAudioVariant(variants)
    }

    /** 映像を見るときの画質の上限（高さ）。通信量と、スマホ・小窓で見るときの見え方の釣り合い。 */
    const val MAX_VIDEO_HEIGHT = 720

    private fun attributesOf(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var index = 0
        while (index < text.length) {
            val eq = text.indexOf('=', index)
            if (eq < 0) break
            val name = text.substring(index, eq).trim()
            var end: Int
            val value: String
            if (eq + 1 < text.length && text[eq + 1] == '"') {
                val close = text.indexOf('"', eq + 2).let { if (it < 0) text.length else it }
                value = text.substring(eq + 2, close)
                end = text.indexOf(',', close).let { if (it < 0) text.length else it }
            } else {
                end = text.indexOf(',', eq).let { if (it < 0) text.length else it }
                value = text.substring(eq + 1, end)
            }
            result[name] = value
            index = end + 1
        }
        return result
    }

    private fun resolveUrl(
        base: String,
        reference: String,
    ): String {
        if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
        val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        if (reference.startsWith("/")) return origin + reference
        return base.substringBefore('?').substringBeforeLast('/') + "/" + reference
    }

    private fun findAll(
        root: JsonObject,
        key: String,
    ): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        fun walk(element: kotlinx.serialization.json.JsonElement?) {
            when (element) {
                is JsonObject ->
                    element.forEach { (name, value) ->
                        if (name == key && value is JsonObject) found += value
                        walk(value)
                    }
                is kotlinx.serialization.json.JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return found
    }
}

/** ライブの HLS のマスタープレイリストの URL として使ってよいか（https・443・manifest.googlevideo.com の /api/manifest/hls_ 配下）。 */
fun isAllowedLiveManifestUrl(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (!parsed.isPlainHttps443()) return false
    return parsed.host == "manifest.googlevideo.com" && parsed.encodedPath.startsWith("/api/manifest/hls_")
}

/** ライブの HLS の区切り（googlevideo の /videoplayback/…）として取ってよいか。 */
fun isAllowedLiveSegmentUrl(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (!parsed.isPlainHttps443()) return false
    return parsed.host.endsWith(".googlevideo.com") && (parsed.encodedPath == "/videoplayback" || parsed.encodedPath.startsWith("/videoplayback/"))
}

/** 解決したライブの再生先。URL は IP に結び付いた署名を含むので、toString に出さない。 */
class LiveStream internal constructor(
    val videoId: String,
    private val url: String,
    /** 呼び出し側の時計（nowMs）のミリ秒。 */
    val expiresAtMs: Long,
) {
    /** 再生器にだけ渡す。ログ・例外文・UI に出さない。 */
    fun revealUrl(): String = url

    override fun toString(): String = "LiveStream(videoId=$videoId)"
}

/** ライブの解決の失敗の種類。 */
enum class LiveFailure { Network, NotLive, NoVariant }

sealed interface LiveResult {
    class Ok(
        val stream: LiveStream,
    ) : LiveResult

    data class Failed(
        val reason: LiveFailure,
    ) : LiveResult
}

/**
 * ライブ配信の検索と HLS の解決。www.youtube.com へ送るので、http には認証情報を持たないクライアントを渡す
 * （ADR-0003。YouTube Music の cookie を送らない）。
 */
class LiveClient(
    private val http: HttpClient,
    private val hl: String,
    private val gl: String,
    private val nowMs: () -> Long,
) {
    /** YouTube の検索をライブに絞る。continuation を渡したら続きのページ。読めなければ null。 */
    suspend fun search(
        query: String,
        continuation: String? = null,
    ): SongPage? =
        try {
            val body =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", WEB_CLIENT_VERSION)
                            put("hl", hl)
                            put("gl", gl)
                        }
                    }
                    if (continuation != null) {
                        put("continuation", continuation)
                    } else {
                        put("query", query)
                        put("params", LIVE_FILTER)
                    }
                }
            val response =
                http.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Origin, "https://www.youtube.com")
                    setBody(body.toString())
                }
            if (response.status.value !in 200..299) null else LiveParsers.webSearch(json.parseToJsonElement(response.bodyAsText()) as JsonObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

    /** ライブの HLS（音を聴くための1本、video なら映像を見るための1本の媒体プレイリスト）を解決する。 */
    suspend fun resolve(
        videoId: String,
        video: Boolean = false,
    ): LiveResult {
        val requestedAt = nowMs()
        val player =
            try {
                val body =
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "ANDROID")
                                put("clientVersion", ANDROID_CLIENT_VERSION)
                                put("androidSdkVersion", 30)
                                put("osName", "Android")
                                put("osVersion", "11")
                                put("hl", hl)
                                put("gl", gl)
                            }
                        }
                        put("videoId", videoId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                    }
                val response =
                    http.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.UserAgent, ANDROID_USER_AGENT)
                        header("X-YouTube-Client-Name", "3")
                        header("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
                        setBody(body.toString())
                    }
                if (response.status.value !in 200..299) return LiveResult.Failed(LiveFailure.Network)
                json.parseToJsonElement(response.bodyAsText()) as JsonObject
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return LiveResult.Failed(LiveFailure.Network)
            }
        val masterUrl = LiveParsers.hlsManifestUrl(player) ?: return LiveResult.Failed(LiveFailure.NotLive)
        val master =
            try {
                val response = http.get(masterUrl)
                if (response.status.value !in 200..299) return LiveResult.Failed(LiveFailure.Network)
                response.bodyAsText()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return LiveResult.Failed(LiveFailure.Network)
            }
        val variants = LiveParsers.variants(master, masterUrl)
        val variant = (if (video) LiveParsers.chooseVideoVariant(variants) else LiveParsers.chooseAudioVariant(variants)) ?: return LiveResult.Failed(LiveFailure.NoVariant)
        if (!isAllowedLiveManifestUrl(variant.url)) return LiveResult.Failed(LiveFailure.NoVariant)
        val lifetimeMs = (LiveParsers.expiresInSeconds(player)?.takeIf { it > 0 } ?: DEFAULT_LIFETIME_SECONDS) * 1000
        return LiveResult.Ok(LiveStream(videoId, variant.url, requestedAt + lifetimeMs - EXPIRY_SAFETY_MS))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** YouTube の検索の「ライブ」の絞り込み。 */
        const val LIVE_FILTER = "EgJAAQ%3D%3D"
        const val WEB_CLIENT_VERSION = "2.20261001.00.00"
        const val ANDROID_CLIENT_VERSION = "20.10.38"
        const val ANDROID_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
        const val DEFAULT_LIFETIME_SECONDS = 6 * 60 * 60L
        const val EXPIRY_SAFETY_MS = 5 * 60 * 1000L
    }
}
