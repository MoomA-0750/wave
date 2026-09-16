package xyz.mooma0750.wave.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/** 認証ヘッダを送ってよい宛先。host は完全一致、path は前方一致。 */
internal data class AuthRoute(
    val host: String,
    val pathPrefix: String,
)

/** Phase 3 で再生統計などが要れば、ここに行を足す。 */
internal val AUTH_ROUTES: List<AuthRoute> =
    listOf(
        AuthRoute("music.youtube.com", "/youtubei/v1/"),
    )

internal val AUTH_HEADERS: Set<String> = setOf("cookie", "authorization", "x-goog-authuser")

class AuthRouteViolation(
    host: String,
) : IllegalStateException("auth headers blocked for host $host")

/** 認証ヘッダを持つリクエストを、この宛先へ送ってよいか。 */
internal fun isAuthRouteAllowed(url: Url): Boolean =
    url.protocol.name == "https" &&
        AUTH_ROUTES.any { route -> url.host == route.host && url.encodedPath.startsWith(route.pathPrefix) }

internal fun hasAuthHeaders(headerNames: Set<String>): Boolean = headerNames.any { it.lowercase() in AUTH_HEADERS }

/**
 * HttpClientEngine を包み、実際に送る直前のリクエストを検査する。
 * innertubex は呼び出し側のクライアントのエンジンだけを使って HttpClient を作り直すことがあるので、
 * 関所をプラグインではなくエンジン層に置く。
 *
 * @param onSetCookieNames 応答に Set-Cookie があったときに、宛先ホストと cookie の名前だけを渡す（値は渡さない）
 */
@OptIn(InternalAPI::class)
class AuthGuardEngine(
    private val delegate: HttpClientEngine,
    private val onSetCookieNames: (host: String, names: List<String>) -> Unit = { _, _ -> },
) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher get() = delegate.dispatcher
    override val config: HttpClientEngineConfig get() = delegate.config
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> get() = delegate.supportedCapabilities
    override val coroutineContext: CoroutineContext get() = delegate.coroutineContext

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        if (hasAuthHeaders(data.headers.names()) && !isAuthRouteAllowed(data.url)) {
            throw AuthRouteViolation(data.url.host)
        }
        val response = delegate.execute(data)
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
        if (!setCookies.isNullOrEmpty()) {
            onSetCookieNames(data.url.host, setCookies.map { it.substringBefore('=').trim() })
        }
        return response
    }

    override fun close() = delegate.close()
}

/** https かつホストが googlevideo.com 配下の場合だけ true。解読後のストリーム URL に使う（Phase 3）。 */
fun isAllowedMediaUrl(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (parsed.protocol.name != "https" || parsed.user != null || parsed.password != null) return false
    return parsed.host.endsWith(".googlevideo.com")
}
