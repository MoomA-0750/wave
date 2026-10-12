package xyz.mooma0750.wave.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.music.Song

/**
 * 保存した会話の1つ分の発言。kind は user（自分）・assistant（Claude）・action（道具を使った行）・songs（並べた曲）・failure（失敗）。
 * videoId は assistant だけ: 時刻付きの歌詞を添えて聞いたときの曲（答えの中の時刻は、この曲が鳴っているときだけ押せる）。
 */
@Serializable
data class AiSavedMessage(
    val kind: String,
    val text: String = "",
    @SerialName("video_id") val videoId: String? = null,
    val songs: List<Song> = emptyList(),
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val ACTION = "action"
        const val SONGS = "songs"
        const val FAILURE = "failure"
    }
}

/** 保存した会話。題名は最初の自分の発言。 */
@Serializable
data class AiConversation(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAtMs: Long,
    @SerialName("updated_at") val updatedAtMs: Long,
    val messages: List<AiSavedMessage>,
)

@Serializable
private data class AiHistoryFile(
    val version: Int = AiHistory.VERSION,
    val conversations: List<AiConversation> = emptyList(),
)

/**
 * Claude との会話の履歴（端末の中だけに置き、同期しない。中継は会話を持たない）。新しく話した順に、最大 MAX 件。
 * Windows の Wave.Core.Ai.AiHistory・Swift の AiHistory と同じ決まり。ログインの情報は入れない（発言と曲の情報だけ）。
 */
object AiHistory {
    const val VERSION = 1
    const val MAX = 50
    const val TITLE_MAX = 80

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

    /** 題名（最初の自分の発言を1行にして縮める）。自分の発言が無ければ空。 */
    fun title(messages: List<AiSavedMessage>): String =
        messages
            .firstOrNull { it.kind == AiSavedMessage.USER }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.let { if (it.length > TITLE_MAX) it.take(TITLE_MAX - 1) + "…" else it }
            .orEmpty()

    /** 会話を入れる（同じ id があれば置き換える）。新しく話した順に並べ、MAX 件を超えた古いものは落とす。 */
    fun upsert(
        list: List<AiConversation>,
        conversation: AiConversation,
    ): List<AiConversation> = (list.filter { it.id != conversation.id } + conversation).sortedByDescending { it.updatedAtMs }.take(MAX)

    fun remove(
        list: List<AiConversation>,
        id: String,
    ): List<AiConversation> = list.filter { it.id != id }

    fun encode(list: List<AiConversation>): String = json.encodeToString(AiHistoryFile.serializer(), AiHistoryFile(VERSION, list))

    /** 読めない・形の違うファイルは空にする。 */
    fun decode(text: String): List<AiConversation> =
        runCatching { json.decodeFromString(AiHistoryFile.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.version == VERSION }
            ?.conversations
            ?.sortedByDescending { it.updatedAtMs }
            ?.take(MAX)
            .orEmpty()
}
