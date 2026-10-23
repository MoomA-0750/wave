package xyz.mooma0750.wave.core.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * YouTube Music の時刻付きの歌詞。歌詞のタブ（next で見つけた MPLY…）を ANDROID_MUSIC として読むと、行ごとの時刻が付いて返る
 * （WEB_REMIX では時刻なしだけ）。ログインは要らないので、http には認証情報を持たないクライアントを渡し、匿名で送る
 * （ADR-0003。cookie を WEB_REMIX 以外の名乗りで送らない）。送るのは歌詞のタブの ID だけ。
 */
class YouTubeTimedLyricsClient(
    private val http: HttpClient,
    private val hl: String,
    private val gl: String,
) {
    sealed interface Result {
        data class Found(
            val lyrics: Lyrics.Synced,
        ) : Result

        /** 時刻付きが無い（MV など時刻の無い歌詞・歌詞の無い曲）。 */
        data object NotFound : Result

        /** 通信・応答の失敗。 */
        data object Failed : Result
    }

    suspend fun timed(browseId: String): Result =
        try {
            val body =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "ANDROID_MUSIC")
                            put("clientVersion", CLIENT_VERSION)
                            put("hl", hl)
                            put("gl", gl)
                        }
                    }
                    put("browseId", browseId)
                }
            val response =
                http.post("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    setBody(body.toString())
                }
            if (response.status.value !in 200..299) {
                Result.Failed
            } else {
                parse(json.parseToJsonElement(response.bodyAsText()) as JsonObject)?.let { Result.Found(it) } ?: Result.NotFound
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.Failed
        }

    companion object {
        /** 6.x では時刻付きの形にならない（2026-10 に確かめた）。7.27.52・8.40.54 は返る。 */
        const val CLIENT_VERSION = "7.27.52"
        private const val USER_AGENT = "com.google.android.apps.youtube.music/$CLIENT_VERSION (Linux; U; Android 11) gzip"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 応答の `…timedLyricsModel.lyricsData` の行と出典。時刻（cueRange）の無い行ばかり（MV の歌詞）・行が無いなら null。
         * 間奏の「♪」の行は空の行にする（画面では行の区切りとして出す）。
         */
        fun parse(root: JsonObject): Lyrics.Synced? {
            val data = find(root, "timedLyricsModel")?.get("lyricsData") as? JsonObject ?: return null
            val lines =
                (data["timedLyricsData"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val line = element as? JsonObject ?: return@mapNotNull null
                    val start = ((line["cueRange"] as? JsonObject)?.get("startTimeMilliseconds") as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                    val text = (line["lyricLine"] as? JsonPrimitive)?.content.orEmpty().trim()
                    LyricLine(start, if (text == "♪") "" else text)
                }
            if (lines.isEmpty()) return null
            val source = (data["sourceMessage"] as? JsonPrimitive)?.content
            return Lyrics.Synced(lines.sortedBy { it.startMs }, source)
        }

        private fun find(
            element: JsonElement,
            key: String,
        ): JsonObject? =
            when (element) {
                is JsonObject -> element[key] as? JsonObject ?: element.values.firstNotNullOfOrNull { find(it, key) }
                is JsonArray -> element.firstNotNullOfOrNull { find(it, key) }
                else -> null
            }
    }
}
