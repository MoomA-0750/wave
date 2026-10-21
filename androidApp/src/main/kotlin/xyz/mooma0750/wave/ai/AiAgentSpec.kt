package xyz.mooma0750.wave.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.mooma0750.wave.core.music.Song

/**
 * 中継（ai-bridge の /v1/run）へ送る頼み。システムプロンプトと道具の定義は ai/agent.json（Mac/iOS・Windows と同じファイル）。
 * 中継はアプリのことを知らないので、今の曲・歌詞・それまでのやりとりは頼みの文に入れる。
 */
class AiAgentSpec(
    private val system: String,
    private val tools: JsonArray,
    private val maxLyricsChars: Int,
) {
    /** /v1/run の本文。history は (role, content) で、role は "user" か "assistant"。 */
    fun body(
        task: String,
        history: List<Pair<String, String>>,
        context: AiTools.Context,
    ): String =
        buildJsonObject {
            put("system", system)
            put("prompt", prompt(task, history, context.song, context.lyrics))
            put("tools", tools)
        }.toString()

    fun prompt(
        task: String,
        history: List<Pair<String, String>>,
        song: Song?,
        lyrics: String?,
    ): String =
        buildString {
            if (song != null) {
                append("<now_playing>\n")
                append("題名: ").append(song.title).append('\n')
                append("アーティスト: ").append(song.artistsText).append('\n')
                append("アルバム: ").append(song.album?.title.orEmpty()).append('\n')
                append("video_id: ").append(song.videoId).append('\n')
                append("</now_playing>\n\n")
            }
            if (!lyrics.isNullOrEmpty()) append("<lyrics>\n").append(lyrics.take(maxLyricsChars)).append("\n</lyrics>\n\n")
            if (history.isNotEmpty()) {
                append("<conversation>\n")
                history.forEach { (role, content) -> append(if (role == "user") "利用者: " else "アシスタント: ").append(content).append('\n') }
                append("</conversation>\n\n")
            }
            append(task)
        }

    companion object {
        fun parse(text: String): AiAgentSpec {
            val root = Json.parseToJsonElement(text) as JsonObject
            return AiAgentSpec(
                system = root.getValue("system").jsonPrimitive.content,
                tools = root.getValue("tools").jsonArray,
                maxLyricsChars = root["max_lyrics_chars"]?.jsonPrimitive?.int ?: 20_000,
            )
        }

        /** アプリに入れた ai/agent.json を読む（androidApp の build.gradle.kts で ai/ を assets にしている）。 */
        fun load(context: android.content.Context): AiAgentSpec = parse(context.assets.open("agent.json").bufferedReader().use { it.readText() })
    }
}
