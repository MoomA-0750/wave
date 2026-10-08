package xyz.mooma0750.wave.core.music

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * アーティストの YouTube（www.youtube.com）のチャンネルの投稿（本人のお知らせ）を読む。ライブ・ツアーの告知を拾うのに使う。
 * YouTube Music ではないので、http には認証情報を持たないクライアントを渡す（ADR-0003。YouTube Music の cookie を www.youtube.com へ送らない）。
 */
class ChannelPostsClient(
    private val http: HttpClient,
    private val hl: String,
    private val gl: String,
) {
    /** 新しい順の投稿。読めなければ null。 */
    suspend fun posts(channelId: String): List<ArtistPost>? =
        try {
            val body =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", CLIENT_VERSION)
                            put("hl", hl)
                            put("gl", gl)
                        }
                    }
                    put("browseId", channelId)
                    put("params", POSTS_PARAMS)
                }
            val response =
                http.post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Origin, "https://www.youtube.com")
                    setBody(body.toString())
                }
            if (response.status.value !in 200..299) null else MusicParsers.artistPosts(json.parseToJsonElement(response.bodyAsText()) as JsonObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** チャンネルの「投稿」タブを開く params。 */
        const val POSTS_PARAMS = "EgVwb3N0c_IGBAoCSgA="
        const val CLIENT_VERSION = "2.20261001.00.00"
    }
}
