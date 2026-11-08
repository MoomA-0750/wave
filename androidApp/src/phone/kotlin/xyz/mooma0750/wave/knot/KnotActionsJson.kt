package xyz.mooma0750.wave.knot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.mooma0750.wave.core.music.PlayLink
import xyz.mooma0750.wave.widget.WidgetItem
import xyz.mooma0750.wave.widget.WidgetItemKind

/** run に渡された引数（`choices` のものは `{"id", "title"}` の辞書）。 */
data class KnotParams(
    val item: JsonObject? = null,
    val shuffle: Boolean = false,
    val link: String? = null,
)

/**
 * Knot のアクションの口の答えの JSON（Android の部品を使わない純粋なもの。`KnotActionsJsonTest` で形を確かめる）。
 * 並びは iPhone・Mac のショートカットのアクションと同じ。
 */
object KnotActionsJson {
    const val KNOT_PACKAGE = "xyz.mooma0750.knot"
    private const val SHELL_PACKAGE = "com.android.shell"

    const val PLAY = "ext.wave.play"
    const val PLAY_LINK = "ext.wave.play_link"
    const val PLAY_PAUSE = "ext.wave.play_pause"
    const val NEXT = "ext.wave.next"
    const val PREVIOUS = "ext.wave.previous"
    const val LIKE = "ext.wave.like"
    const val NOW_PLAYING = "ext.wave.now_playing"

    /** 出力が無いとき。 */
    const val NULL = "null"

    private val json = Json { ignoreUnknownKeys = true }

    /** 答えてよい呼び手か。Knot だけ。開発版に限り、adb の `content call`（shell）でも確かめられるようにする。 */
    fun allowed(
        caller: String?,
        debug: Boolean,
    ): Boolean = caller == KNOT_PACKAGE || (debug && caller == SHELL_PACKAGE)

    /** `actions` の答え（アクションの定義の配列）。 */
    fun definitions(): String =
        buildJsonArray {
            action(PLAY, "選んだものを再生", "Play Item", "プレイリスト・アルバム・曲・ライブを選んで再生する", "Play a playlist, album, song or live stream", "music", output = "text") {
                addJsonObject {
                    put("name", "item")
                    title("再生するもの", "Item")
                    put("type", "dict")
                    put("required", true)
                    put("choices", true)
                }
                shuffleParam()
            }
            action(PLAY_LINK, "リンクで再生", "Play Link", "YouTube・YouTube Music のリンクか ID を再生する（曲・ライブ・プレイリスト・アルバム）", "Play a YouTube or YouTube Music link or ID", "link") {
                addJsonObject {
                    put("name", "link")
                    title("リンク", "Link")
                    put("type", "text")
                    put("required", true)
                }
                shuffleParam()
            }
            action(PLAY_PAUSE, "再生・一時停止", "Play/Pause", "今の曲を再生・一時停止する（ほかの端末を映している間はその端末で）", "Play or pause the current song", "headphones") {}
            action(NEXT, "次の曲", "Next Song", "次の曲へ進む", "Skip to the next song", "music") {}
            action(PREVIOUS, "前の曲", "Previous Song", "前の曲へ戻る", "Go back to the previous song", "music") {}
            action(LIKE, "高く評価", "Like", "今の曲を高く評価する", "Like the current song", "heart") {}
            action(NOW_PLAYING, "今の曲を取得", "Get Now Playing", "今の曲を「曲名 — アーティスト」の文字で返す", "Return the current song as \"Title — Artist\"", "note", output = "text") {}
        }.toString()

    private fun kotlinx.serialization.json.JsonArrayBuilder.action(
        type: String,
        ja: String,
        en: String,
        descriptionJa: String,
        descriptionEn: String,
        icon: String,
        output: String = "null",
        params: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ) = addJsonObject {
        put("type", type)
        title(ja, en)
        putJsonObject("description") {
            put("ja", descriptionJa)
            put("en", descriptionEn)
        }
        put("category", "media")
        put("icon", icon)
        putJsonArray("params", params)
        put("output", output)
        putJsonArray("capabilities") { add(JsonPrimitive("app.wave")) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.title(
        ja: String,
        en: String,
    ) = putJsonObject("title") {
        put("ja", ja)
        put("en", en)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.shuffleParam() =
        addJsonObject {
            put("name", "shuffle")
            title("シャッフル", "Shuffle")
            put("type", "boolean")
            put("default", false)
        }

    /** `choices` の答え（見出しと候補）。候補の id は選んだものを丸ごと入れる（候補から外れても鳴らせる）。 */
    fun choices(
        sections: List<Pair<String, List<WidgetItem>>>,
        liveBadge: String,
    ): String =
        buildJsonArray {
            sections.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
                addJsonObject {
                    put("section", title)
                    putJsonArray("items") {
                        items.forEach { item ->
                            addJsonObject {
                                put("id", idOf(item))
                                put("title", item.title)
                                val subtitle = listOfNotNull(liveBadge.takeIf { item.kind == WidgetItemKind.Live }, item.subtitle?.takeIf { it.isNotEmpty() }).joinToString(" • ")
                                if (subtitle.isNotEmpty()) put("subtitle", subtitle)
                            }
                        }
                    }
                }
            }
        }.toString()

    /** 候補の id（WidgetItem を丸ごと JSON にしたもの）。 */
    fun idOf(item: WidgetItem): String =
        buildJsonObject {
            put("kind", item.kind.name)
            put("id", item.id)
            put("title", item.title)
            item.subtitle?.let { put("subtitle", it) }
            item.thumbnailUrl?.let { put("thumbnail", it) }
        }.toString()

    /**
     * 保存された `{"id", "title"}` から鳴らすものを戻す。id が wave の作ったものでなければ、リンクか ID として読む
     * （Claude Code に作らせたショートカットなどで、id に URL を入れたとき）。
     */
    fun itemOf(choice: JsonObject): WidgetItem? {
        val id = choice["id"]?.stringOrNull() ?: return null
        val title = choice["title"]?.stringOrNull().orEmpty()
        runCatching { json.parseToJsonElement(id) as? JsonObject }.getOrNull()?.let { saved ->
            val kind = saved["kind"]?.stringOrNull()?.let { name -> WidgetItemKind.entries.firstOrNull { it.name == name } } ?: return null
            val itemId = saved["id"]?.stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return WidgetItem(kind, itemId, saved["title"]?.stringOrNull() ?: title, saved["subtitle"]?.stringOrNull(), saved["thumbnail"]?.stringOrNull())
        }
        return when (val link = PlayLink.parse(id)) {
            is PlayLink.Video -> WidgetItem(WidgetItemKind.Song, link.videoId, title, null, null)
            is PlayLink.Playlist -> WidgetItem(WidgetItemKind.Playlist, link.playlistId, title, null, null)
            is PlayLink.Album -> WidgetItem(WidgetItemKind.Album, link.browseId, title, null, null)
            null -> null
        }
    }

    /** run の extras の `params`（JSON の辞書）を読む。 */
    fun params(text: String?): KnotParams {
        val root = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return KnotParams()
        return KnotParams(
            item = root["item"] as? JsonObject,
            shuffle = (root["shuffle"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: false,
            link = root["link"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** 文字の出力（JSON の文字列）。 */
    fun text(value: String): String = JsonPrimitive(value).toString()

    /** 「曲名 — アーティスト」（アーティストが無ければ曲名だけ）。 */
    fun nowPlayingText(
        title: String,
        artist: String,
    ): String = if (artist.isEmpty()) title else "$title — $artist"

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { this is JsonNull }?.contentOrNull
}
