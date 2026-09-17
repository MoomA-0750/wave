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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.net.AuthRouteViolation
import kotlin.time.Duration
import kotlin.time.TimeSource

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
 * ログイン状態が変わるたびに「世代」を作り直す。進行中の呼び出しは古い世代のスコープごとキャンセルする
 * （innertubex は player・browse・accountMenu を世代の変化でキャンセルしないため）。
 * 状態は (世代, SessionState) の組で CAS 更新し、古い世代の結果が新しい世代の状態を上書きしないようにする。
 *
 * 呼び出しの block と parse は scope のディスパッチャで走る。メインスレッドのスコープを渡さないこと。
 */
class WaveSession internal constructor(
    httpClient: HttpClient,
    private val credentials: StateFlow<Credentials?>,
    scope: CoroutineScope,
    retryDelay: suspend (Duration) -> Unit,
    private val rethrowViolations: Boolean,
    /** 単調時計のミリ秒。壁時計を使うと、端末の時刻の巻き戻しでキャッシュが固定される。 */
    private val nowMs: () -> Long,
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
        nowMs = monotonicMillis(),
        configParser = null,
    )

    private class Generation(
        val id: Long,
        /** この世代の呼び出しをまとめてキャンセルするためのスコープ。 */
        val scope: CoroutineScope,
        val credentials: Credentials?,
    ) {
        // 以下は generationLock の中でだけ読み書きする
        var confirm: Deferred<Unit>? = null
        var lastConfirmAtMs: Long? = null
        var premium: Deferred<PremiumStatus>? = null
        var premiumUnknownAtMs: Long? = null
    }

    private class Tagged(
        val generationId: Long,
        val state: SessionState,
    )

    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)

    private fun childScope(): CoroutineScope = CoroutineScope(sessionScope.coroutineContext + SupervisorJob(sessionJob))

    internal val innerTube = InnerTube(httpClient = httpClient, retryDelay = retryDelay)
    private val ytConfigParser: YtConfigParser =
        configParser?.invoke(httpClient, innerTube) ?: YtConfigParserImpl(httpClient, innerTube)
    private val generationLock = Mutex()
    private val current = MutableStateFlow(Generation(0, childScope(), credentials = null))
    private val tagged = MutableStateFlow(Tagged(0, SessionState.LoggedOut))
    private val mutableState = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableAppliedObtainedAt = MutableStateFlow<Long?>(null)

    /** このセッションに反映済みのログインの取得時刻（未ログインなら null）。UI が「このログインが反映された」ことを待つのに使う。 */
    val appliedObtainedAtEpochMs: StateFlow<Long?> = mutableAppliedObtainedAt.asStateFlow()

    // 他のプロパティをすべて初期化してから購読を始める（UNDISPATCHED なので、ここで最初の値を反映する）
    private val subscription =
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            credentials.collect { next -> applyCredentials(next) }
        }

    private suspend fun applyCredentials(next: Credentials?) {
        generationLock.withLock {
            val old = current.value
            if (old.id > 0 && next === old.credentials) return
            if (!sessionJob.isActive) return
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
                clearInnerTubeSession()
            }
            val generation = Generation(old.id + 1, childScope(), next)
            current.value = generation
            tagged.value = Tagged(generation.id, if (next != null) SessionState.Active else SessionState.LoggedOut)
            publishState()
            mutableAppliedObtainedAt.value = next?.obtainedAtEpochMs
            // close() と行き違った場合に、cookie をメモリに残さない
            if (!sessionJob.isActive) clearInnerTubeSession()
        }
    }

    private fun clearInnerTubeSession() {
        // clearSessionIfMatches は CAS を1回しか試さないので使わない
        innerTube.replaceSession(cookie = null, visitorData = null, dataSyncId = null, authUser = "0", useLoginForBrowse = false)
    }

    /** generationId が現在の世代と一致するときだけ状態を変える。 */
    internal fun updateStateFor(
        generationId: Long,
        transform: (SessionState) -> SessionState,
    ): Boolean {
        while (true) {
            val previous = tagged.value
            if (previous.generationId != generationId) return false
            if (tagged.compareAndSet(previous, Tagged(generationId, transform(previous.state)))) {
                publishState()
                return true
            }
        }
    }

    internal fun currentGenerationId(): Long = current.value.id

    /** テスト用: 走っている確認（accountMenu）が終わるまで待つ。 */
    internal suspend fun awaitConfirmIdle() {
        generationLock.withLock { current.value.confirm }?.join()
    }

    /** tagged の最新値を公開用の StateFlow に写す。並行して写した場合も、最後は最新値になる。 */
    private fun publishState() {
        while (true) {
            val latest = tagged.value
            mutableState.value = latest.state
            if (tagged.value === latest) return
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

    /**
     * InnerTube 呼び出しの入口。応答を分類し、state を更新する。core の外からは呼ばせない（素の InnerTube を触らせない）。
     *
     * @param provesAuthentication この応答の成功が「ログインが有効」を意味するか（accountMenu のヘッダなど）。
     *   匿名でも成功する検索などで Suspect・Expired を解除しないよう、既定は false
     */
    internal suspend fun <T> call(
        operation: String,
        provesAuthentication: Boolean = false,
        block: suspend (InnerTube) -> HttpResponse,
        parse: suspend (HttpResponse) -> ParseOutcome<T>,
    ): CallResult<T> {
        val generation = current.value
        // ログアウトが AuthManager に反映済みで、まだこの世代に届いていない場合も送らない
        if (generation.credentials == null || credentials.value !== generation.credentials) {
            return CallResult.Failed(FailureKind.NotLoggedIn)
        }

        val deferred = generation.scope.async { runCatchingOutcome(block, parse) }
        val outcome =
            try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                deferred.cancel()
                currentCoroutineContext().ensureActive()
                // 世代が変わったなら未ログイン。世代がそのままなら innertubex 側の中断（その世代のセッション変更）
                return if (generation.scope.isActive && current.value === generation) {
                    CallResult.Failed(FailureKind.Network)
                } else {
                    CallResult.Failed(FailureKind.NotLoggedIn)
                }
            }
        if (outcome is Outcome.Violation && rethrowViolations) throw AuthRouteViolation("operation=$operation")
        return classify(generation, outcome, provesAuthentication)
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
        provesAuthentication: Boolean,
    ): CallResult<T> =
        when (outcome) {
            is Outcome.Body ->
                when (val parsed = outcome.parsed) {
                    is ParseOutcome.Parsed -> {
                        if (provesAuthentication) updateStateFor(generation.id) { SessionState.Active }
                        CallResult.Ok(parsed.value)
                    }
                    ParseOutcome.LoggedOutResponse -> suspect(generation, SuspectReason.EmptyResponse, status = null)
                    ParseOutcome.LoginRequired -> suspect(generation, SuspectReason.LoginRequired, status = null)
                    ParseOutcome.Invalid -> CallResult.Failed(FailureKind.Parse)
                }
            is Outcome.Status ->
                when (outcome.status) {
                    401 -> {
                        updateStateFor(generation.id) { SessionState.Expired(ExpiredReason.Unauthenticated) }
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
        var needsConfirm = false
        updateStateFor(generation.id) { previous ->
            when (previous) {
                is SessionState.Expired -> {
                    needsConfirm = false
                    previous
                }
                is SessionState.Suspect -> {
                    needsConfirm = true
                    SessionState.Suspect(reason, previous.consecutive + 1)
                }
                else -> {
                    needsConfirm = true
                    SessionState.Suspect(reason, 1)
                }
            }
        }
        if (needsConfirm) startConfirm(generation)
        return CallResult.Failed(FailureKind.Suspect, status)
    }

    /**
     * accountMenu（応答がそのまま返る）で、ログインが外れているかを確かめる。
     * 世代ごとに同時に1本だけ、かつ前回の開始から CONFIRM_INTERVAL_MS 以上あける（レート制限中に負荷を上乗せしない）。
     */
    private suspend fun startConfirm(generation: Generation) {
        generationLock.withLock {
            if (generation.confirm?.isActive == true) return
            val last = generation.lastConfirmAtMs
            if (last != null && nowMs() - last < CONFIRM_INTERVAL_MS) return
            generation.lastConfirmAtMs = nowMs()
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
                if (host == SIGN_IN_HOST) expire(generation, ExpiredReason.RedirectedToSignIn)
            }
            status == 401 -> {
                response.bodyAsChannel().cancel(null)
                expire(generation, ExpiredReason.Unauthenticated)
            }
            status in 200..299 -> {
                val body =
                    try {
                        parseAccountMenu(response)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        return
                    }
                when {
                    activeAccountOf(body) != null ->
                        updateStateFor(generation.id) { previous -> if (previous is SessionState.Suspect) SessionState.Active else previous }
                    showsLoggedOut(body) -> expire(generation, ExpiredReason.ConfirmedLoggedOut)
                }
            }
            else -> response.bodyAsChannel().cancel(null)
        }
    }

    private fun expire(
        generation: Generation,
        reason: ExpiredReason,
    ) {
        updateStateFor(generation.id) { SessionState.Expired(reason) }
    }

    suspend fun accountInfo(): AccountResult {
        val result =
            call("accountMenu", provesAuthentication = true, block = { it.accountMenu(YouTubeClient.WEB_REMIX) }) { response ->
                val account = activeAccountOf(parseAccountMenu(response))
                if (account == null) ParseOutcome.LoggedOutResponse else ParseOutcome.Parsed(account)
            }
        return when (result) {
            is CallResult.Ok -> result.value
            is CallResult.Failed -> AccountResult.Failed(result.kind)
        }
    }

    /**
     * 表示用の診断値。固定の1曲で Premium 音質のフォーマットが観測できたかを返すだけで、アカウントの資格の判定ではない
     * （地域・曲の差し替え・一時的な欠落で変わる）。再生時の音質選択には使わず、再生する曲のフォーマットで決めること。
     *
     * 世代ごとに1本だけ判定する。Available・Unavailable はその世代のあいだ、Unknown は60秒だけキャッシュする。
     */
    suspend fun premiumStatus(): PremiumStatus {
        val generation = current.value
        if (generation.credentials == null) return PremiumStatus.Unknown
        val deferred =
            generationLock.withLock {
                val cached = generation.premium
                val unknownAt = generation.premiumUnknownAtMs
                val reusable =
                    cached != null &&
                        !cached.isCancelled &&
                        (unknownAt == null || nowMs() - unknownAt < UNKNOWN_CACHE_MS)
                if (cached != null && reusable) {
                    cached
                } else {
                    generation.premiumUnknownAtMs = null
                    generation.scope
                        .async {
                            probePremium().also { status ->
                                if (status == PremiumStatus.Unknown) {
                                    generationLock.withLock { generation.premiumUnknownAtMs = nowMs() }
                                }
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
            call("player", block = { it.player(YouTubeClient.WEB_REMIX, PROBE_VIDEO_ID, signatureTimestamp = sts) }) { response ->
                val body = json.decodeFromString(PlayerProbeResponse.serializer(), response.bodyAsTextLimited(MAX_PLAYER_BYTES))
                when (body.playabilityStatus?.status) {
                    "LOGIN_REQUIRED" -> ParseOutcome.LoginRequired
                    else -> ParseOutcome.Parsed(body)
                }
            }
        val body = (player as? CallResult.Ok)?.value ?: return PremiumStatus.Unknown
        return premiumStatusOf(body)
    }

    /** 購読と進行中の呼び出しを止め、innertubex のセッションから cookie を消す。HttpClient は閉じない。 */
    override fun close() {
        sessionJob.cancel()
        clearInnerTubeSession()
        innerTube.close()
    }

    internal companion object {
        const val SIGN_IN_HOST = "accounts.google.com"

        /** 判定に使う曲。Pixel 3・日本・Premium アカウントで 141/774 が出ることを確認した（2026-09-17）。 */
        const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"

        const val UNKNOWN_CACHE_MS = 60_000L
        const val CONFIRM_INTERVAL_MS = 30_000L
        const val MAX_ACCOUNT_MENU_BYTES = 2 * 1024 * 1024
        const val MAX_PLAYER_BYTES = 4 * 1024 * 1024

        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        fun monotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }

        internal fun create(
            httpClient: HttpClient,
            credentials: StateFlow<Credentials?>,
            scope: CoroutineScope,
            retryDelay: suspend (Duration) -> Unit = {},
            rethrowViolations: Boolean = false,
            nowMs: () -> Long = { 0L },
            configParser: ((HttpClient, InnerTube) -> YtConfigParser)? = null,
        ) = WaveSession(httpClient, credentials, scope, retryDelay, rethrowViolations, nowMs, configParser)
    }
}
