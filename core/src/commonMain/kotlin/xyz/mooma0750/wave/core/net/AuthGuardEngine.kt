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

/**
 * 認証ヘッダを持つリクエストを、この宛先へ送ってよいか。
 * OkHttp は送信時に `.`・`..` のセグメントを解決するので、それらを含むパスは前方一致を信用せずに拒否する。
 */
internal fun isAuthRouteAllowed(url: Url): Boolean {
    if (url.protocol.name != "https" || url.port != 443 || url.user != null || url.password != null) return false
    val path = url.encodedPath
    val lowered = path.lowercase()
    if ('\\' in path || "%2e" in lowered || "%2f" in lowered || "%5c" in lowered) return false
    if (path.split('/').any { it == "." || it == ".." }) return false
    return AUTH_ROUTES.any { route -> url.host == route.host && path.startsWith(route.pathPrefix) }
}

internal fun hasAuthHeaders(headerNames: Set<String>): Boolean = headerNames.any { it.lowercase() in AUTH_HEADERS }

/**
 * HttpClientEngine を包み、実際に送る直前のリクエストを検査する。
 * innertubex は呼び出し側のクライアントのエンジンだけを使って HttpClient を作り直すことがあるので、
 * 関所をプラグインではなくエンジン層に置く。
 *
 * 検査するのは、リクエストのヘッダと本文（OutgoingContent）側のヘッダの両方。エンジンは送信時に両者を合わせて送る。
 * OkHttp 側に Interceptor・Authenticator・CookieJar を足すと、このガードの後でヘッダが付くので、足さないこと。
 *
 * @param onViolation 送らずに止めたとき、宛先ホストだけを渡す
 * @param onSetCookieNames 応答に Set-Cookie があったときに、宛先ホストと cookie の名前だけを渡す（値は渡さない）
 */
@OptIn(InternalAPI::class)
class AuthGuardEngine(
    private val delegate: HttpClientEngine,
    private val onViolation: (host: String) -> Unit = {},
    private val onSetCookieNames: (host: String, names: List<String>) -> Unit = { _, _ -> },
) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher get() = delegate.dispatcher
    override val config: HttpClientEngineConfig get() = delegate.config
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> get() = delegate.supportedCapabilities
    override val coroutineContext: CoroutineContext get() = delegate.coroutineContext

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        if (hasAuthHeaders(data.headers.names() + data.body.headers.names()) && !isAuthRouteAllowed(data.url)) {
            onViolation(data.url.host)
            throw AuthRouteViolation(data.url.host)
        }
        val response = delegate.execute(data)
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
        if (!setCookies.isNullOrEmpty()) {
            // "=" が無い Set-Cookie は、行全体が名前の無い cookie の値になるので、名前として渡さない
            onSetCookieNames(data.url.host, setCookies.map { if ('=' in it) it.substringBefore('=').trim() else "(unnamed)" })
        }
        return response
    }

    override fun close() = delegate.close()
}

/** 解読後のストリーム URL として使ってよいか。https・ポート 443・googlevideo.com 配下・パスが /videoplayback のときだけ true（ADR-0003）。 */
fun isAllowedMediaUrl(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (parsed.protocol.name != "https" || parsed.port != 443 || parsed.user != null || parsed.password != null) return false
    return parsed.host.endsWith(".googlevideo.com") && parsed.encodedPath == "/videoplayback"
}
