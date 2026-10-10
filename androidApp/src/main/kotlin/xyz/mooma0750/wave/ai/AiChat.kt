package xyz.mooma0750.wave.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.settings.AiBridgeConfig
import xyz.mooma0750.wave.settings.AppSettings
import java.util.concurrent.TimeUnit

/** 会話の1つ分の表示。 */
sealed interface AiMessage {
    data class User(
        val text: String,
    ) : AiMessage

    /** Claude の答え（流れてくる途中も足していく）。 */
    data class Assistant(
        val text: String,
    ) : AiMessage

    /** 道具を使った（「〜を探しました」など）。 */
    data class Action(
        val text: String,
    ) : AiMessage

    /** 画面に並べた曲（押すと再生）。 */
    data class Songs(
        val songs: List<Song>,
    ) : AiMessage

    data class Failure(
        val text: String,
    ) : AiMessage
}

data class AiChatState(
    val messages: List<AiMessage> = emptyList(),
    val running: Boolean = false,
)

/**
 * Claude との会話（Kite の AI と同じく、家の PC などで動く中継 tools/ai-bridge 経由で Claude Code を使う）。
 * 中継は状態を持たないので、毎回それまでのやりとりと、今の曲・歌詞を送る。道具の呼び出しは AiTools が手元で行う。
 * 会話はアプリが動いている間だけ持つ（保存しない）。YouTube の認証情報は中継へ送らない（別の HTTP クライアントを使う）。
 */
class AiChat(
    private val transport: AiTransport,
    private val tools: AiTools,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AiChatState())
    val state: StateFlow<AiChatState> = mutableState.asStateFlow()
    private var job: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun clear() {
        stop()
        mutableState.value = AiChatState()
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(running = false) }
    }

    /** 頼みを送る。答えは state に流れてくる。 */
    fun send(task: String) {
        if (task.isBlank() || state.value.running) return
        val history =
            state.value.messages.mapNotNull {
                when (it) {
                    is AiMessage.User -> "user" to it.text
                    is AiMessage.Assistant -> "assistant" to it.text
                    else -> null
                }
            }
        mutableState.update { it.copy(messages = it.messages + AiMessage.User(task), running = true) }
        job =
            scope.launch {
                try {
                    run(task, history)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.i(WaveApplication.LOG_TAG, "ai failed type=${error::class.simpleName}")
                    fail(UNREACHABLE)
                } finally {
                    mutableState.update { it.copy(running = false) }
                }
            }
    }

    private suspend fun run(
        task: String,
        history: List<Pair<String, String>>,
    ) {
        val context = tools.context()
        val body =
            buildJsonObject {
                context.song?.let { song ->
                    put(
                        "now_playing",
                        buildJsonObject {
                            put("title", song.title)
                            put("artists", song.artists.joinToString(", ") { it.name })
                            put("album", song.album?.title.orEmpty())
                            put("video_id", song.videoId)
                        },
                    )
                }
                context.lyrics?.let { put("lyrics", it) }
                put(
                    "messages",
                    buildJsonArray {
                        history.forEach { (role, content) ->
                            add(
                                buildJsonObject {
                                    put("role", role)
                                    put("content", content)
                                },
                            )
                        }
                    },
                )
                put("task", task)
            }.toString()
        var session: String? = null
        // 答えの区切り（message_stop の空行）ごとに、新しい吹き出しにする
        var appending = false
        val outcome =
            transport.stream(body) { line ->
                val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@stream true
                when {
                    "session" in event -> session = event.string("session")
                    "delta" in event -> {
                        val text = event.string("delta").orEmpty()
                        if (text.isBlank() && !appending) return@stream true
                        mutableState.update { state ->
                            val last = state.messages.lastOrNull()
                            if (appending && last is AiMessage.Assistant) {
                                state.copy(messages = state.messages.dropLast(1) + AiMessage.Assistant(last.text + text))
                            } else {
                                state.copy(messages = state.messages + AiMessage.Assistant(text.trimStart()))
                            }
                        }
                        appending = text != "\n\n"
                    }
                    "tool_call" in event -> {
                        appending = false
                        val call = event["tool_call"]?.jsonObject ?: return@stream true
                        val id = call.string("id") ?: return@stream true
                        val name = call.string("name").orEmpty()
                        val arguments = call["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                        val outcome = tools.call(name, arguments)
                        outcome.shown?.let { mutableState.update { it.copy(messages = it.messages + it.shownMessage(outcome)) } }
                        postResult(session, id, outcome)
                    }
                    "error" in event -> fail(event.string("error").orEmpty().take(300))
                }
                event["done"] == null
            }
        when (outcome) {
            AiTransport.Outcome.Done -> Unit
            AiTransport.Outcome.NotConfigured -> fail(NOT_CONFIGURED)
            AiTransport.Outcome.Unauthorized -> fail(UNAUTHORIZED)
            AiTransport.Outcome.Unreachable -> fail(UNREACHABLE)
        }
    }
    private fun AiChatState.shownMessage(outcome: AiTools.Outcome): AiMessage = outcome.songs?.let(AiMessage::Songs) ?: AiMessage.Action(outcome.shown.orEmpty())

    private suspend fun postResult(
        session: String?,
        id: String,
        outcome: AiTools.Outcome,
    ) {
        val body =
            buildJsonObject {
                put("session", session.orEmpty())
                put("id", id)
                if (outcome.error != null) put("error", outcome.error) else put("result", outcome.result)
            }.toString()
        transport.postResult(body)
    }

    private fun fail(text: String) {
        mutableState.update { it.copy(messages = it.messages + AiMessage.Failure(text)) }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** 失敗の種類（画面で端末の言語の文言にする）。 */
        const val NOT_CONFIGURED = "wave:ai-not-configured"
        const val UNREACHABLE = "wave:ai-unreachable"
        const val UNAUTHORIZED = "wave:ai-unauthorized"
    }
}
