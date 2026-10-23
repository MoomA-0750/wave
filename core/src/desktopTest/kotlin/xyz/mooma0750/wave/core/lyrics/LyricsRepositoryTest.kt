package xyz.mooma0750.wave.core.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.core.Fixtures
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.jsonResponse
import xyz.mooma0750.wave.core.music.ArtistRef
import xyz.mooma0750.wave.core.music.MusicParsers
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.FailureKind
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsRepositoryTest {
    @Test
    fun parsersFindTheLyricsTabAndText() {
        assertEquals("MPLYt_LfJd6hctedK-1", MusicParsers.lyricsBrowseId(Fixtures.json("next_radio")))
        val plain = assertNotNull(MusicParsers.plainLyrics(Fixtures.json("browse_lyrics")))
        assertEquals("一行目の歌詞\n二行目の歌詞\n\n三行目の歌詞", plain.text)
        assertEquals("Source: Musixmatch", plain.source)
        // 歌詞の無い曲はタブに行き先が無い
        val noLyrics = Fixtures.text("next_radio").replace("MUSIC_PAGE_TYPE_TRACK_LYRICS", "MUSIC_PAGE_TYPE_UNKNOWN")
        assertNull(MusicParsers.lyricsBrowseId(kotlinx.serialization.json.Json.parseToJsonElement(noLyrics) as kotlinx.serialization.json.JsonObject))
        assertNull(MusicParsers.plainLyrics(Fixtures.json("browse_album")))
    }

    @Test
    fun choosePrefersTimedAndNeverCallsAFailureAbsence() {
        val lines = listOf(LyricLine(0, "x"))
        val plain = Lyrics.Plain("p", "Source: s")
        val found = LrclibClient.Result.Found(lines)
        assertEquals(LyricsRepository.Result.Found(Lyrics.Synced(lines, "LRCLIB")), LyricsRepository.choose(found, CallResult.Failed(FailureKind.Network)))
        assertEquals(LyricsRepository.Result.Found(plain), LyricsRepository.choose(LrclibClient.Result.NotFound, CallResult.Ok(plain)))
        assertEquals(LyricsRepository.Result.Found(plain), LyricsRepository.choose(LrclibClient.Result.Failed, CallResult.Ok(plain)))
        assertEquals(LyricsRepository.Result.None, LyricsRepository.choose(LrclibClient.Result.NotFound, CallResult.Ok(null)))
        assertEquals(LyricsRepository.Result.Failed, LyricsRepository.choose(LrclibClient.Result.Failed, CallResult.Ok(null)))
        assertEquals(LyricsRepository.Result.Failed, LyricsRepository.choose(LrclibClient.Result.NotFound, CallResult.Failed(FailureKind.Network)))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val youtube = CopyOnWriteArrayList<Pair<String, String>>()
    private var lrclibRequests = 0
    private val youtubeClient =
        createWaveHttpClient(
            AuthGuardEngine(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    youtube += path to request.body.toByteArray().decodeToString()
                    when {
                        path.endsWith("/next") -> jsonResponse(Fixtures.text("next_radio"))
                        path.endsWith("/browse") -> jsonResponse(Fixtures.text("browse_lyrics"))
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            ),
        )
    private val lrclibHttp =
        HttpClient(
            MockEngine { request ->
                lrclibRequests++
                // LRCLIB は、1件を引いて無ければ 404、検索で無ければ空の一覧を返す
                if (request.url.encodedPath.endsWith("/search")) jsonResponse("[]") else respond("", HttpStatusCode.NotFound)
            },
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        youtubeClient.close()
        lrclibHttp.close()
    }

    @Test
    fun fallsBackToYouTubeLyricsAndRemembersTheAnswer(): Unit =
        runBlocking {
            val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync", 1)))
            val session = WaveSession.create(httpClient = youtubeClient, credentials = credentials, scope = scope)
            withTimeout(5_000) { session.state.first { it == SessionState.Active } }
            val repository = LyricsRepository(MusicRepository(session), LrclibClient(lrclibHttp, "wave-test/1"))
            val song = Song("vid", "t", listOf(ArtistRef("a", null)), null, 262, null)

            val result = assertIs<LyricsRepository.Result.Found>(repository.lyrics(song))
            assertEquals("Source: Musixmatch", assertIs<Lyrics.Plain>(result.lyrics).source)
            val browse = youtube.single { it.first.endsWith("/browse") }
            assertTrue("MPLYt_LfJd6hctedK-1" in browse.second)
            val calls = youtube.size to lrclibRequests

            assertEquals(result, repository.lyrics(song))
            assertEquals(calls, youtube.size to lrclibRequests, "覚えた結果を使わずに読み直した")
        }

    @Test
    fun timedYouTubeLyricsAreReadAndUntimedLinesAreNot() {
        val synced = assertNotNull(YouTubeTimedLyricsClient.parse(Fixtures.json("browse_lyrics_timed")))
        assertEquals(listOf(LyricLine(1530, "一行目"), LyricLine(9040, "二行目"), LyricLine(17000, "")), synced.lines)
        assertEquals("提供元: Musixmatch", synced.source)
        // MV の歌詞（時刻の無い行だけ）と、古い形（時刻なしの棚）は時刻付きにしない
        val untimed = Fixtures.text("browse_lyrics_timed").replace("\"cueRange\"", "\"noCueRange\"")
        assertNull(YouTubeTimedLyricsClient.parse(kotlinx.serialization.json.Json.parseToJsonElement(untimed) as kotlinx.serialization.json.JsonObject))
        assertNull(YouTubeTimedLyricsClient.parse(Fixtures.json("browse_lyrics")))
    }

    private fun timedClient(
        status: HttpStatusCode,
        requests: MutableList<String>,
    ) = YouTubeTimedLyricsClient(
        HttpClient(
            MockEngine { request ->
                requests += request.body.toByteArray().decodeToString()
                if (status == HttpStatusCode.OK) jsonResponse(Fixtures.text("browse_lyrics_timed")) else respond("", status)
            },
        ),
        hl = "ja",
        gl = "JP",
    )

    private suspend fun activeSession(): WaveSession {
        val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync", 1)))
        val session = WaveSession.create(httpClient = youtubeClient, credentials = credentials, scope = scope)
        withTimeout(5_000) { session.state.first { it == SessionState.Active } }
        return session
    }

    @Test
    fun timedYouTubeLyricsComeFirstWithoutAskingLrclib(): Unit =
        runBlocking {
            val requests = mutableListOf<String>()
            val repository = LyricsRepository(MusicRepository(activeSession()), LrclibClient(lrclibHttp, "wave-test/1"), timedClient(HttpStatusCode.OK, requests))
            val song = Song("vid", "t", listOf(ArtistRef("a", null)), null, 262, null)

            val result = assertIs<LyricsRepository.Result.Found>(repository.lyrics(song))
            assertEquals("提供元: Musixmatch", assertIs<Lyrics.Synced>(result.lyrics).source)
            // 歌詞のタブの ID だけを ANDROID_MUSIC として送る。LRCLIB には聞かない
            assertTrue(requests.single().contains("MPLYt_LfJd6hctedK-1") && requests.single().contains("ANDROID_MUSIC"))
            assertEquals(0, lrclibRequests)
        }

    @Test
    fun plainLyricsShownAfterAFailureAreNotRemembered(): Unit =
        runBlocking {
            val requests = mutableListOf<String>()
            val repository = LyricsRepository(MusicRepository(activeSession()), LrclibClient(lrclibHttp, "wave-test/1"), timedClient(HttpStatusCode.ServiceUnavailable, requests))
            val song = Song("vid", "t", listOf(ArtistRef("a", null)), null, 262, null)

            assertIs<Lyrics.Plain>(assertIs<LyricsRepository.Result.Found>(repository.lyrics(song)).lyrics)
            repository.lyrics(song)
            assertEquals(2, requests.size, "時刻付きの失敗のあとの時刻なしを覚えてしまった")
        }
}
