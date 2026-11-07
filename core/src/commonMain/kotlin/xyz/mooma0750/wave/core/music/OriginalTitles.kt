package xyz.mooma0750.wave.core.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 曲名・アーティスト名・アルバム名を、YouTube が言語に合わせて書き換える前の表記（曲の言語のまま）にする（設定の「曲名を元の表記で」）。
 * YouTube Music は、日本語で頼むと洋楽をカタカナに（テイラー・スウィフト）、日本語以外で頼むと邦楽の一部をローマ字に（Kenshi Yonezu）して返す。
 * どの言語で頼んでも両方が元の表記になる頼み方は無い（2026-11 に hl・gl を変えて確かめた）ので、日本語と英語の2つの応答を、
 * 行・カード・見出しごとに ID（videoId・browseId）で突き合わせ、元の表記の方を選ぶ。
 *
 * 選び方（手がかりの強い順）:
 * - 英語の応答に日本の文字が残る（「夜の踊り子 - Yoru No Odoriko」）・アーティスト名の漢字・ひらがながローマ字になる → 日本の曲（日本語の方）
 * - カタカナが英字になる → 洋楽（英語の方）。カタカナ名の邦楽（サカナクション）もあるので、日本の曲の手がかりが無いときだけ
 * - 曲名の漢字がローマ字になるだけでは決めない（洋楽にも日本語の副題がある。「シェイク・イット・オフ～気にしてなんかいられないっ！！」）
 * 日本の曲と分かったアーティストは覚え（[Result.learned]）、手がかりの無い行にも使う。YouTube が作ったプレイリスト・棚の見出し・ジャンル名は画面の言語のまま。
 */
object OriginalTitles {
    /** 画面の言語（hl）に対して、もう1つ頼む言語。 */
    fun otherLanguage(hl: String): String = if (isJapanese(hl)) "en" else "ja"

    fun isJapanese(hl: String): Boolean = hl.substringBefore('-').equals("ja", ignoreCase = true)

    class Result(
        val json: JsonObject,
        /** 新しく分かったアーティスト（browseId → 日本の曲なら `ja`、洋楽なら `en`）。 */
        val learned: Map<String, String>,
    )

    /**
     * primary（画面の言語 primaryLanguage の応答）を、other（もう1つの言語の同じ頼みの応答）と突き合わせて元の表記にする。
     * known は前に覚えたアーティスト（browseId → `ja`・`en`）。
     */
    fun merge(
        primary: JsonObject,
        other: JsonObject,
        primaryLanguage: String,
        known: Map<String, String> = emptyMap(),
    ): Result {
        val primaryIsJa = isJapanese(primaryLanguage)
        // もう1つの言語の応答の、行・カードの題と行き先のある文字（ID ごと）。言語で並びや中身が少し変わるので、同じ位置ではなく ID で引く
        val otherTitles = HashMap<String, JsonArray>()
        groups(other).forEach { (name, obj) ->
            val key = groupKey(name, obj) ?: return@forEach
            titleRuns(name, obj)?.let { otherTitles.getOrPut(key) { it } }
        }
        val otherLinked = linked(other, nested = true)

        class Pairing(val name: String, val key: String, val artists: MutableSet<String>, val hints: Set<Hint>, val artistHints: Set<Hint>)

        val pageArtists = mutableSetOf<String>()
        val pairings =
            groups(primary).mapNotNull { (name, obj) ->
                val key = groupKey(name, obj)?.takeIf(::isMusicKey) ?: return@mapNotNull null
                val fields = mutableListOf<Triple<String, String, Boolean>>()
                val title = titleRuns(name, obj)
                val otherTitle = otherTitles[key]
                if (title != null && otherTitle != null) fields += Triple(text(title), text(otherTitle), isArtistKey(key))
                val artists = mutableSetOf<String>()
                linked(obj).forEach { (id, value) ->
                    if (isArtistKey(id)) artists += id.removePrefix("b:")
                    otherLinked[id]?.let { fields += Triple(value, it, isArtistKey(id)) }
                }
                if (isArtistKey(key)) artists += key.removePrefix("b:")
                if (name in HEADERS) pageArtists += artists

                fun hintOf(field: Triple<String, String, Boolean>): Hint? =
                    if (primaryIsJa) hint(field.first, field.second, field.third) else hint(field.second, field.first, field.third)
                Pairing(name, key, artists, fields.mapNotNull(::hintOf).toSet(), fields.filter { it.third }.mapNotNull(::hintOf).toSet())
            }
        val learned = known.toMutableMap()
        val newlyLearned = mutableMapOf<String, String>()
        for (p in pairings) {
            // アルバムの曲の行はアーティストを書かない（見出しのアーティストの曲）
            if (p.artists.isEmpty() && p.name == LIST_ITEM) p.artists += pageArtists
            if (Hint.StrongJa in p.hints) {
                p.artists.forEach {
                    learned[it] = JA
                    newlyLearned[it] = JA
                }
            }
        }
        // カタカナが英字になったアーティストは洋楽として覚える（キューの動画の行のように、題しか違わない行に使う）。日本の曲と分かっていれば覚えない
        for (p in pairings) {
            if (Hint.StrongJa in p.hints || Hint.WeakEn !in p.artistHints) continue
            p.artists.filter { learned[it] != JA }.forEach {
                learned[it] = EN
                newlyLearned[it] = EN
            }
        }
        val replace = HashSet<String>()
        for (p in pairings) {
            val origin =
                when {
                    Hint.StrongJa in p.hints || p.artists.any { learned[it] == JA } -> JA
                    Hint.WeakEn in p.artistHints || p.hints == setOf(Hint.WeakEn) || p.artists.any { learned[it] == EN } -> EN
                    else -> continue
                }
            if ((origin == JA) != primaryIsJa) replace += p.key
        }
        if (replace.isEmpty()) return Result(primary, newlyLearned)
        return Result(rewrite(primary, null, Replacement(replace, otherTitles, otherLinked)) as JsonObject, newlyLearned)
    }

    private class Replacement(
        val keys: Set<String>,
        val titles: Map<String, JsonArray>,
        val linked: Map<String, String>,
    )

    private enum class Hint { StrongJa, WeakJa, WeakEn }

    /** ja・en の2つの表記の違いから、元の言語の手がかり。 */
    private fun hint(
        ja: String,
        en: String,
        artist: Boolean,
    ): Hint? {
        if (ja.trim() == en.trim()) return null
        val j = Scripts.of(ja)
        val e = Scripts.of(en)
        return when {
            e.japanese || e.katakana -> Hint.StrongJa
            j.japanese && e.latin -> if (artist) Hint.StrongJa else Hint.WeakJa
            j.katakana && e.latin -> Hint.WeakEn
            else -> null
        }
    }

    private class Scripts(
        /** 漢字・ひらがな。 */
        val japanese: Boolean,
        val katakana: Boolean,
        val latin: Boolean,
    ) {
        companion object {
            fun of(s: String): Scripts {
                var japanese = false
                var katakana = false
                var latin = false
                for (c in s) {
                    when (c.code) {
                        in 0x3041..0x309F, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, in 0xD840..0xD87F, 0x3005, 0x3006 -> japanese = true
                        in 0x30A0..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9F -> katakana = true
                        else -> if (c.isLetter() && (c.code < 0x2000 || c.code in 0xFF21..0xFF5A)) latin = true
                    }
                }
                return Scripts(japanese, katakana, latin)
            }
        }
    }

    private const val JA = "ja"
    private const val EN = "en"
    private const val LIST_ITEM = "musicResponsiveListItemRenderer"
    private val ITEMS = setOf(LIST_ITEM, "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicCardShelfRenderer", "musicMultiRowListItemRenderer")

    /** アーティストのページの見出し（アーティスト名）。 */
    private val ARTIST_HEADERS = setOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")

    /** アルバム・プレイリストのページの見出し。アーティストへのつながりがあるもの（アルバム）だけを扱う。 */
    private val ALBUM_HEADERS = setOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer")
    private val HEADERS = ARTIST_HEADERS + ALBUM_HEADERS
    private val GROUPS = ITEMS + HEADERS

    private fun groups(root: JsonElement): List<Pair<String, JsonObject>> {
        val out = mutableListOf<Pair<String, JsonObject>>()

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject ->
                    for ((k, v) in e) {
                        if (k in GROUPS && v is JsonObject) out += k to v
                        walk(v)
                    }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** 文字の続き（run）・行の行き先の ID（`v:videoId`・`b:browseId`）。 */
    private fun endpointId(o: JsonElement?): String? {
        val ne = o.obj("navigationEndpoint")
        ne.obj("watchEndpoint").obj("videoId").str()?.takeIf { it.isNotEmpty() }?.let { return "v:$it" }
        ne.obj("browseEndpoint").obj("browseId").str()?.takeIf { it.isNotEmpty() }?.let { return "b:$it" }
        return null
    }

    private fun isArtistKey(key: String) = key.startsWith("b:UC")

    /** 曲・動画・アルバム・アーティスト（YouTube のプレイリスト・気分の棚などは画面の言語のまま）。 */
    private fun isMusicKey(key: String) = key.startsWith("v:") || key.startsWith("b:MPRE") || isArtistKey(key) || key == ALBUM_PAGE

    private const val ALBUM_PAGE = "h:album"

    private fun titleRuns(
        name: String,
        g: JsonObject,
    ): JsonArray? =
        if (name == LIST_ITEM) {
            ((g["flexColumns"] as? JsonArray)?.firstOrNull().obj("musicResponsiveListItemFlexColumnRenderer").obj("text").obj("runs")) as? JsonArray
        } else {
            g["title"].obj("runs") as? JsonArray
        }

    private fun text(runs: JsonArray): String = runs.joinToString("") { it.obj("text").str().orEmpty() }

    private fun groupKey(
        name: String,
        g: JsonObject,
    ): String? {
        if (name in ARTIST_HEADERS) return firstString(g, "channelId")?.let { "b:$it" }
        if (name in ALBUM_HEADERS) return ALBUM_PAGE.takeIf { runsIn(g).any { r -> pageType(r) == "MUSIC_PAGE_TYPE_ARTIST" } }
        (g["playlistItemData"].obj("videoId").str() ?: g["videoId"].str())?.takeIf { it.isNotEmpty() }?.let { return "v:$it" }
        endpointId(g)?.let { return it }
        return titleRuns(name, g)?.firstOrNull()?.let(::endpointId)
    }

    private fun pageType(run: JsonElement): String? =
        run.obj("navigationEndpoint").obj("browseEndpoint").obj("browseEndpointContextSupportedConfigs").obj("browseEndpointContextMusicConfig").obj("pageType").str()

    private fun firstString(
        e: JsonElement,
        key: String,
    ): String? =
        when (e) {
            is JsonObject -> e[key].str() ?: e.values.firstNotNullOfOrNull { firstString(it, key) }
            is JsonArray -> e.firstNotNullOfOrNull { firstString(it, key) }
            else -> null
        }

    /** 群の中の run（nested でなければ入れ子の群の中は除く）。 */
    private fun runsIn(
        g: JsonObject,
        nested: Boolean = false,
    ): List<JsonElement> {
        val out = mutableListOf<JsonElement>()

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject ->
                    for ((k, v) in e) {
                        if (k in GROUPS && !nested) continue
                        if (k == "runs" && v is JsonArray) out.addAll(v) else walk(v)
                    }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(g)
        return out
    }

    /** 群の中（nested なら入れ子の群の中も）の、行き先のある run の ID → 文字。 */
    private fun linked(
        g: JsonObject,
        nested: Boolean = false,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (run in runsIn(g, nested)) {
            val id = endpointId(run) ?: continue
            if (id !in out) out[id] = run.obj("text").str().orEmpty()
        }
        return out
    }

    /** replace の群を、もう1つの言語の題・行き先のある文字に置き換える。scope は今いる置き換える群の、もう1つの言語の文字。 */
    private fun rewrite(
        e: JsonElement,
        scope: Map<String, String>?,
        replace: Replacement,
    ): JsonElement =
        when (e) {
            is JsonObject -> {
                val id = if (scope != null && e["text"] is JsonPrimitive) endpointId(e) else null
                val replacement = id?.takeIf(::isMusicKey)?.let { scope?.get(it) }
                JsonObject(
                    e.mapValues { (k, v) ->
                        when {
                            k == "text" && replacement != null -> JsonPrimitive(replacement)
                            k in GROUPS && v is JsonObject -> group(k, v, replace)
                            else -> rewrite(v, scope, replace)
                        }
                    },
                )
            }
            is JsonArray -> JsonArray(e.map { rewrite(it, scope, replace) })
            else -> e
        }

    private fun group(
        name: String,
        g: JsonObject,
        replace: Replacement,
    ): JsonObject {
        val key = groupKey(name, g)?.takeIf { it in replace.keys } ?: return rewrite(g, null, replace) as JsonObject
        val otherTitle = replace.titles[key]
        val titled = if (otherTitle != null && titleRuns(name, g) != null) withTitle(name, g, otherTitle) else g
        return rewrite(titled, replace.linked, replace) as JsonObject
    }

    private fun withTitle(
        name: String,
        g: JsonObject,
        runs: JsonArray,
    ): JsonObject {
        fun JsonObject.with(
            key: String,
            value: JsonElement,
        ) = JsonObject(this + (key to value))
        if (name != LIST_ITEM) return g.with("title", (g["title"] as JsonObject).with("runs", runs))
        val columns = g["flexColumns"] as JsonArray
        val first = columns[0] as JsonObject
        val renderer = first["musicResponsiveListItemFlexColumnRenderer"] as JsonObject
        val text = renderer["text"] as JsonObject
        val newFirst = first.with("musicResponsiveListItemFlexColumnRenderer", renderer.with("text", text.with("runs", runs)))
        return g.with("flexColumns", JsonArray(listOf(newFirst) + columns.drop(1)))
    }
}
