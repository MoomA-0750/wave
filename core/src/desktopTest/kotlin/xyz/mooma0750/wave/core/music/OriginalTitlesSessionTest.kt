package xyz.mooma0750.wave.core.music

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
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.SessionState
import xyz.mooma0750.wave.core.session.WaveSession
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 設定の「曲名を元の表記で」: 入れたときだけ、検索を日本語と英語の2つで送って突き合わせる。 */
class OriginalTitlesSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val languages = Collections.synchronizedList(mutableListOf<String>())
    private var englishFails = false

    private val engine =
        MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val hl = Regex(""""hl"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
            languages += hl
            when {
                !request.url.encodedPath.endsWith("/search") -> respond("", HttpStatusCode.NotFound)
                hl == "en" && englishFails -> respond("", HttpStatusCode.InternalServerError)
                hl == "en" -> jsonResponse(Fixtures.text("bilingual_search_songs_en"))
                else -> jsonResponse(Fixtures.text("bilingual_search_songs_ja"))
            }
        }
    private val client = createWaveHttpClient(AuthGuardEngine(engine))

    @AfterTest
    fun tearDown() {
        scope.cancel()
        client.close()
    }

    private val credentials = MutableStateFlow<Credentials?>(assertNotNull(Credentials.fromLogin("SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync", 1)))

    private suspend fun repository(originalTitles: Boolean): MusicRepository {
        val session = WaveSession.create(httpClient = client, credentials = credentials, scope = scope, hl = "ja")
        withTimeout(5_000) { session.state.first { it == SessionState.Active } }
        session.originalTitles = originalTitles
        return MusicRepository(session)
    }

    private fun artists(result: CallResult<SongPage>) = assertIs<CallResult.Ok<SongPage>>(result).value.songs.flatMap { s -> s.artists.map { it.name } }

    @Test
    fun offSendsOneRequestInTheScreenLanguage(): Unit =
        runBlocking {
            val names = artists(repository(originalTitles = false).searchSongs("Taylor Swift"))
            assertEquals(listOf("ja"), languages)
            assertTrue("テイラー・スウィフト" in names)
        }

    @Test
    fun onAsksInBothLanguagesAndKeepsTheOriginalNames(): Unit =
        runBlocking {
            val names = artists(repository(originalTitles = true).searchSongs("Taylor Swift"))
            assertEquals(setOf("ja", "en"), languages.toSet())
            assertEquals(2, languages.size)
            assertTrue(names.all { it == "Taylor Swift" }, names.toString())
        }

    @Test
    fun aFailedSecondLanguageFallsBackToTheScreenLanguage(): Unit =
        runBlocking {
            englishFails = true
            val names = artists(repository(originalTitles = true).searchSongs("Taylor Swift"))
            assertTrue("テイラー・スウィフト" in names)
        }
}
