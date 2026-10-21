package xyz.mooma0750.wave.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiAgentSpecTest {
    private val spec = AiAgentSpec.parse(File("../ai/agent.json").readText())

    @Test
    fun theBodyCarriesTheSystemPromptAndOnlyWavesTools() {
        val body = Json.parseToJsonElement(spec.body("似た曲を探して", emptyList(), AiTools.Context(null, null))).jsonObject
        assertTrue(body.getValue("system").jsonPrimitive.content.startsWith("あなたは音楽プレイヤー wave"))
        val names = body.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(
            listOf("now_playing", "get_queue", "search_songs", "liked_songs", "show_songs", "play_songs", "add_to_queue", "list_playlists", "create_playlist", "add_to_playlist"),
            names,
        )
        assertEquals("似た曲を探して", body.getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun thePromptPutsLyricsAndTheConversationBeforeTheTask() {
        val prompt = spec.prompt("続けて", listOf("user" to "意味は？", "assistant" to "別れの歌です"), null, "[00:12] 行")
        assertEquals("<lyrics>\n[00:12] 行\n</lyrics>\n\n<conversation>\n利用者: 意味は？\nアシスタント: 別れの歌です\n</conversation>\n\n続けて", prompt)
    }

    @Test
    fun longLyricsAreCut() {
        val prompt = spec.prompt("x", emptyList(), null, "あ".repeat(30_000))
        assertEquals(20_000, prompt.count { it == 'あ' })
    }
}
