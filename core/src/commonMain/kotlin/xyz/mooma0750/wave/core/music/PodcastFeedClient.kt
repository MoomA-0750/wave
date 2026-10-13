package xyz.mooma0750.wave.core.music

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * RSS のポッドキャスト（ADR-0012）。番組を Apple の目録で探し、フィードを読む。
 * http には認証情報を持たないクライアントを渡す（ADR-0003）。リダイレクトは https の間だけ追う設定にしておくこと。
 */
class PodcastFeedClient(
    private val http: HttpClient,
    /** 目録の国（`JP` など）。 */
    private val country: String,
    private val userAgent: String,
) {
    /** 番組を探す。読めなければ null。 */
    suspend fun search(term: String): List<PodcastSummary>? {
        if (term.isBlank()) return emptyList()
        // URL を打ち込まれたら、そのフィードを読んで1件にする
        if (term.trim().let { it.contains("://") || it.startsWith("feed:") }) {
            val url = Feed.normalizeFeedUrl(term) ?: return emptyList()
            return when (val result = show(url)) {
                is FeedResult.Ok -> listOf(result.show.subscription.summary)
                is FeedResult.Failed -> emptyList()
            }
        }
        return try {
            val response =
                http.get(DIRECTORY_URL) {
                    parameter("term", term.trim())
                    parameter("media", "podcast")
                    parameter("entity", "podcast")
                    parameter("limit", 25)
                    parameter("country", country)
                    header(HttpHeaders.UserAgent, userAgent)
                }
            if (response.status.value !in 200..299) null else directoryResults(response.bodyAsText())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    /** フィードを読む。 */
    suspend fun show(feedUrl: String): FeedResult {
        if (!Feed.isAllowedFeedUrl(feedUrl)) return FeedResult.Failed(FeedFailure.NotAllowed)
        return try {
            val response = http.get(feedUrl) { header(HttpHeaders.UserAgent, userAgent) }
            if (response.status.value !in 200..299) return FeedResult.Failed(FeedFailure.Http(response.status.value))
            val bytes = response.bodyAsChannel().readRemaining(MAX_FEED_BYTES + 1).readByteArray()
            if (bytes.size > MAX_FEED_BYTES) return FeedResult.Failed(FeedFailure.TooLarge)
            val show = FeedParser.parse(feedUrl, bytes.decodeToString()) ?: return FeedResult.Failed(FeedFailure.NotAFeed)
            FeedResult.Ok(show)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            FeedResult.Failed(FeedFailure.Network)
        }
    }

    companion object {
        const val DIRECTORY_URL = "https://itunes.apple.com/search"

        /** 長く続く番組のフィードは数 MB になる。これを超えたら読まない。 */
        const val MAX_FEED_BYTES = 40L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** 目録の応答（`results[]`）。フィードの URL の無い番組は落とす。 */
        fun directoryResults(body: String): List<PodcastSummary> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                fun str(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val feed = str("feedUrl")?.let(Feed::normalizeFeedUrl) ?: return@mapNotNull null
                PodcastSummary(
                    browseId = Feed.showBrowseId(feed),
                    title = str("collectionName") ?: str("trackName") ?: return@mapNotNull null,
                    author = str("artistName"),
                    thumbnailUrl = (str("artworkUrl600") ?: str("artworkUrl100"))?.takeIf(Feed::isAllowedFeedUrl),
                )
            }.distinctBy { it.browseId }
        }
    }
}

sealed interface FeedResult {
    data class Ok(
        val show: FeedShow,
    ) : FeedResult

    data class Failed(
        val failure: FeedFailure,
    ) : FeedResult
}

sealed interface FeedFailure {
    /** https でない・形のおかしい URL。 */
    data object NotAllowed : FeedFailure

    data object Network : FeedFailure

    data class Http(
        val status: Int,
    ) : FeedFailure

    data object TooLarge : FeedFailure

    /** RSS として読めなかった。 */
    data object NotAFeed : FeedFailure
}
