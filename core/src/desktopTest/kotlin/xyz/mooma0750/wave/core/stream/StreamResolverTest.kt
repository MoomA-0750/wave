package xyz.mooma0750.wave.core.stream

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 合成値だけを使う。 */
class StreamResolverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = CopyOnWriteArrayList<HttpRequestData>()
    private var playerJson = player("OK", listOf(format(251, cipher = true), format(141, cipher = true), format(774, cipher = true)))
    private val engine =
        MockEngine { request ->
            sent += request
            respond(playerJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    private val client = createWaveHttpClient(AuthGuardEngine(engine))
    private val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic", "v", "d", 1)))
    private val now = AtomicLong(0)
    private val configFetches = AtomicInteger(0)
    private var sts: Int? = 20702
    private var decipher: (List<PlayerResponse.StreamingData.Format>) -> List<PlayerResponse.StreamingData.Format> = { formats ->
        formats.map { it.copy(url = "https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=${it.itag}&pot=synthetic-pot&sig=abc%3D&ump=1&n=solved", signatureCipher = null) }
    }
    private val rejections = AtomicInteger(0)

    private val session = WaveSession.create(client, credentials, scope)
    private val resolver =
        StreamResolver(
            session = session,
            configParser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        assertFalse(useLoginCookies)
                        configFetches.incrementAndGet()
                        return PlayerConfig("https://www.youtube.com/s/player/abc123/player_ias.vflset/ja_JP/base.js", sts, null, null)
                    }
                },
            decipherer =
                object : FormatDecipherer {
                    override suspend fun decipher(
                        playerUrl: String,
                        formats: List<PlayerResponse.StreamingData.Format>,
                    ): List<PlayerResponse.StreamingData.Format> {
                        assertEquals("https://www.youtube.com/s/player/abc123/player_ias.vflset/en_GB/base.js", playerUrl)
                        return decipher(formats)
                    }

                    override suspend fun onStreamRejected() {
                        rejections.incrementAndGet()
                    }
                },
            nowMs = { now.get() },
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        client.close()
    }

    private suspend fun ready() {
        withTimeout(5_000) { session.state.first { it == SessionState.Active } }
    }

    @Test
    fun picksPreferredItagAndStripsPoTokenAndUmp(): Unit =
        runBlocking {
            ready()
            val stream = assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id")).stream
            assertEquals(774, stream.itag)
            assertEquals("https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=774&sig=abc%3D&n=solved", stream.revealUrl())
            assertEquals(21540_000L - StreamResolver.EXPIRY_SAFETY_MS, stream.expiresAtMs)
            assertFalse(stream.toString().contains("googlevideo"))
            assertEquals("music.youtube.com", sent.single().url.host)
        }

    @Test
    fun playbackStatsUrlIsKeptWithTheGenerationThatFetchedIt(): Unit =
        runBlocking {
            ready()
            playerJson = player("OK", listOf(format(251, cipher = true)), statsUrl = "https://s.youtube.com/api/stats/playback?ns=yt&docid=synthetic")
            val stream = assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id")).stream
            assertTrue(stream.hasPlaybackStats)
            assertEquals(session.currentGenerationId(), stream.sessionGeneration)
            assertFalse(stream.toString().contains("stats"))
        }

    @Test
    fun playbackStatsUrlIsDroppedWhenTheLoginChangesAfterThePlayerResponse(): Unit =
        runBlocking {
            ready()
            playerJson = player("OK", listOf(format(251, cipher = true)), statsUrl = "https://s.youtube.com/api/stats/playback?ns=yt")
            val before = session.currentGenerationId()
            val original = decipher
            // /player の応答の後、解読の最中に別のアカウントへ切り替わる
            decipher = { formats ->
                credentials.value = assertNotNull(Credentials.fromLogin("SAPISID=other-synthetic", "v", "d", 2))
                runBlocking { withTimeout(5_000) { while (session.currentGenerationId() == before) delay(5) } }
                original(formats)
            }
            val stream = assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id")).stream
            assertFalse(stream.hasPlaybackStats, "切り替えの前に取った統計 URL を持っている")
            assertEquals(before, stream.sessionGeneration)
        }

    @Test
    fun requiredItagDoesNotFallBackToAnotherFormat(): Unit =
        runBlocking {
            ready()
            assertEquals(141, assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id", requiredItag = 141)).stream.itag)
            playerJson = player("OK", listOf(format(251, cipher = true)))
            assertEquals(ResolveResult.Failed(StreamFailure.ItagUnavailable), resolver.resolve("synthetic-id", requiredItag = 141))
        }

    @Test
    fun fallsBackWhenPreferredFormatCannotBeDeciphered(): Unit =
        runBlocking {
            ready()
            decipher = { formats -> formats.map { if (it.itag == 774) it.copy(url = null) else it.copy(url = "https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=${it.itag}", signatureCipher = null) } }
            assertEquals(141, assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id")).stream.itag)
        }

    @Test
    fun skipsDrcAndVideoFormats(): Unit =
        runBlocking {
            ready()
            playerJson = player("OK", listOf(format(774, cipher = true, drc = true), format(137, cipher = true, width = 1920), format(140, cipher = true)))
            assertEquals(140, assertIs<ResolveResult.Ok>(resolver.resolve("synthetic-id")).stream.itag)
        }

    @Test
    fun rejectsUrlsOutsideGooglevideo(): Unit =
        runBlocking {
            ready()
            decipher = { formats -> formats.map { it.copy(url = "https://evil.example/videoplayback?itag=${it.itag}", signatureCipher = null) } }
            assertEquals(ResolveResult.Failed(StreamFailure.RejectedUrl), resolver.resolve("synthetic-id"))
        }

    @Test
    fun decipherFailureDropsCachedPlayerConfig(): Unit =
        runBlocking {
            ready()
            resolver.resolve("synthetic-id")
            assertEquals(1, configFetches.get())
            decipher = { formats -> formats }
            assertEquals(ResolveResult.Failed(StreamFailure.DecipherFailed), resolver.resolve("synthetic-id"))
            resolver.resolve("synthetic-id")
            assertEquals(2, configFetches.get(), "解読に失敗したのにプレイヤーの設定を使い回している")
        }

    @Test
    fun playerConfigIsCachedForAnHourAcrossVideos(): Unit =
        runBlocking {
            ready()
            resolver.resolve("a")
            resolver.resolve("b")
            assertEquals(1, configFetches.get())
            now.set(StreamResolver.PLAYER_CONFIG_TTL_MS)
            resolver.resolve("c")
            assertEquals(2, configFetches.get())
        }

    @Test
    fun rejectionDropsConfigAndNotifiesCipher(): Unit =
        runBlocking {
            ready()
            resolver.resolve("a")
            resolver.onStreamRejected()
            resolver.resolve("a")
            assertEquals(2, configFetches.get())
            assertEquals(1, rejections.get())
        }

    @Test
    fun classifiesUnplayableSabrOnlyAndMissingSts(): Unit =
        runBlocking {
            ready()
            playerJson = player("UNPLAYABLE", emptyList())
            assertEquals(ResolveResult.Failed(StreamFailure.Unplayable), resolver.resolve("a"))
            playerJson = player("OK", listOf(format(251, cipher = false, url = null)))
            assertEquals(ResolveResult.Failed(StreamFailure.SabrOnly), resolver.resolve("a"))

            sts = null
            now.set(StreamResolver.PLAYER_CONFIG_TTL_MS * 2)
            assertEquals(ResolveResult.Failed(StreamFailure.PlayerConfigUnavailable), resolver.resolve("a"))
        }

    @Test
    fun loginRequiredIsReportedAsSuspect(): Unit =
        runBlocking {
            ready()
            playerJson = player("LOGIN_REQUIRED", emptyList())
            assertEquals(ResolveResult.Failed(StreamFailure.Suspect), resolver.resolve("a"))
        }

    @Test
    fun reResolveRequiresTheSameFile(): Unit =
        runBlocking {
            ready()
            val first = assertIs<ResolveResult.Ok>(resolver.resolve("a")).stream
            assertEquals(774, assertIs<ResolveResult.Ok>(resolver.resolve("a", sameAs = first)).stream.itag)
            playerJson = player("OK", listOf(format(774, cipher = true, contentLength = 99999)))
            assertEquals(ResolveResult.Failed(StreamFailure.StreamChanged), resolver.resolve("a", sameAs = first))
        }

    @Test
    fun usesOnlyTheDefaultAudioTrack(): Unit =
        runBlocking {
            ready()
            playerJson =
                player(
                    "OK",
                    listOf(
                        format(774, cipher = true, track = """{"id":"en.1","audioIsDefault":false}"""),
                        format(141, cipher = true, track = """{"id":"ja.0","audioIsDefault":true}"""),
                    ),
                )
            assertEquals(141, assertIs<ResolveResult.Ok>(resolver.resolve("a")).stream.itag)
        }

    @Test
    fun decipherExceptionIsDecipherFailed(): Unit =
        runBlocking {
            ready()
            decipher = { error("synthetic failure") }
            assertEquals(ResolveResult.Failed(StreamFailure.DecipherFailed), resolver.resolve("a"))
        }

    @Test
    fun missingLifetimeFallsBackToAnHour(): Unit =
        runBlocking {
            ready()
            playerJson = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[${format(141, cipher = true)}]}}"""
            val stream = assertIs<ResolveResult.Ok>(resolver.resolve("a")).stream
            assertEquals(StreamResolver.DEFAULT_LIFETIME_SECONDS * 1000 - StreamResolver.EXPIRY_SAFETY_MS, stream.expiresAtMs)
            assertTrue(resolver.isFresh(stream, marginMs = 0))
            now.set(stream.expiresAtMs)
            assertFalse(resolver.isFresh(stream, marginMs = 0))
        }

    @Test
    fun staleConfigFailureDoesNotDropFreshConfig(): Unit =
        runBlocking {
            ready()
            resolver.resolve("a")
            assertEquals(1, configFetches.get())
            now.set(StreamResolver.PLAYER_CONFIG_TTL_MS)
            decipher = { formats -> formats }
            // 取り直し（2回目）→ 解読失敗でその設定を捨てる
            resolver.resolve("a")
            decipher = { formats -> formats.map { it.copy(url = "https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=${it.itag}", signatureCipher = null) } }
            resolver.resolve("a")
            assertEquals(3, configFetches.get())
            resolver.resolve("a")
            assertEquals(3, configFetches.get(), "取り直した設定を使い回していない")
        }

    @Test
    fun removesOnlyNamedParametersWithoutReencoding() {
        assertEquals(
            "https://h/videoplayback?a=1&sig=x%3D%3D&b=2#f",
            StreamResolver.removeQueryParameters("https://h/videoplayback?pot=p&a=1&ump=1&sig=x%3D%3D&b=2#f", setOf("pot", "ump")),
        )
        assertEquals("https://h/videoplayback", StreamResolver.removeQueryParameters("https://h/videoplayback?pot=p", setOf("pot")))
        assertEquals("https://h/videoplayback?potato=1", StreamResolver.removeQueryParameters("https://h/videoplayback?potato=1", setOf("pot")))
        assertTrue(StreamResolver.normalizePlayerUrl("https://www.youtube.com/s/player/zz9/player_es6.vflset/ja_JP/base.js").endsWith("/zz9/player_ias.vflset/en_GB/base.js"))
    }

    private fun format(
        itag: Int,
        cipher: Boolean,
        drc: Boolean = false,
        width: Int? = null,
        url: String? = "https://rr1---sn-synthetic.googlevideo.com/videoplayback?itag=$itag",
        contentLength: Long = 12345,
        track: String? = null,
    ): String {
        val source = if (cipher) """"signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Fsynthetic"""" else if (url != null) """"url":"$url"""" else null
        return listOfNotNull(
            """"itag":$itag""",
            """"mimeType":"audio/webm; codecs=\"opus\""""",
            """"contentLength":"$contentLength"""",
            track?.let { """"audioTrack":$it""" },
            """"isDrc":$drc""",
            width?.let { """"width":$it""" },
            source,
        ).joinToString(",", "{", "}")
    }

    private fun player(
        status: String,
        formats: List<String>,
        statsUrl: String? = null,
    ): String =
        """{"playabilityStatus":{"status":"$status"},"streamingData":{"expiresInSeconds":21540,"adaptiveFormats":[${formats.joinToString(",")}]}""" +
            (statsUrl?.let { ""","playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"$it"}}""" } ?: "") + "}"
}
