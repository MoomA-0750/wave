package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
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
import xyz.mooma0750.wave.core.auth.normalizeDataSyncId
import xyz.mooma0750.wave.core.net.AuthRouteViolation
import xyz.mooma0750.wave.core.net.PLAYBACK_STATS_PATH
import xyz.mooma0750.wave.core.net.isAuthRouteAllowed
import xyz.mooma0750.wave.core.net.isPlainHttps443
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

enum class FailureKind {
    NotLoggedIn,
    Expired,
    Suspect,
    Network,
    Http,
    Parse,

    /** 書き込み先のアカウント（DATASYNC_ID）を確かめられていない。書き込み・再生履歴・受け渡しをしない（ADR-0007）。 */
    AccountUnverified,
}

/** 書き込み先のアカウントの確認の結果。 */
enum class AccountVerification {
    /** 保存している DATASYNC_ID が、accountsList の選択中のアカウントと一致した。 */
    Verified,

    /** 一致しなかった、または DATASYNC_ID が無い。 */
    Mismatch,

    /** 通信の失敗などで確かめられなかった（60秒後にもう一度確かめる）。 */
    Unknown,
}

sealed interface CallResult<out T> {
    class Ok<T>(
        val value: T,
    ) : CallResult<T>

    data class Failed(
        val kind: FailureKind,
        val status: Int? = null,
    ) : CallResult<Nothing>
}

/**
 * 書き込み（高く評価・プレイリストの編集など）の結果。アプリは自動で送り直さない。
 * 楽観的に表示を変えず、Done のときだけ確定する。Unknown のときは読み直して確かめる。
 */
sealed interface WriteResult<out T> {
    class Done<T>(
        val value: T,
    ) : WriteResult<T>

    /** 送っていないと確実、または反映されていないと分かる応答（4xx・ログインの問題）。 */
    data class Failed(
        val kind: FailureKind,
        val status: Int? = null,
    ) : WriteResult<Nothing>

    /** 反映されたか分からない（送信後の通信の失敗・中断・5xx・読めない 2xx）。 */
    data object Unknown : WriteResult<Nothing>
}

/** 確認済みのアカウント（識別子の値は HMAC にだけ使う）と、確認したログインの世代。 */
class VerifiedAccount(
    val generationId: Long,
    val id: xyz.mooma0750.wave.core.auth.Secret,
) {
    override fun toString(): String = "VerifiedAccount(generation=$generationId)"
}

/** 再生統計の送信の結果。ログには種類と status の数値だけを出す（ADR-0006 の 5）。 */
sealed interface StatsOutcome {
    data object NotLoggedIn : StatsOutcome

    /** 統計 URL を取った世代と、今の世代が違う（アカウントの切り替えなど）。送っていない。 */
    data object GenerationMismatch : StatsOutcome

    /** 統計 URL の形が想定と違う（ホスト・パス・ポート）。送っていない。 */
    data object ShapeRejected : StatsOutcome

    /** 書き込み先のアカウントを確かめられていない。送っていない。 */
    data object AccountUnverified : StatsOutcome

    data class Sent(
        val status: Int,
        /** 応答が HTML だった（受け付けられていない兆候）。 */
        val html: Boolean,
    ) : StatsOutcome

    data object Violation : StatsOutcome

    data object Failed : StatsOutcome

    data object Cancelled : StatsOutcome
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
    internal val httpClient: HttpClient,
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
        /** YouTube の文字（棚の見出し・ジャンル名など）の言語。null なら端末の言語。地域（gl）は端末のまま。 */
        contentLanguage: String? = null,
    ) : this(
        httpClient = httpClient,
        credentials = authManager.credentials,
        scope = scope,
        retryDelay = { delay(it) },
        rethrowViolations = rethrowViolations,
        nowMs = monotonicMillis(),
        configParser = null,
    ) {
        // 認証情報を公開する前に世代を進める（購読の順序に頼らない、ADR-0007 の同期点 a）
        authManager.beforePublish = { next -> applyCredentials(next, authoritative = true) }
        contentLanguage?.let { hl -> innerTube.locale = sanitizedYouTubeLocale(YouTubeLocale(gl = innerTube.locale.gl, hl = hl)) }
    }

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
        var verification: Deferred<AccountVerification>? = null
        var verificationUnknownAtMs: Long? = null
    }

    private class Tagged(
        val generationId: Long,
        val state: SessionState,
    )

    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)

    private fun childScope(): CoroutineScope = CoroutineScope(sessionScope.coroutineContext + SupervisorJob(sessionJob))

    internal val innerTube =
        InnerTube(httpClient = httpClient, retryDelay = retryDelay).also { it.locale = sanitizedYouTubeLocale(it.locale) }
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
            credentials.collect { next -> applyCredentials(next, authoritative = false) }
        }

    /**
     * @param authoritative AuthManager が公開する前に呼んだ（まだ credentials の値には出ていない）。
     *   false（購読からの反映）なら、今の値と違う古い値は反映しない（先に反映した新しい世代を巻き戻さない）
     */
    private suspend fun applyCredentials(
        next: Credentials?,
        authoritative: Boolean,
    ) {
        generationLock.withLock {
            val old = current.value
            if (old.id > 0 && next === old.credentials) return
            if (!authoritative && old.id > 0 && next !== credentials.value) return
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
        /** 呼び出しの文脈（キューなど）を作ったときのログインの世代。違えば送らない（ADR-0007）。 */
        expectedGeneration: Long? = null,
        parse: suspend (HttpResponse) -> ParseOutcome<T>,
    ): CallResult<T> {
        val generation = current.value
        // ログアウトが AuthManager に反映済みで、まだこの世代に届いていない場合も送らない
        if (isStale(generation)) {
            return CallResult.Failed(FailureKind.NotLoggedIn)
        }
        if (expectedGeneration != null && expectedGeneration != generation.id) return CallResult.Failed(FailureKind.NotLoggedIn)

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

    /**
     * 書き込みの入口。応答の失効・疑いの分類は読み取りと同じだが、書き込みの成功は認証の証明に使わない（ADR-0004）。
     * 「送っていない」と確実に言えるのは、ログインの事前チェックとガード違反だけ。通信の例外・キャンセル（innertubex の
     * セッション変更を含む）は、送信後に起きたかもしれないので Unknown にする。
     */
    internal suspend fun <T> callWrite(
        operation: String,
        block: suspend (InnerTube) -> HttpResponse,
        /** 操作を始めたときのログインの世代。違えば（途中でアカウントが変われば）送らない。 */
        expectedGeneration: Long? = null,
        parse: suspend (HttpResponse) -> ParseOutcome<T>,
    ): WriteResult<T> {
        val generation = current.value
        if (isStale(generation)) {
            return WriteResult.Failed(FailureKind.NotLoggedIn)
        }
        if (expectedGeneration != null && expectedGeneration != generation.id) return WriteResult.Failed(FailureKind.NotLoggedIn)
        // 書き込み先のアカウントを確かめてから送る（ADR-0007）
        if (verify(generation) != AccountVerification.Verified) {
            return if (current.value === generation) WriteResult.Failed(FailureKind.AccountUnverified) else WriteResult.Failed(FailureKind.NotLoggedIn)
        }
        val deferred = generation.scope.async { runCatchingOutcome(block, parse) }
        val outcome =
            try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                deferred.cancel()
                currentCoroutineContext().ensureActive()
                return WriteResult.Unknown
            }
        when {
            outcome is Outcome.Violation -> {
                if (rethrowViolations) throw AuthRouteViolation("operation=$operation")
                return WriteResult.Failed(FailureKind.Http)
            }
            outcome is Outcome.Network -> return WriteResult.Unknown
            outcome is Outcome.Body && outcome.parsed is ParseOutcome.Invalid -> return WriteResult.Unknown
            outcome is Outcome.Status && outcome.status >= 500 -> return WriteResult.Unknown
        }
        return when (val result = classify(generation, outcome, provesAuthentication = false)) {
            is CallResult.Ok -> WriteResult.Done(result.value)
            is CallResult.Failed -> WriteResult.Failed(result.kind, result.status)
        }
    }

    /**
     * 再生の playback 統計を送る（ADR-0006）。応答は失効・疑いの分類に通さない。送り直さない。
     * 統計 URL を取った世代が今の世代と同じときだけ、世代のスコープで送る（切り替え・ログアウトで取り消される）。
     */
    internal suspend fun sendPlaybackStats(
        statsUrl: String,
        statsGeneration: Long,
        cpn: String,
        currentMediaTimeSeconds: String,
    ): StatsOutcome {
        val generation = current.value
        if (isStale(generation)) return StatsOutcome.NotLoggedIn
        if (generation.id != statsGeneration) return StatsOutcome.GenerationMismatch
        val target = playbackStatsTarget(statsUrl) ?: return StatsOutcome.ShapeRejected
        if (verify(generation) != AccountVerification.Verified) return StatsOutcome.AccountUnverified
        // innertubex のセッションの写しを先に取り、その後で世代がまだ同じかを確かめる（切り替えと行き違って新しいアカウントの写しを渡さない）
        val snapshot = innerTube.sessionSnapshot()
        if (!generation.scope.isActive || current.value !== generation) return StatsOutcome.GenerationMismatch
        val deferred =
            generation.scope.async {
                try {
                    val response =
                        innerTube.registerPlaybackWithSession(
                            client = YouTubeClient.WEB_REMIX,
                            url = target,
                            cpn = cpn,
                            playlistId = null,
                            requestSession = snapshot,
                            currentMediaTime = currentMediaTimeSeconds,
                        )
                    // 204 も Content-Type: text/html を付けて返るので、本文のある応答だけを HTML とみなす（実機、2026-09-17）
                    val html = response.status.value != 204 && response.headers[HttpHeaders.ContentType]?.startsWith("text/html") == true
                    response.bodyAsChannel().cancel(null)
                    StatsOutcome.Sent(response.status.value, html)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (httpError: InnerTubeHttpException) {
                    StatsOutcome.Sent(httpError.status.value, html = false)
                } catch (_: AuthRouteViolation) {
                    // rethrowViolations でも投げ直さない。統計は再生サービスの中で送るので、落とさずに件数として記録する（ADR-0006 の 5）
                    StatsOutcome.Violation
                } catch (_: Exception) {
                    StatsOutcome.Failed
                }
            }
        return try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            deferred.cancel()
            currentCoroutineContext().ensureActive()
            StatsOutcome.Cancelled
        }
    }

    /**
     * 書き込み先のアカウントを、世代ごとに1本だけ確かめる（ADR-0007）。保存している DATASYNC_ID（onBehalfOfUser に送る値）が、
     * accountsList の選択中のアカウントの datasyncIdToken と一致すれば Verified。Unknown は60秒だけキャッシュする。
     */
    private suspend fun verify(generation: Generation): AccountVerification {
        val saved = generation.credentials?.dataSyncId?.reveal() ?: return AccountVerification.Mismatch
        val deferred =
            generationLock.withLock {
                val cached = generation.verification
                val unknownAt = generation.verificationUnknownAtMs
                if (cached != null && !cached.isCancelled && (unknownAt == null || nowMs() - unknownAt < UNKNOWN_CACHE_MS)) {
                    cached
                } else {
                    generation.verificationUnknownAtMs = null
                    generation.scope
                        .async {
                            fetchVerification(generation, saved).also { result ->
                                if (result == AccountVerification.Unknown) generationLock.withLock { generation.verificationUnknownAtMs = nowMs() }
                            }
                        }.also { generation.verification = it }
                }
            }
        return try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            AccountVerification.Unknown
        }
    }

    private suspend fun fetchVerification(
        generation: Generation,
        saved: String,
    ): AccountVerification {
        if (current.value !== generation) return AccountVerification.Unknown
        val result =
            call("accounts_list", block = { it.accountsList(YouTubeClient.WEB_REMIX) }, expectedGeneration = generation.id) { response ->
                ParseOutcome.Parsed(accountTokensOf(json.parseToJsonElement(response.bodyAsTextLimited(MAX_PLAYER_BYTES))))
            }
        val tokens = (result as? CallResult.Ok)?.value ?: return AccountVerification.Unknown
        // 選択中のアカウントがちょうど1件のときだけ判定する（0件・複数件は形が想定と違う）
        val selected = tokens.filter { it.first }.map { it.second }.distinct()
        if (selected.size != 1) return AccountVerification.Unknown
        return if (selected.single() == saved) AccountVerification.Verified else AccountVerification.Mismatch
    }

    /**
     * 確認済みのアカウントと、確認したログインの世代。受け渡し・ペアリングでは、確認した後の操作（取り込み・保存）の直前に、
     * この世代が今も続いているかを確かめる（確認と操作の間にアカウントが切り替わった場合に、前のアカウントの結果を使わない）。
     */
    suspend fun verifiedAccount(): VerifiedAccount? {
        val generation = current.value
        val id = generation.credentials?.dataSyncId ?: return null
        return if (verify(generation) == AccountVerification.Verified && current.value === generation) VerifiedAccount(generation.id, id) else null
    }

    /** その世代はもう使えない（ログアウトした・資格情報が差し替わった）。送る前の確認。 */
    private fun isStale(generation: Generation): Boolean = generation.credentials == null || credentials.value !== generation.credentials

    /** 今のログインの世代（受け渡し・書き込み・曲の解決を、始めたときのログインに結び付けるのに使う）。 */
    fun loginGenerationId(): Long = current.value.id

    /** 今のログインの、書き込み先のアカウントの確認（UI・受け渡し用）。 */
    suspend fun accountVerification(): AccountVerification {
        val generation = current.value
        if (generation.credentials == null) return AccountVerification.Mismatch
        return verify(generation)
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

private val PLAYBACK_STATS_SOURCE_HOSTS = setOf("s.youtube.com", "music.youtube.com", "www.youtube.com")

/**
 * /player の応答の統計 URL を、送ってよい宛先（music.youtube.com の /api/stats/playback）に組み立て直す（ADR-0006 の 2・3）。
 * 応答の値は信用せず、https・443・既知のホスト・パス完全一致でなければ null。list=（playlistId）は外す。
 */
internal fun playbackStatsTarget(statsUrl: String): String? {
    val parsed = runCatching { Url(statsUrl) }.getOrNull() ?: return null
    if (!parsed.isPlainHttps443()) return null
    if (parsed.host !in PLAYBACK_STATS_SOURCE_HOSTS || parsed.encodedPath != PLAYBACK_STATS_PATH) return null
    val rewritten =
        URLBuilder(parsed)
            .apply {
                host = "music.youtube.com"
                parameters.remove("list")
                fragment = ""
            }.build()
    return rewritten.takeIf { isAuthRouteAllowed(it) }?.toString()
}

/**
 * accountsList の応答から、各アカウントの（選択中か, datasyncIdToken の `||` より前）を集める。
 * 実アカウントで確かめた場所（2026-09-17、キー名だけ）: accountItem.isSelected と、その下の selectActiveIdentityEndpoint.supportedTokens[].datasyncIdToken.datasyncIdToken
 */
internal fun accountTokensOf(root: kotlinx.serialization.json.JsonElement): List<Pair<Boolean, String>> {
    val tokens = mutableListOf<Pair<Boolean, String>>()
    fun walk(
        element: kotlinx.serialization.json.JsonElement,
        selected: Boolean,
    ) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                val isSelected = selected || (element["isSelected"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                element.forEach { (name, value) ->
                    if (name == "datasyncIdToken" && value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                        normalizeDataSyncId(value.content)?.let { tokens += isSelected to it }
                    }
                    walk(value, isSelected)
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walk(it, selected) }
            else -> Unit
        }
    }
    walk(root, false)
    return tokens
}

/**
 * innertubex は端末の言語を `toLanguageTag()` のまま hl に入れる。Samsung などは `en-US-u-mu-celsius` のように
 * 拡張（1文字の区切りから後ろ）を付けるので、そのまま送ると全ての要求が 400 になる。拡張を落とし、形が崩れていれば既定に戻す。
 */
internal fun sanitizedYouTubeLocale(locale: YouTubeLocale): YouTubeLocale {
    val subtags = locale.hl.split('-', '_').takeWhile { it.length > 1 }
    val hl =
        subtags
            .takeIf { tags -> tags.isNotEmpty() && tags.first() != "und" && tags.all { tag -> tag.length <= 8 && tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } } }
            ?.joinToString("-")
            ?: DEFAULT_HL
    val gl = locale.gl.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: DEFAULT_GL
    return YouTubeLocale(gl = gl, hl = hl)
}

private const val DEFAULT_HL = "en"
private const val DEFAULT_GL = "US"
