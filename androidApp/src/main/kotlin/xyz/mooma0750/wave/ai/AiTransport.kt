package xyz.mooma0750.wave.ai

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.settings.AppSettings
import xyz.mooma0750.wave.watchlink.WatchLogin
import xyz.mooma0750.wave.watchlink.await

/** Claude の中継（tools/ai-bridge）への行き方。スマホは直接、時計はスマホに中継してもらう（時計は tailnet に届かない）。 */
interface AiTransport {
    enum class Outcome { Done, NotConfigured, Unauthorized, Unreachable }

    /** /v1/agent に頼みを送り、返ってくる行ごとに onLine を呼ぶ。onLine が false を返したら（答えの終わり）読むのをやめる。 */
    suspend fun stream(
        body: String,
        onLine: suspend (String) -> Boolean,
    ): Outcome

    /** 道具の結果を返す（/v1/agent/result）。 */
    suspend fun postResult(body: String)
}

/** スマホ: 設定した中継へ直接送る。 */
class DirectAiTransport(
    private val settings: AppSettings,
) : AiTransport {
    // 認証情報を持たないクライアント（cookie・リダイレクトなし）。答えは長く流れるので読み取りの時間切れは長めにする
    private val http =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    override suspend fun stream(
        body: String,
        onLine: suspend (String) -> Boolean,
    ): AiTransport.Outcome {
        val config = settings.aiBridge.first() ?: return AiTransport.Outcome.NotConfigured
        val request =
            Request
                .Builder()
                .url(config.url + "/v1/agent")
                .header("Authorization", "Bearer " + config.token)
                .post(body.toRequestBody(JSON))
                .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) return@use AiTransport.Outcome.Unauthorized
                if (!response.isSuccessful) return@use AiTransport.Outcome.Unreachable
                val source = response.body.source()
                while (true) if (!onLine(source.readUtf8Line() ?: break)) break
                AiTransport.Outcome.Done
            }
        }
    }

    override suspend fun postResult(body: String) {
        val config = settings.aiBridge.first() ?: return
        val request =
            Request
                .Builder()
                .url(config.url + "/v1/agent/result")
                .header("Authorization", "Bearer " + config.token)
                .post(body.toRequestBody(JSON))
                .build()
        withContext(Dispatchers.IO) { runCatching { http.newCall(request).execute().close() } }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 時計: 頼みをスマホへ送り、スマホが中継とやりとりした行を受け取る（Data Layer）。中継のトークンは時計に置かない。
 * 道具（曲を流すなど）は時計の上で動く。
 */
class PhoneRelayAiTransport(
    context: Context,
) : AiTransport {
    private val appContext = context.applicationContext
    private val inbox = ConcurrentHashMap<String, Channel<Pair<Int, String>>>()

    override suspend fun stream(
        body: String,
        onLine: suspend (String) -> Boolean,
    ): AiTransport.Outcome {
        val node = phone() ?: return AiTransport.Outcome.Unreachable
        val id = UUID.randomUUID().toString()
        val channel = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        inbox[id] = channel
        try {
            if (!send(node, PATH_AGENT, "$id\n$body")) return AiTransport.Outcome.Unreachable
            // 届く順が入れ替わってもよいように、番号の順に並べ直す
            val waiting = sortedMapOf<Int, String>()
            var next = 0
            while (true) {
                val (seq, line) = withTimeoutOrNull(IDLE_TIMEOUT_MS) { channel.receive() } ?: return AiTransport.Outcome.Unreachable
                waiting[seq] = line
                while (waiting.containsKey(next)) {
                    val current = waiting.remove(next)!!
                    next++
                    if (current.startsWith(END_PREFIX)) return runCatching { AiTransport.Outcome.valueOf(current.removePrefix(END_PREFIX)) }.getOrDefault(AiTransport.Outcome.Unreachable)
                    // 答えの終わりの後も、スマホからの終わりの印までは読む（次の頼みに混ざらないように）
                    onLine(current)
                }
            }
        } finally {
            inbox.remove(id)
        }
    }

    override suspend fun postResult(body: String) {
        phone()?.let { send(it, PATH_RESULT, body) }
    }

    /** スマホから行が届いた（WatchLinkListener から）。 */
    fun onLine(message: String) {
        val id = message.substringBefore('\n')
        val rest = message.substringAfter('\n')
        val seq = rest.substringBefore('\n').toIntOrNull() ?: return
        inbox[id]?.trySend(seq to rest.substringAfter('\n'))
    }

    private suspend fun phone(): String? =
        runCatching { Wearable.getCapabilityClient(appContext).getCapability(WatchLogin.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes }
            .getOrNull()
            .orEmpty()
            .sortedByDescending { it.isNearby }
            .firstOrNull()
            ?.id

    private suspend fun send(
        node: String,
        path: String,
        text: String,
    ): Boolean = runCatching { Wearable.getMessageClient(appContext).sendMessage(node, path, text.encodeToByteArray()).await() }.isSuccess

    companion object {
        const val PATH_AGENT = "/wave/ai/agent"
        const val PATH_RESULT = "/wave/ai/result"
        const val PATH_LINE = "/wave/ai/line"
        const val END_PREFIX = "wave:end:"
        private const val IDLE_TIMEOUT_MS = 130_000L
    }
}

/** スマホ: 時計から頼まれた Claude への頼みを、自分の中継の設定で送り、行を時計へ返す。 */
class AiRelayHost(
    context: Context,
    private val direct: DirectAiTransport,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    fun onAgent(
        node: String,
        message: String,
    ) {
        val id = message.substringBefore('\n')
        val body = message.substringAfter('\n')
        scope.launch {
            var seq = 0
            suspend fun reply(line: String) {
                runCatching { Wearable.getMessageClient(appContext).sendMessage(node, PhoneRelayAiTransport.PATH_LINE, "$id\n${seq++}\n$line".encodeToByteArray()).await() }
            }
            val outcome =
                runCatching {
                    direct.stream(body) { line ->
                        reply(line)
                        !isAgentDone(line)
                    }
                }.getOrDefault(AiTransport.Outcome.Unreachable)
            reply(PhoneRelayAiTransport.END_PREFIX + outcome.name)
            Log.i(WaveApplication.LOG_TAG, "ai relay for watch outcome=$outcome lines=$seq")
        }
    }

    fun onResult(body: String) {
        scope.launch { direct.postResult(body) }
    }
}

/** 中継の答えの終わりの行（{"done": ...}）。 */
fun isAgentDone(line: String): Boolean =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }.getOrNull()?.containsKey("done") == true
