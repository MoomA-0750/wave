package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.net.AuthRouteViolation
import kotlin.time.Duration

sealed interface SessionState {
    data object LoggedOut : SessionState

    data object Active : SessionState

    /** ログイン中のはずなのに、失効を疑う応答が続いている。確認（accountMenu）が走る。 */
    data class Suspect(
        val reason: SuspectReason,
        val consecutive: Int,
    ) : SessionState

    data class Expired(
        val reason: ExpiredReason,
    ) : SessionState
}

enum class SuspectReason { EmptyResponse, LoginRequired, Forbidden, Redirect }

enum class ExpiredReason { Unauthenticated, RedirectedToSignIn, ConfirmedLoggedOut }

enum class PremiumStatus { Available, Unavailable, Unknown }

enum class FailureKind { NotLoggedIn, Expired, Suspect, Network, Http, Parse }

sealed interface CallResult<out T> {
    class Ok<T>(
        val value: T,
    ) : CallResult<T>

    data class Failed(
        val kind: FailureKind,
        val status: Int? = null,
    ) : CallResult<Nothing>
}

sealed interface ParseOutcome<out T> {
    class Parsed<T>(
        val value: T,
    ) : ParseOutcome<T>

    /** 2xx だが、ログアウト相当の中身。 */
    data object LoggedOutResponse : ParseOutcome<Nothing>

    /** playabilityStatus が LOGIN_REQUIRED。 */
    data object LoginRequired : ParseOutcome<Nothing>

    data object Invalid : ParseOutcome<Nothing>
}

sealed interface AccountResult {
    class Ok(
        val name: String?,
        val handle: String?,
        val thumbnailUrl: String?,
    ) : AccountResult

    data class Failed(
        val kind: FailureKind,
    ) : AccountResult
}

/**
 * InnerTube（music.youtube.com の youtubei/v1/ 以下）専用の認証付きチャネル。
 * 音声バイトの取得には使わない。この HttpClient を再生エンジンに渡さない。
 *
 * ログイン状態が変わるたびに「世代」を作り直す。進行中の呼び出しは古い世代の Job ごとキャンセルする
 * （innertubex は player・browse・accountMenu を世代の変化でキャンセルしないため）。
 */
class WaveSession internal constructor(
    private val httpClient: HttpClient,
    private val credentials: StateFlow<Credentials?>,
    private val scope: CoroutineScope,
    retryDelay: suspend (Duration) -> Unit,
    private val rethrowViolations: Boolean,
    private val nowEpochMs: () -> Long,
    configParser: ((HttpClient, InnerTube) -> YtConfigParser)?,
) : AutoCloseable {
    constructor(
        httpClient: HttpClient,
        authManager: AuthManager,
        scope: CoroutineScope,
        rethrowViolations: Boolean,
    ) : this(
        httpClient = httpClient,
        credentials = authManager.credentials,
        scope = scope,
        retryDelay = { delay(it) },
        rethrowViolations = rethrowViolations,
        nowEpochMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        configParser = null,
    )

    private class Generation(
        val id: Long,
        /** この世代の呼び出しをまとめてキャンセルするためのスコープ。 */
        val scope: CoroutineScope,
        val loggedIn: Boolean,
    ) {
        var confirm: Deferred<Unit>? = null
        var premium: Deferred<PremiumStatus>? = null
        var premiumUnknownAtEpochMs: Long? = null
    }

    private fun childScope(): CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    internal val innerTube = InnerTube(httpClient = httpClient, retryDelay = retryDelay)
    private val ytConfigParser: YtConfigParser =
        configParser?.invoke(httpClient, innerTube) ?: YtConfigParserImpl(httpClient, innerTube)
    private val generationLock = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    private val current = MutableStateFlow(Generation(0, childScope(), loggedIn = false))
    private var applied: Credentials? = null

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val subscription =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            credentials.collect { next -> applyCredentials(next) }
        }

    private suspend fun applyCredentials(next: Credentials?) {
        generationLock.withLock {
            if (next === applied && current.value.id > 0) return
            applied = next
            val old = current.value
            old.scope.cancel()
            if (next != null) {
                innerTube.replaceSession(
                    cookie = next.cookie.reveal(),
                    visitorData = next.visitorData,
                    dataSyncId = next.dataSyncId?.reveal(),
                    authUser = "0",
                    useLoginForBrowse = true,
                )
            } else {
                // clearSessionIfMatches は CAS を1回しか試さないので使わない
                innerTube.replaceSession(cookie = null, visitorData = null, dataSyncId = null, authUser = "0", useLoginForBrowse = false)
            }
            current.value = Generation(old.id + 1, childScope(), loggedIn = next != null)
            mutableState.value = if (next != null) SessionState.Active else SessionState.LoggedOut
        }
    }

    private sealed interface Outcome<out T> {
        class Body<T>(
            val parsed: ParseOutcome<T>,
        ) : Outcome<T>

        data class Status(
            val status: Int,
        ) : Outcome<Nothing>

        data object Violation : Outcome<Nothing>

        data object Network : Outcome<Nothing>
    }

    /** InnerTube 呼び出しの入口。応答を分類し、state を更新する。 */
    suspend fun <T> call(
        operation: String,
        block: suspend (InnerTube) -> HttpResponse,
        parse: suspend (HttpResponse) -> ParseOutcome<T>,
    ): CallResult<T> {
        val generation = current.value
        if (!generation.loggedIn) return CallResult.Failed(FailureKind.NotLoggedIn)

        val deferred =
            generation.scope.async {
                runCatchingOutcome(block, parse)
            }
        val outcome =
            try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                deferred.cancel()
                currentCoroutineContext().ensureActive()
                // 呼び出し元は生きていて、世代が変わった（ログアウト・再ログイン）
                return CallResult.Failed(FailureKind.NotLoggedIn)
            }
        if (outcome is Outcome.Violation && rethrowViolations) throw AuthRouteViolation("($operation)")
        return classify(generation, outcome)
    }

    private suspend fun <T> runCatchingOutcome(
        block: suspend (InnerTube) -> HttpResponse,
        parse: suspend (HttpResponse) -> ParseOutcome<T>,
    ): Outcome<T> =
        try {
            val response = block(innerTube)
            val status = response.status.value
            if (status in 200..299) {
                Outcome.Body(runCatchingParse { parse(response) })
            } else {
                response.bodyAsChannel().cancel(null)
                Outcome.Status(status)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (httpError: InnerTubeHttpException) {
            Outcome.Status(httpError.status.value)
        } catch (_: AuthRouteViolation) {
            Outcome.Violation
        } catch (_: Exception) {
            Outcome.Network
        }

    private suspend fun <T> runCatchingParse(parse: suspend () -> ParseOutcome<T>): ParseOutcome<T> =
        try {
            parse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ParseOutcome.Invalid
        }

    private suspend fun <T> classify(
        generation: Generation,
        outcome: Outcome<T>,
    ): CallResult<T> =
        when (outcome) {
            is Outcome.Body ->
                when (val parsed = outcome.parsed) {
                    is ParseOutcome.Parsed -> {
                        setState(generation, SessionState.Active)
                        CallResult.Ok(parsed.value)
                    }
                    ParseOutcome.LoggedOutResponse -> suspect(generation, SuspectReason.EmptyResponse, status = null)
                    ParseOutcome.LoginRequired -> suspect(generation, SuspectReason.LoginRequired, status = null)
                    ParseOutcome.Invalid -> CallResult.Failed(FailureKind.Parse)
                }
            is Outcome.Status ->
                when (outcome.status) {
                    401 -> {
                        setState(generation, SessionState.Expired(ExpiredReason.Unauthenticated))
                        CallResult.Failed(FailureKind.Expired, 401)
                    }
                    403 -> suspect(generation, SuspectReason.Forbidden, 403)
                    in 300..399 -> suspect(generation, SuspectReason.Redirect, outcome.status)
                    else -> CallResult.Failed(FailureKind.Http, outcome.status)
                }
            Outcome.Violation -> CallResult.Failed(FailureKind.Http)
            Outcome.Network -> CallResult.Failed(FailureKind.Network)
        }

    private suspend fun suspect(
        generation: Generation,
        reason: SuspectReason,
        status: Int?,
    ): CallResult.Failed {
        if (current.value === generation) {
            mutableState.update { previous ->
                when (previous) {
                    is SessionState.Expired -> previous
                    is SessionState.Suspect -> SessionState.Suspect(reason, previous.consecutive + 1)
                    else -> SessionState.Suspect(reason, 1)
                }
            }
            startConfirm(generation)
        }
        return CallResult.Failed(FailureKind.Suspect, status)
    }

    private fun setState(
        generation: Generation,
        state: SessionState,
    ) {
        if (current.value === generation) mutableState.value = state
    }

    private val confirmLock = Mutex()

    /** 世代ごとに同時に1本だけ。accountMenu（応答がそのまま返る）で、ログインが外れているかを確かめる。 */
    private suspend fun startConfirm(generation: Generation) {
        confirmLock.withLock {
            if (generation.confirm?.isActive == true) return
            generation.confirm = generation.scope.async { confirm(generation) }
        }
    }

    private suspend fun confirm(generation: Generation) {
        val response =
            try {
                innerTube.accountMenu(YouTubeClient.WEB_REMIX)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return
            }
        val status = response.status.value
        when {
            status in 300..399 -> {
                val host = response.headers[HttpHeaders.Location]?.let { runCatching { Url(it).host }.getOrNull() }
                response.bodyAsChannel().cancel(null)
                if (host == SIGN_IN_HOST) setState(generation, SessionState.Expired(ExpiredReason.RedirectedToSignIn))
            }
            status == 401 -> {
                response.bodyAsChannel().cancel(null)
                setState(generation, SessionState.Expired(ExpiredReason.Unauthenticated))
            }
            status in 200..299 -> {
                val header =
                    try {
                        parseActiveAccount(response)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        return
                    }
                if (header == null) {
                    setState(generation, SessionState.Expired(ExpiredReason.ConfirmedLoggedOut))
                } else {
                    setState(generation, SessionState.Active)
                }
            }
            else -> response.bodyAsChannel().cancel(null)
        }
    }

    suspend fun accountInfo(): AccountResult {
        val result =
            call("accountMenu", { it.accountMenu(YouTubeClient.WEB_REMIX) }) { response ->
                val account = parseActiveAccount(response)
                if (account == null) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(account)
            }
        return when (result) {
            is CallResult.Ok -> result.value
            is CallResult.Failed -> AccountResult.Failed(result.kind)
        }
    }

    /**
     * 世代ごとに1本だけ判定する。Available・Unavailable はその世代のあいだ、Unknown は60秒だけキャッシュする。
     */
    suspend fun premiumStatus(): PremiumStatus {
        val generation = current.value
        if (!generation.loggedIn) return PremiumStatus.Unknown
        val deferred =
            generationLock.withLock {
                val cached = generation.premium
                val unknownAt = generation.premiumUnknownAtEpochMs
                val reusable =
                    cached != null &&
                        (unknownAt == null || nowEpochMs() - unknownAt < UNKNOWN_CACHE_MS)
                if (reusable) {
                    cached
                } else {
                    generation.premiumUnknownAtEpochMs = null
                    generation.scope
                        .async {
                            probePremium().also { status ->
                                if (status == PremiumStatus.Unknown) generation.premiumUnknownAtEpochMs = nowEpochMs()
                            }
                        }.also { generation.premium = it }
                }
            }
        return try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            PremiumStatus.Unknown
        }
    }

    private suspend fun probePremium(): PremiumStatus {
        if (accountInfo() !is AccountResult.Ok) return PremiumStatus.Unknown
        val sts =
            try {
                ytConfigParser.fetchConfig(PROBE_VIDEO_ID, useLoginCookies = false).signatureTimestamp
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return PremiumStatus.Unknown
        val player =
            call("player", { it.player(YouTubeClient.WEB_REMIX, PROBE_VIDEO_ID, signatureTimestamp = sts) }) { response ->
                val body = json.decodeFromString(PlayerProbeResponse.serializer(), response.bodyAsTextLimited(MAX_PLAYER_BYTES))
                when (body.playabilityStatus?.status) {
                    "LOGIN_REQUIRED" -> ParseOutcome.LoginRequired
                    else -> ParseOutcome.Parsed(body)
                }
            }
        val body = (player as? CallResult.Ok)?.value ?: return PremiumStatus.Unknown
        return premiumStatusOf(body)
    }

    override fun close() {
        subscription.cancel()
        current.value.scope.cancel()
        innerTube.close()
    }

    internal companion object {
        const val SIGN_IN_HOST = "accounts.google.com"

        /** 判定に使う曲。削除されにくい公式の音源にする（実機で 141/774 が出ることを確認して決める）。 */
        const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"

        const val UNKNOWN_CACHE_MS = 60_000L
        const val MAX_ACCOUNT_MENU_BYTES = 2 * 1024 * 1024
        const val MAX_PLAYER_BYTES = 4 * 1024 * 1024

        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        internal fun create(
            httpClient: HttpClient,
            credentials: StateFlow<Credentials?>,
            scope: CoroutineScope,
            retryDelay: suspend (Duration) -> Unit = {},
            rethrowViolations: Boolean = false,
            nowEpochMs: () -> Long = { 0L },
            configParser: ((HttpClient, InnerTube) -> YtConfigParser)? = null,
        ) = WaveSession(httpClient, credentials, scope, retryDelay, rethrowViolations, nowEpochMs, configParser)
    }
}
