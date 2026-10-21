package xyz.mooma0750.wave.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import xyz.mooma0750.wave.core.ai.AiConversation
import xyz.mooma0750.wave.core.ai.AiHistory
import xyz.mooma0750.wave.core.ai.AiSavedMessage
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

    /**
     * Claude の答え（流れてくる途中も足していく）。videoId は時刻付きの歌詞を添えて聞いたときの曲で、
     * 答えの中の [mm:ss] はこの曲が鳴っているときだけ押せる（押すとその位置へ飛ぶ）。
     */
    data class Assistant(
        val text: String,
        val videoId: String? = null,
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
    /** 履歴に残すときの id（新しい会話ごとに変わる。履歴から開けばその会話の id）。 */
    val conversationId: String = java.util.UUID.randomUUID().toString(),
    val createdAtMs: Long = System.currentTimeMillis(),
)

/**
 * Claude との会話（家の PC などで動く汎用の中継 ai-bridge 経由で Claude Code を使う）。
 * 中継は状態もアプリのことも持たないので、毎回システムプロンプト・道具の定義（AiAgentSpec）と、
 * それまでのやりとり・今の曲・歌詞を入れた頼みの文を送る。道具の呼び出しは AiTools が手元で行う。
 * 会話は端末の中の履歴（AiHistoryStore）に残し、「新しい会話」で次の会話へ移る。履歴から開いた会話はそのまま続けられる
 * （中継へは毎回それまでのやりとりを送る）。YouTube の認証情報は中継へ送らない（別の HTTP クライアントを使う）。
 */
class AiChat(
    private val transport: AiTransport,
    private val tools: AiTools,
    private val spec: AiAgentSpec,
    private val scope: CoroutineScope,
    private val store: AiHistoryStore,
) {
    private val mutableState = MutableStateFlow(AiChatState())
    val state: StateFlow<AiChatState> = mutableState.asStateFlow()
    private var job: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** これまでの会話（新しく話した順）。 */
    val history: StateFlow<List<AiConversation>> = store.conversations

    /** 鳴っている曲の id（答えの中の時刻を押せるか）。 */
    val playingVideoId: StateFlow<String?> = tools.playingVideoId().stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** 新しい会話にする（今の会話は履歴に残っている）。 */
    fun clear() {
        stop()
        archive()
        mutableState.value = AiChatState()
    }

    /** 履歴の会話を開いて続ける。 */
    fun open(id: String) {
        val conversation = history.value.firstOrNull { it.id == id } ?: return
        stop()
        archive()
        val messages = conversation.messages.mapNotNull(::messageOf)
        messages.filterIsInstance<AiMessage.Songs>().forEach { tools.remember(it.songs) }
        mutableState.value = AiChatState(messages, conversationId = conversation.id, createdAtMs = conversation.createdAtMs)
    }

    /** 履歴から消す。開いている会話なら、新しい会話にする。 */
    fun delete(id: String) {
        if (state.value.conversationId == id) {
            stop()
            mutableState.value = AiChatState()
        }
        store.remove(id)
    }

    /** ログアウトのとき: 今の会話も履歴も消す。 */
    fun wipe() {
        stop()
        mutableState.value = AiChatState()
        store.clear()
    }

    /** 答えの中の時刻を押した: 話している曲がまだ鳴っていれば、その位置へ飛ぶ。 */
    fun seek(
        videoId: String,
        positionMs: Long,
    ) {
        scope.launch {
            val ok = runCatching { tools.seek(videoId, positionMs) }.getOrDefault(false)
            Log.i(WaveApplication.LOG_TAG, "ai seek ok=$ok")
        }
    }

    /** 今の会話を履歴に残す（自分の発言が無ければ残さない）。 */
    private fun archive(state: AiChatState = this.state.value) {
        val saved = state.messages.map(::savedOf)
        if (saved.none { it.kind == AiSavedMessage.USER }) return
        store.save(AiConversation(state.conversationId, AiHistory.title(saved), state.createdAtMs, System.currentTimeMillis(), saved))
    }

    private fun savedOf(message: AiMessage): AiSavedMessage =
        when (message) {
            is AiMessage.User -> AiSavedMessage(AiSavedMessage.USER, message.text)
            is AiMessage.Assistant -> AiSavedMessage(AiSavedMessage.ASSISTANT, message.text, videoId = message.videoId)
            is AiMessage.Action -> AiSavedMessage(AiSavedMessage.ACTION, message.text)
            is AiMessage.Songs -> AiSavedMessage(AiSavedMessage.SONGS, songs = message.songs)
            is AiMessage.Failure -> AiSavedMessage(AiSavedMessage.FAILURE, message.text)
        }

    private fun messageOf(saved: AiSavedMessage): AiMessage? =
        when (saved.kind) {
            AiSavedMessage.USER -> AiMessage.User(saved.text)
            AiSavedMessage.ASSISTANT -> AiMessage.Assistant(saved.text, saved.videoId)
            AiSavedMessage.ACTION -> AiMessage.Action(saved.text)
            AiSavedMessage.SONGS -> saved.songs.takeIf { it.isNotEmpty() }?.let(AiMessage::Songs)
            AiSavedMessage.FAILURE -> AiMessage.Failure(saved.text)
            else -> null
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
        archive()
        val conversationId = state.value.conversationId
        job =
            scope.launch {
                try {
                    run(task, history)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.i(WaveApplication.LOG_TAG, "ai failed type=${error::class.simpleName}")
                    fail(UNREACHABLE)
                } finally {
                    // 止めたあとに別の会話へ移っていれば、そちらには触れない
                    val current = state.value
                    if (current.conversationId == conversationId) {
                        mutableState.update { it.copy(running = false) }
                        archive(current)
                    }
                }
            }
    }

    private suspend fun run(
        task: String,
        history: List<Pair<String, String>>,
    ) {
        val context = tools.context()
        val body = spec.body(task, history, context)
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
                                state.copy(messages = state.messages.dropLast(1) + last.copy(text = last.text + text))
                            } else {
                                state.copy(messages = state.messages + AiMessage.Assistant(text.trimStart(), context.syncedVideoId))
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
