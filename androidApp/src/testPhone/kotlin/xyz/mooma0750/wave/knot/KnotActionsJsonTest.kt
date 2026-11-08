package xyz.mooma0750.wave.knot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.mooma0750.wave.core.music.PlayLink
import xyz.mooma0750.wave.widget.WidgetItem
import xyz.mooma0750.wave.widget.WidgetItemKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnotActionsJsonTest {
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun definitionsFollowTheKnotSchema() {
        val defs = Json.parseToJsonElement(KnotActionsJson.definitions()).jsonArray.map { it.jsonObject }
        // iPhone・Mac のショートカットと同じ並び
        assertEquals(
            listOf("ext.wave.play", "ext.wave.play_link", "ext.wave.play_pause", "ext.wave.next", "ext.wave.previous", "ext.wave.like", "ext.wave.now_playing"),
            defs.map { it.string("type") },
        )
        // Knot の schema/icons.json の glyphs にある絵
        val glyphs = setOf("music", "link", "headphones", "heart", "note")
        val paramTypes = setOf("text", "number", "boolean", "list", "dict", "any", "enum")
        defs.forEach { def ->
            assertTrue(Regex("ext\\.wave\\.[a-z0-9_]+").matches(def.string("type")))
            assertTrue(def.getValue("title").jsonObject.string("ja").isNotEmpty())
            assertTrue(def.getValue("title").jsonObject.string("en").isNotEmpty())
            assertTrue(def.getValue("description").jsonObject.string("ja").isNotEmpty())
            assertEquals("media", def.string("category"))
            assertTrue(def.string("icon") in glyphs)
            assertEquals(listOf("app.wave"), def.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content })
            assertFalse("risk" in def)
            def.getValue("params").jsonArray.map { it.jsonObject }.forEach { param ->
                assertTrue(param.string("type") in paramTypes)
                // 候補で選ばせるものは dict
                if (param["choices"]?.jsonPrimitive?.boolean == true) assertEquals("dict", param.string("type"))
            }
        }
        val play = defs.first { it.string("type") == "ext.wave.play" }
        assertEquals(listOf("item" to "dict", "shuffle" to "boolean"), play.getValue("params").jsonArray.map { it.jsonObject.string("name") to it.jsonObject.string("type") })
        assertEquals("text", play.string("output"))
        val link = defs.first { it.string("type") == "ext.wave.play_link" }
        assertEquals(listOf("link" to "text", "shuffle" to "boolean"), link.getValue("params").jsonArray.map { it.jsonObject.string("name") to it.jsonObject.string("type") })
        assertEquals("text", defs.first { it.string("type") == "ext.wave.now_playing" }.string("output"))
        listOf("ext.wave.play_pause", "ext.wave.next", "ext.wave.previous", "ext.wave.like", "ext.wave.now_playing").forEach { type ->
            assertTrue(defs.first { it.string("type") == type }.getValue("params").jsonArray.isEmpty())
        }
    }

    @Test
    fun choicesHaveSectionsAndRoundTripTheWholeItem() {
        val playlist = WidgetItem(WidgetItemKind.Playlist, "PL123", "朝の曲", "20 曲", "https://example.com/a.jpg")
        val live = WidgetItem(WidgetItemKind.Live, "jfKfPfyJRdk", "lofi hip hop radio", "Lofi Girl", null)
        val sections =
            Json
                .parseToJsonElement(KnotActionsJson.choices(listOf("プレイリスト" to listOf(playlist), "アルバム" to emptyList(), "ライブ" to listOf(live)), "LIVE"))
                .jsonArray
                .map { it.jsonObject }
        // 空の見出しは出さない
        assertEquals(listOf("プレイリスト", "ライブ"), sections.map { it.string("section") })
        val first = sections[0].getValue("items").jsonArray[0].jsonObject
        assertEquals("朝の曲", first.string("title"))
        assertEquals("20 曲", first.string("subtitle"))
        assertEquals("LIVE • Lofi Girl", sections[1].getValue("items").jsonArray[0].jsonObject.string("subtitle"))
        // Knot が保存して run で返す {"id", "title"} から、選んだものが丸ごと戻る
        val saved = buildJsonObject {
            put("id", first.string("id"))
            put("title", "朝の曲")
        }
        assertEquals(playlist, KnotActionsJson.itemOf(saved))
    }

    @Test
    fun itemOfReadsLinksAndRejectsUnknownIds() {
        fun choice(id: String) =
            buildJsonObject {
                put("id", id)
                put("title", "題")
            }
        // リンクや ID は「リンクで再生」と同じく読む（動画は曲かライブかを引いてから鳴らす）
        assertNull(KnotActionsJson.itemOf(choice("https://youtu.be/4t4AQ15yj1M")))
        assertEquals(PlayLink.Video("4t4AQ15yj1M"), KnotActionsJson.linkOf(choice("https://youtu.be/4t4AQ15yj1M")))
        assertEquals(PlayLink.Album("MPREb_4pL8gzRtw1p"), KnotActionsJson.linkOf(choice("MPREb_4pL8gzRtw1p")))
        assertNull(KnotActionsJson.itemOf(choice("pl:morning")))
        assertNull(KnotActionsJson.linkOf(choice("pl:morning")))
        assertNull(KnotActionsJson.itemOf(choice("""{"kind":"Nope","id":"x"}""")))
        assertNull(KnotActionsJson.linkOf(choice("""{"kind":"Nope","id":"x"}""")))
        assertNull(KnotActionsJson.itemOf(JsonObject(mapOf("title" to JsonPrimitive("題")))))
    }

    @Test
    fun paramsAndOutputs() {
        val params = KnotActionsJson.params("""{"item":{"id":"x","title":"t"},"shuffle":true,"link":"  https://youtu.be/jfKfPfyJRdk "}""")
        assertEquals("x", params.item?.string("id"))
        assertTrue(params.shuffle)
        assertEquals("https://youtu.be/jfKfPfyJRdk", params.link)
        // 省いた・壊れた引数は既定に
        assertEquals(KnotParams(), KnotActionsJson.params(null))
        assertEquals(KnotParams(), KnotActionsJson.params("not json"))
        assertTrue(KnotActionsJson.params("""{"shuffle":"true"}""").shuffle)
        // 出力は JSON の値
        assertEquals("\"朝の歌 — 誰か\"", KnotActionsJson.text(KnotActionsJson.nowPlayingText("朝の歌", "誰か")))
        assertEquals("朝の歌", KnotActionsJson.nowPlayingText("朝の歌", ""))
        assertEquals(JsonPrimitive("a\"b"), Json.parseToJsonElement(KnotActionsJson.text("a\"b")))
        assertTrue(Json.parseToJsonElement(KnotActionsJson.NULL) !is JsonArray)
    }

    @Test
    fun onlyKnotMayCall() {
        assertTrue(KnotActionsJson.allowed("xyz.mooma0750.knot", debug = false))
        assertFalse(KnotActionsJson.allowed("com.example.other", debug = false))
        assertFalse(KnotActionsJson.allowed(null, debug = false))
        // adb の content call は開発版でだけ通す
        assertFalse(KnotActionsJson.allowed("com.android.shell", debug = false))
        assertTrue(KnotActionsJson.allowed("com.android.shell", debug = true))
        assertFalse(KnotActionsJson.allowed("com.example.other", debug = true))
    }
}
