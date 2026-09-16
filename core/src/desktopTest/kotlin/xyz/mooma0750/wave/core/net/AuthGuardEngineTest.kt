package xyz.mooma0750.wave.core.net

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthGuardEngineTest {
    private val sent = CopyOnWriteArrayList<HttpRequestData>()
    private val setCookieNames = CopyOnWriteArrayList<Pair<String, List<String>>>()
    private val mock =
        MockEngine { request ->
            sent += request
            when (request.url.encodedPath) {
                "/set-cookie" -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, listOf("__Secure-3PSIDTS=synthetic-value; Path=/", "PREF=f6=1")))
                "/redirect" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://accounts.google.com/ServiceLogin"))
                else -> respondOk("{}")
            }
        }
    private val client = createWaveHttpClient(AuthGuardEngine(mock) { host, names -> setCookieNames += host to names })

    @Test
    fun blocksAuthHeadersOutsideAllowedRoutes() =
        runBlocking {
            val blocked =
                listOf(
                    "https://www.youtube.com/youtubei/v1/browse",
                    "https://s.youtube.com/api/stats/playback",
                    "https://accounts.google.com/",
                    "https://rr1---sn-synthetic.googlevideo.com/videoplayback",
                    "https://music.youtube.com/playlist_image_upload/x",
                    "http://music.youtube.com/youtubei/v1/browse",
                    "https://music.youtube.com.evil.example/youtubei/v1/browse",
                )
            for (url in blocked) {
                for (header in listOf(HttpHeaders.Cookie, HttpHeaders.Authorization, "X-Goog-AuthUser")) {
                    assertFailsWith<AuthRouteViolation>("$header → $url") {
                        client.get(url) { header(header, "synthetic") }
                    }
                }
            }
            assertEquals(emptyList(), sent.toList(), "ブロックしたリクエストがエンジンに届いている")
        }

    @Test
    fun allowsAuthHeadersToInnerTubeAndAnythingWithoutAuth() =
        runBlocking {
            client.get("https://music.youtube.com/youtubei/v1/browse") { header(HttpHeaders.Cookie, "SAPISID=synthetic") }
            client.get("https://www.youtube.com/s/player/base.js")
            client.get("https://github.com/MetrolistGroup/faraday/releases/download/x/y.json")
            assertEquals(3, sent.size)
        }

    @Test
    fun guardsClientsThatInnerTubeRebuildsFromTheEngine() =
        runBlocking {
            // innertubex の getTextWithoutRedirects は HttpClient(engine) を作り直す。その経路もガードを通ることを確かめる
            val innerTube = InnerTube(httpClient = client, retryDelay = {})
            innerTube.replaceSession(cookie = "SID=s; SAPISID=synthetic", visitorData = "v", dataSyncId = "d", authUser = "0", useLoginForBrowse = true)

            runCatching { YtConfigParserImpl(client, innerTube).fetchConfig("dQw4w9WgXcQ", useLoginCookies = true) }

            assertTrue(sent.none { it.headers[HttpHeaders.Cookie] != null }, "cookie 付きのリクエストがエンジンに届いている")
            innerTube.close()
        }

    @Test
    fun reportsOnlySetCookieNames() =
        runBlocking {
            client.get("https://music.youtube.com/set-cookie")
            assertEquals(listOf("music.youtube.com" to listOf("__Secure-3PSIDTS", "PREF")), setCookieNames.toList())
            assertFalse(setCookieNames.toString().contains("synthetic-value"))
        }

    @Test
    fun doesNotFollowRedirects() =
        runBlocking {
            val response = client.get("https://music.youtube.com/redirect")
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(listOf("/redirect"), sent.map { it.url.encodedPath })
        }

    @Test
    fun routeRuleMatchesHostExactlyAndPathByPrefix() {
        assertTrue(isAuthRouteAllowed(Url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")))
        assertFalse(isAuthRouteAllowed(Url("https://music.youtube.com/youtubei/v2/player")))
        assertFalse(isAuthRouteAllowed(Url("https://sub.music.youtube.com/youtubei/v1/player")))
    }

    @Test
    fun mediaUrlsMustBeHttpsGooglevideo() {
        assertTrue(isAllowedMediaUrl("https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=141"))
        assertFalse(isAllowedMediaUrl("http://rr1---sn-synthetic.googlevideo.com/videoplayback"))
        assertFalse(isAllowedMediaUrl("https://googlevideo.com.evil.example/videoplayback"))
        assertFalse(isAllowedMediaUrl("https://evil.example/rr1.googlevideo.com/videoplayback"))
        assertFalse(isAllowedMediaUrl("https://user:pass@rr1.googlevideo.com/videoplayback"))
        assertFalse(isAllowedMediaUrl("https://googlevideo.com/videoplayback"))
        assertFalse(isAllowedMediaUrl("not a url"))
    }
}
