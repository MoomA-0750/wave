package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.net.AuthGuardEngine
import xyz.mooma0750.wave.core.net.AuthRouteViolation
import xyz.mooma0750.wave.core.net.createWaveHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 合成値だけを使う。MockEngine の応答はパスで振り分ける。 */
class WaveSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = CopyOnWriteArrayList<HttpRequestData>()
    private val now = AtomicLong(0)
    private var sts: Int? = 20702
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(accountMenu("Synthetic User")) }

    private val engine =
        MockEngine { request ->
            sent += request
            handler(request)
        }
    private val client = createWaveHttpClient(AuthGuardEngine(engine))
    private val credentials = MutableStateFlow<Credentials?>(null)
    private val loggedIn = assertNotNull(Credentials.fromLogin("SID=s; SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync||extra", 1))

    private fun session(rethrowViolations: Boolean = false) =
        WaveSession.create(
            httpClient = client,
            credentials = credentials,
            scope = scope,
            rethrowViolations = rethrowViolations,
            nowMs = { now.get() },
            configParser = { _, _ ->
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        assertFalse(useLoginCookies, "STS の取得に cookie を送っている")
                        return PlayerConfig(playerUrl = "https://www.youtube.com/s/player/x/base.js", signatureTimestamp = sts, visitorData = null, clientVersion = null)
                    }
                }
            },
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        client.close()
    }

    @Test
    fun loggedOutSessionSendsNothing(): Unit =
        runBlocking {
            val session = session()
            assertEquals(SessionState.LoggedOut, session.state.value)
            assertEquals(AccountResult.Failed(FailureKind.NotLoggedIn), session.accountInfo())
            assertEquals(PremiumStatus.Unknown, session.premiumStatus())
            assertTrue(sent.isEmpty())
        }

    @Test
    fun accountInfoSendsAuthOnlyToInnerTubeWithTrimmedDataSyncId(): Unit =
        runBlocking {
            val session = session()
            credentials.value = loggedIn
            awaitState(session) { it == SessionState.Active }

            val account = assertIs<AccountResult.Ok>(session.accountInfo())
            assertEquals("Synthetic User", account.name)
            assertEquals("@synthetic", account.handle)

            val request = sent.single()
            assertEquals("music.youtube.com", request.url.host)
            assertEquals("/youtubei/v1/account/account_menu", request.url.encodedPath)
            assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("SAPISID=synthetic-sapisid"))
            assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("SAPISIDHASH "))
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"onBehalfOfUser\":\"synthetic-sync\""), "onBehalfOfUser が || で切られていない")
            assertFalse(body.contains("||"))
        }

    @Test
    fun missingNameBecomesNull(): Unit =
        runBlocking {
            handler = { json("""{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{}}}}}}]}""") }
            val session = loggedInSession()
            assertNull(assertIs<AccountResult.Ok>(session.accountInfo()).name)
        }

    @Test
    fun unauthorizedResponseExpiresImmediately(): Unit =
        runBlocking {
            handler = { respond("", HttpStatusCode.Unauthorized) }
            val session = loggedInSession()
            // accountMenu は requireSuccess = false なので、401 は応答として返る
            assertEquals(AccountResult.Failed(FailureKind.Expired), session.accountInfo())
            assertEquals(SessionState.Expired(ExpiredReason.Unauthenticated), session.state.value)
        }

    @Test
    fun unauthorizedExceptionExpiresImmediately(): Unit =
        runBlocking {
            handler = { respond("", HttpStatusCode.Unauthorized) }
            val session = loggedInSession()
            // player は requireSuccess = true なので、401 は InnerTubeHttpException で届く
            assertEquals(CallResult.Failed(FailureKind.Expired, 401), callPlayer(session))
            assertEquals(SessionState.Expired(ExpiredReason.Unauthenticated), session.state.value)
        }

    @Test
    fun redirectIsConfirmedBeforeExpiring(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) {
                    respond("", HttpStatusCode.Found)
                } else {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://accounts.google.com/ServiceLogin?continue=x"))
                }
            }
            val session = loggedInSession()
            assertEquals(CallResult.Failed(FailureKind.Suspect, 302), callPlayer(session))
            awaitState(session) { it == SessionState.Expired(ExpiredReason.RedirectedToSignIn) }
        }

    @Test
    fun loginRequiredIsConfirmedAndClearedWhenStillLoggedIn(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) json(player("LOGIN_REQUIRED", emptyList())) else json(accountMenu("Synthetic User"))
            }
            val session = loggedInSession()
            assertEquals(CallResult.Failed(FailureKind.Suspect), callPlayer(session, parseLoginRequired = true))
            awaitState(session) { it == SessionState.Active }
            assertEquals(1, sent.count { it.url.encodedPath.endsWith("/account_menu") }, "確認の accountMenu が走っていない")
        }

    @Test
    fun loginRequiredExpiresWhenConfirmationShowsLoggedOut(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) json(player("LOGIN_REQUIRED", emptyList())) else json(accountMenu(null))
            }
            val session = loggedInSession()
            callPlayer(session, parseLoginRequired = true)
            awaitState(session) { it == SessionState.Expired(ExpiredReason.ConfirmedLoggedOut) }
        }

    @Test
    fun forbiddenIsOnlySuspectedWhileAccountIsStillThere(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) respond("", HttpStatusCode.Forbidden) else json(accountMenu("Synthetic User"))
            }
            val session = loggedInSession()
            assertEquals(CallResult.Failed(FailureKind.Suspect, 403), callPlayer(session))
            awaitState(session) { it == SessionState.Active }
        }

    @Test
    fun serverErrorsAreRetriedAndDoNotChangeState(): Unit =
        runBlocking {
            handler = { respond("", HttpStatusCode.ServiceUnavailable) }
            val session = loggedInSession()
            assertEquals(CallResult.Failed(FailureKind.Http, 503), callPlayer(session))
            assertEquals(3, sent.size)
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun onlyAuthenticationProvingSuccessClearsSuspicion(): Unit =
        runBlocking {
            var fail = true
            var accountAvailable = false
            handler = { request ->
                when {
                    request.url.encodedPath.endsWith("/player") && fail -> respond("", HttpStatusCode.Forbidden)
                    request.url.encodedPath.endsWith("/player") -> json(player("OK", emptyList()))
                    accountAvailable -> json(accountMenu("Synthetic User"))
                    else -> respond("", HttpStatusCode.InternalServerError) // 確認は失敗させて Suspect のままにする
                }
            }
            val session = loggedInSession()
            callPlayer(session)
            session.awaitConfirmIdle()
            assertIs<SessionState.Suspect>(session.state.value)

            // 匿名でも成功しうる応答では解除しない
            fail = false
            assertIs<CallResult.Ok<Unit>>(callPlayer(session))
            assertIs<SessionState.Suspect>(session.state.value)

            // アカウントのヘッダが返った（ログインが有効と分かった）ときだけ解除する
            accountAvailable = true
            assertIs<AccountResult.Ok>(session.accountInfo())
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun logoutClearsCookieEvenAfterGenerationMoved(): Unit =
        runBlocking {
            val session = loggedInSession()
            // locale の変更でも innertubex の世代は進む（clearSessionIfMatches だとここで取りこぼす）
            session.innerTube.locale = session.innerTube.locale.copy(hl = "en")
            credentials.value = null
            awaitState(session) { it == SessionState.LoggedOut }
            assertNull(session.innerTube.sessionSnapshot().cookie)
            assertNull(session.innerTube.sessionSnapshot().sapisid)
            assertNull(session.innerTube.sessionSnapshot().dataSyncId)
        }

    @Test
    fun logoutCancelsInFlightCallsAndTheirRetries(): Unit =
        runBlocking {
            val received = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            handler = {
                received.complete(Unit)
                release.await()
                respond("", HttpStatusCode.ServiceUnavailable)
            }
            val session = loggedInSession()
            val inFlight = async { callPlayer(session) }
            withTimeout(5_000) { received.await() }

            credentials.value = null

            assertEquals(CallResult.Failed(FailureKind.NotLoggedIn), withTimeout(5_000) { inFlight.await() })
            release.complete(Unit)
            delay(200)
            assertEquals(1, sent.size, "ログアウト後に古い cookie で再試行している")
        }

    @Test
    fun violationsAreRethrownOnlyWhenStrict(): Unit =
        runBlocking {
            val strict = loggedInSession(rethrowViolations = true)
            assertFailsWith<AuthRouteViolation> {
                strict.call("stray", block = { it.httpClient.get("https://www.youtube.com/") { header(HttpHeaders.Cookie, "SAPISID=synthetic") } }) { ParseOutcome.Parsed(Unit) }
            }
            val lenient = loggedInSession(rethrowViolations = false)
            assertEquals(
                CallResult.Failed(FailureKind.Http),
                lenient.call("stray", block = { it.httpClient.get("https://www.youtube.com/") { header(HttpHeaders.Cookie, "SAPISID=synthetic") } }) { ParseOutcome.Parsed(Unit) },
            )
            assertTrue(sent.none { it.url.host == "www.youtube.com" })
        }

    @Test
    fun premiumIsAvailableWithPremiumItags(): Unit =
        runBlocking {
            handler = premiumHandler(listOf(140, 141, 251))
            assertEquals(PremiumStatus.Available, loggedInSession().premiumStatus())
        }

    @Test
    fun premiumIsUnavailableWithoutPremiumItags(): Unit =
        runBlocking {
            handler = premiumHandler(listOf(140, 251))
            assertEquals(PremiumStatus.Unavailable, loggedInSession().premiumStatus())
        }

    @Test
    fun premiumIsUnknownWithoutSignatureTimestamp(): Unit =
        runBlocking {
            sts = null
            handler = premiumHandler(listOf(141))
            assertEquals(PremiumStatus.Unknown, loggedInSession().premiumStatus())
            assertTrue(sent.none { it.url.encodedPath.endsWith("/player") })
        }

    @Test
    fun premiumIsProbedOncePerGenerationEvenWhenCalledConcurrently(): Unit =
        runBlocking {
            handler = premiumHandler(listOf(774))
            val session = loggedInSession()
            val results = List(5) { async { session.premiumStatus() } }.awaitAll()
            assertEquals(List(5) { PremiumStatus.Available }, results)
            session.premiumStatus()
            assertEquals(1, sent.count { it.url.encodedPath.endsWith("/player") })
        }

    @Test
    fun unknownPremiumIsRetriedAfterSixtySeconds(): Unit =
        runBlocking {
            sts = null
            handler = premiumHandler(listOf(141))
            val session = loggedInSession()
            assertEquals(PremiumStatus.Unknown, session.premiumStatus())
            now.set(59_000)
            assertEquals(PremiumStatus.Unknown, session.premiumStatus())
            sts = 20702
            now.set(60_000)
            assertEquals(PremiumStatus.Available, session.premiumStatus())
        }

    @Test
    fun emptySuccessIsOnlySuspectedAndUnknownShapesDoNotExpire(): Unit =
        runBlocking {
            handler = { json("{}") }
            val session = loggedInSession()
            assertEquals(AccountResult.Failed(FailureKind.Suspect), session.accountInfo())
            assertEquals(SessionState.Suspect(SuspectReason.EmptyResponse, 1), session.state.value)
            session.awaitConfirmIdle()
            assertEquals(2, sent.size, "確認の accountMenu が走っていない")
            // 確認の応答も {} なので、ログアウトの印とはみなさず Suspect のまま
            assertIs<SessionState.Suspect>(session.state.value)
        }

    @Test
    fun confirmationWith401Expires() =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) respond("", HttpStatusCode.Forbidden) else respond("", HttpStatusCode.Unauthorized)
            }
            val session = loggedInSession()
            callPlayer(session)
            awaitState(session) { it == SessionState.Expired(ExpiredReason.Unauthenticated) }
        }

    @Test
    fun confirmationIsRateLimited(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) respond("", HttpStatusCode.Forbidden) else respond("", HttpStatusCode.InternalServerError)
            }
            val session = loggedInSession()
            repeat(5) { callPlayer(session) }
            session.awaitConfirmIdle()
            val confirms = { sent.count { it.url.encodedPath.endsWith("/account_menu") } }
            // 500 は innertubex が3回まで再試行するので、確認1回 = 3リクエスト
            assertEquals(3, confirms())
            now.set(WaveSession.CONFIRM_INTERVAL_MS)
            callPlayer(session)
            withTimeout(5_000) { while (confirms() < 6) delay(10) }
        }

    @Test
    fun expiredIsNotDowngradedToSuspect(): Unit =
        runBlocking {
            var status = HttpStatusCode.Unauthorized
            handler = { respond("", status) }
            val session = loggedInSession()
            callPlayer(session)
            status = HttpStatusCode.Forbidden
            callPlayer(session)
            assertEquals(SessionState.Expired(ExpiredReason.Unauthenticated), session.state.value)
            // 確認は suspect() の中で同期的に起動されるので、起動されていればここで待てる
            session.awaitConfirmIdle()
            assertEquals(0, sent.count { it.url.encodedPath.endsWith("/account_menu") })
        }

    @Test
    fun parseFailuresAndNetworkErrorsAreClassified(): Unit =
        runBlocking {
            handler = { request ->
                if (request.url.encodedPath.endsWith("/player")) json("not json") else throw java.io.IOException("synthetic network failure")
            }
            val session = loggedInSession()
            assertEquals(
                CallResult.Failed(FailureKind.Parse),
                session.call("player", block = { it.player(YouTubeClient.WEB_REMIX, "synthetic-id", signatureTimestamp = 1) }) { error("unparseable") },
            )
            assertEquals(AccountResult.Failed(FailureKind.Network), session.accountInfo())
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun cancellationFromInnerTubeIsNotReportedAsLoggedOut(): Unit =
        runBlocking {
            val session = loggedInSession()
            assertEquals(
                CallResult.Failed(FailureKind.Network),
                session.call<Unit>("mutation", block = { throw kotlinx.coroutines.CancellationException("InnerTube session changed") }) { ParseOutcome.Parsed(Unit) },
            )
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun writesAreNotRetriedAndUncertainOutcomesAreUnknown(): Unit =
        runBlocking {
            val session = loggedInSession()
            val like: suspend (com.metrolist.innertubex.InnerTube) -> io.ktor.client.statement.HttpResponse = { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }

            handler = { respond("", HttpStatusCode.InternalServerError) }
            assertEquals(WriteResult.Unknown, session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            assertEquals(1, sent.size, "書き込みを送り直した")

            handler = { throw java.io.IOException("synthetic network failure") }
            assertEquals(WriteResult.Unknown, session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            assertEquals(WriteResult.Unknown, session.callWrite<Unit>("like", { throw kotlinx.coroutines.CancellationException("InnerTube session changed") }) { ParseOutcome.Parsed(Unit) })

            handler = { json("{}") }
            assertEquals(WriteResult.Unknown, session.callWrite("like", like) { ParseOutcome.Invalid })
            assertIs<WriteResult.Done<Unit>>(session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })

            handler = { respond("", HttpStatusCode.BadRequest) }
            assertEquals(WriteResult.Failed(FailureKind.Http, 400), session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun writesClassifyAuthFailuresButNeverClearThem(): Unit =
        runBlocking {
            val session = loggedInSession()
            val like: suspend (com.metrolist.innertubex.InnerTube) -> io.ktor.client.statement.HttpResponse = { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }
            handler = { request -> if (request.url.encodedPath.endsWith("/account_menu")) json(accountMenu("Synthetic User")) else respond("", HttpStatusCode.Forbidden) }
            assertEquals(WriteResult.Failed(FailureKind.Suspect, 403), session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })

            handler = { respond("", HttpStatusCode.Unauthorized) }
            assertEquals(WriteResult.Failed(FailureKind.Expired, 401), session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            handler = { json("{}") }
            assertIs<WriteResult.Done<Unit>>(session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            assertEquals(SessionState.Expired(ExpiredReason.Unauthenticated), session.state.value, "書き込みの成功で失効を解除した")
        }

    @Test
    fun writeInterruptedByLogoutIsUnknownNotFailed(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            handler = {
                release.await()
                json("{}")
            }
            val session = loggedInSession()
            val write = async { session.callWrite("like", { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }) { ParseOutcome.Parsed(Unit) } }
            withTimeout(5_000) { while (sent.isEmpty()) delay(10) }
            // 送信後にログアウトした。反映されたかは分からない
            credentials.value = null
            assertEquals(WriteResult.Unknown, withTimeout(5_000) { write.await() })
            release.complete(Unit)
        }

    @Test
    fun writeViolationsAreFailedAndRethrownOnlyWhenStrict(): Unit =
        runBlocking {
            val stray: suspend (com.metrolist.innertubex.InnerTube) -> io.ktor.client.statement.HttpResponse = {
                it.httpClient.get("https://www.youtube.com/") { header(HttpHeaders.Cookie, "SAPISID=synthetic") }
            }
            assertFailsWith<AuthRouteViolation> { loggedInSession(rethrowViolations = true).callWrite("stray", stray) { ParseOutcome.Parsed(Unit) } }
            // ガードは送る前に止めるので、反映されていないと確実
            assertEquals(WriteResult.Failed(FailureKind.Http), loggedInSession(rethrowViolations = false).callWrite("stray", stray) { ParseOutcome.Parsed(Unit) })
            assertTrue(sent.none { it.url.host == "www.youtube.com" })
        }

    @Test
    fun callerCancellationOfAWriteIsPropagated(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            handler = {
                release.await()
                json("{}")
            }
            val session = loggedInSession()
            val job = launch { session.callWrite("like", { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }) { ParseOutcome.Parsed(Unit) } }
            withTimeout(5_000) { while (sent.isEmpty()) delay(10) }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            release.complete(Unit)
        }

    @Test
    fun writeRedirectIsSuspectAndSuccessDoesNotClearIt(): Unit =
        runBlocking {
            val like: suspend (com.metrolist.innertubex.InnerTube) -> io.ktor.client.statement.HttpResponse = { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }
            // 確認（accountMenu）は 403 を返し続けるので、疑いのまま残る
            handler = { request -> if (request.url.encodedPath.endsWith("/account_menu")) respond("", HttpStatusCode.Forbidden) else respond("", HttpStatusCode.Found) }
            val session = loggedInSession()
            assertEquals(WriteResult.Failed(FailureKind.Suspect, 302), session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            session.awaitConfirmIdle()
            assertTrue(sent.any { it.url.encodedPath.endsWith("/account_menu") }, "確認が走っていない")
            handler = { request -> if (request.url.encodedPath.endsWith("/account_menu")) respond("", HttpStatusCode.Forbidden) else json("{}") }
            assertIs<WriteResult.Done<Unit>>(session.callWrite("like", like) { ParseOutcome.Parsed(Unit) })
            assertIs<SessionState.Suspect>(session.state.value, "書き込みの成功で疑いを解除した")
        }

    @Test
    fun writesWhileLoggedOutAreNotSent(): Unit =
        runBlocking {
            val session = session()
            assertEquals(WriteResult.Failed(FailureKind.NotLoggedIn), session.callWrite("like", { it.likeVideo(YouTubeClient.WEB_REMIX, "synthetic-id") }) { ParseOutcome.Parsed(Unit) })
            assertTrue(sent.isEmpty())
        }

    @Test
    fun callerCancellationIsPropagated(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            handler = {
                release.await()
                json(player("OK", emptyList()))
            }
            val session = loggedInSession()
            val job = launch { callPlayer(session) }
            withTimeout(5_000) { while (sent.isEmpty()) delay(10) }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            release.complete(Unit)
        }

    @Test
    fun staleGenerationCannotOverwriteNewState(): Unit =
        runBlocking {
            val session = loggedInSession()
            val staleId = session.currentGenerationId()
            credentials.value = assertNotNull(Credentials.fromLogin("SAPISID=other-synthetic", "v2", "s2", 2))
            awaitState(session) { session.currentGenerationId() != staleId }

            assertFalse(session.updateStateFor(staleId) { SessionState.Expired(ExpiredReason.Unauthenticated) })
            assertEquals(SessionState.Active, session.state.value)
        }

    @Test
    fun appliedLoginIsPublishedForTheUi(): Unit =
        runBlocking {
            val session = session()
            assertNull(session.appliedObtainedAtEpochMs.value)
            credentials.value = loggedIn
            withTimeout(5_000) { session.appliedObtainedAtEpochMs.first { it == loggedIn.obtainedAtEpochMs } }
            credentials.value = null
            withTimeout(5_000) { session.appliedObtainedAtEpochMs.first { it == null } }
        }

    @Test
    fun switchingAccountsResetsStateAndPremiumCache(): Unit =
        runBlocking {
            handler = premiumHandler(listOf(141))
            val session = loggedInSession()
            assertEquals(PremiumStatus.Available, session.premiumStatus())
            handler = { respond("", HttpStatusCode.Unauthorized) }
            callPlayer(session)
            assertIs<SessionState.Expired>(session.state.value)

            handler = premiumHandler(listOf(140))
            credentials.value = assertNotNull(Credentials.fromLogin("SAPISID=other-synthetic", "v2", "s2", 2))
            awaitState(session) { it == SessionState.Active }
            assertEquals(PremiumStatus.Unavailable, session.premiumStatus())
            assertTrue(sent.last().headers[HttpHeaders.Cookie].orEmpty().contains("other-synthetic"))
        }

    @Test
    fun premiumIsUnknownWhenAnyStepFails(): Unit =
        runBlocking {
            handler = { request -> if (request.url.encodedPath.endsWith("/player")) json(player("OK", listOf(141))) else respond("", HttpStatusCode.InternalServerError) }
            assertEquals(PremiumStatus.Unknown, loggedInSession().premiumStatus())

            handler = { request -> if (request.url.encodedPath.endsWith("/player")) json(player("LOGIN_REQUIRED", listOf(141))) else json(accountMenu("Synthetic User")) }
            assertEquals(PremiumStatus.Unknown, loggedInSession().premiumStatus())

            handler = { request -> if (request.url.encodedPath.endsWith("/player")) json(player("UNPLAYABLE", listOf(141))) else json(accountMenu("Synthetic User")) }
            assertEquals(PremiumStatus.Unknown, loggedInSession().premiumStatus())
        }

    @Test
    fun closeClearsTheInnerTubeSession(): Unit =
        runBlocking {
            val session = loggedInSession()
            session.close()
            assertNull(session.innerTube.sessionSnapshot().cookie)
            credentials.value = assertNotNull(Credentials.fromLogin("SAPISID=other-synthetic", null, null, 2))
            delay(200)
            assertNull(session.innerTube.sessionSnapshot().cookie)
            assertEquals(AccountResult.Failed(FailureKind.NotLoggedIn), session.accountInfo())
        }

    private suspend fun loggedInSession(rethrowViolations: Boolean = false): WaveSession {
        val session = session(rethrowViolations)
        credentials.value = loggedIn
        awaitState(session) { it == SessionState.Active }
        return session
    }

    private suspend fun callPlayer(
        session: WaveSession,
        parseLoginRequired: Boolean = false,
    ): CallResult<Unit> =
        session.call("player", block = { it.player(YouTubeClient.WEB_REMIX, "synthetic-id", signatureTimestamp = 1) }) {
            if (parseLoginRequired) ParseOutcome.LoginRequired else ParseOutcome.Parsed(Unit)
        }

    private suspend fun awaitState(
        session: WaveSession,
        predicate: (SessionState) -> Boolean,
    ) {
        withTimeout(5_000) { session.state.first(predicate) }
    }

    private fun premiumHandler(itags: List<Int>): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { request ->
            if (request.url.encodedPath.endsWith("/player")) json(player("OK", itags)) else json(accountMenu("Synthetic User"))
        }

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun accountMenu(name: String?): String =
        if (name == null) {
            // 合成のログアウト相当: actions はあるが、アカウントのヘッダが無い（実際の形は未検証）
            """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{}}}}]}"""
        } else {
            """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":""" +
                """{"accountName":{"runs":[{"text":"$name"}]},"channelHandle":{"runs":[{"text":"@synthetic"}]}}}}}}}]}"""
        }

    private fun player(
        status: String,
        itags: List<Int>,
    ): String =
        """{"playabilityStatus":{"status":"$status"},"streamingData":{"adaptiveFormats":[""" +
            itags.joinToString(",") { """{"itag":$it}""" } + "]}}"
}
