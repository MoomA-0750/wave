package xyz.mooma0750.wave.core.music

/**
 * ポッドキャストの RSS から、要る要素だけを拾う（commonMain に JVM の XML を持ち込まない）。
 * 木は作らず、`indexOf` で目当てのタグへ飛ぶ。debug の端末（Pixel 3）では1字ずつ見るループが極端に遅く、
 * 数 MB・数百回分のフィードで木を作ると十数秒かかった。`indexOf` は端末の速い処理で動く。
 * 要素の名前は接頭辞ごと（`itunes:image` など）で扱う。名前空間は解決しない。壊れた入力でも例外にしない。
 */
class XmlRange(
    val source: String,
    /** 中身の始まり（開きタグの `>` の次）。 */
    val start: Int,
    /** 中身の終わり（閉じタグの `<`）。 */
    val end: Int,
    /** 開きタグの属性。 */
    val attributes: Map<String, String> = emptyMap(),
) {
    /** 範囲の中の最初の [name] の要素。[skip] の要素の中にあるものは飛ばす（チャンネルの題と `<image>` の題を分ける）。 */
    fun child(
        name: String,
        skip: String? = null,
    ): XmlRange? {
        var from = start
        while (true) {
            val found = FeedXml.element(source, name, from, end) ?: return null
            val skipped = skip?.let { FeedXml.element(source, it, from, end) }
            if (skipped != null && skipped.start <= found.start && found.end <= skipped.end) {
                from = skipped.end
                continue
            }
            return found
        }
    }

    /**
     * 中身だけを切り出した範囲。中を何度も探すときに使う（`indexOf` は範囲の外まで探すので、
     * 大きな文書のまま探すと、無いタグを探すたびに文書の終わりまで見てしまう）。
     */
    fun detached(): XmlRange = XmlRange(source.substring(start, end), 0, end - start, attributes)

    /** 範囲の中の [name] の要素を順に。 */
    fun children(name: String): List<XmlRange> {
        val out = mutableListOf<XmlRange>()
        var from = start
        while (true) {
            val found = FeedXml.element(source, name, from, end) ?: return out
            out += found
            from = found.end
        }
    }

    /** 中の文字（CDATA はそのまま、ほかは実体参照をほどく）。前後の空白は落とす。中に子要素があればタグごと残る。 */
    val text: String
        get() {
            val out = StringBuilder()
            var from = start
            while (from < end) {
                val cdata = FeedXml.find(source, CDATA_OPEN, from).takeIf { it in from until end }
                if (cdata == null) {
                    out.append(FeedXml.decodeEntities(source.substring(from, end)))
                    break
                }
                out.append(FeedXml.decodeEntities(source.substring(from, cdata)))
                val close = FeedXml.find(source, CDATA_CLOSE, cdata).let { if (it < 0 || it > end) end else it }
                out.append(source, cdata + CDATA_OPEN.length, close)
                from = (close + CDATA_CLOSE.length).coerceAtMost(end)
            }
            return out.toString().trim()
        }

    /** 子要素の文字。無い・空なら null。 */
    fun childText(
        name: String,
        skip: String? = null,
    ): String? = child(name, skip)?.text?.takeIf { it.isNotEmpty() }

    private companion object {
        const val CDATA_OPEN = "<![CDATA["
        const val CDATA_CLOSE = "]]>"
    }
}

object FeedXml {
    /** 文書の最初の [name] の要素。 */
    fun root(
        source: String,
        name: String,
    ): XmlRange? = element(source, name, 0, source.length)

    /**
     * [from]〜[to] の中の、最初の `<name` の要素。閉じが無ければ範囲の終わりまで、`/>` で閉じていれば中身は空。
     * 同じ名前の要素の入れ子は考えない（RSS の item・title などは入れ子にならない）。
     */
    fun element(
        source: String,
        name: String,
        from: Int,
        to: Int,
    ): XmlRange? {
        val open = "<$name"
        var at = find(source, open, from)
        while (at in from until to) {
            val after = at + open.length
            val next = if (after < source.length) source[after] else '>'
            if (next == '>' || next == '/' || next == ' ' || next == '\t' || next == '\n' || next == '\r') {
                val tagEnd = tagEnd(source, after).takeIf { it in after until to } ?: return null
                val selfClosing = source[tagEnd - 1] == '/'
                val attributes = attributes(source, after, if (selfClosing) tagEnd - 1 else tagEnd)
                if (selfClosing) return XmlRange(source, tagEnd + 1, tagEnd + 1, attributes)
                val close = find(source, "</$name", tagEnd + 1).let { if (it < 0 || it > to) to else it }
                return XmlRange(source, tagEnd + 1, close, attributes)
            }
            at = find(source, open, after)
        }
        return null
    }

    /** [needle] の位置（無ければ -1）。String.indexOf は端末の側で前もって機械語になっていて、アプリのループより速い。 */
    fun find(
        source: String,
        needle: String,
        from: Int,
    ): Int = source.indexOf(needle, from)

    /**
     * 開きタグの `>` の位置。属性の値の中の `>` は飛ばす。
     * 引用符はタグの文字だけを切り出して数える（文書のまま indexOf で数えると、`'` の無い文書ではタグごとに終わりまで探してしまう）。
     */
    private fun tagEnd(
        source: String,
        from: Int,
    ): Int {
        var end = source.indexOf('>', from)
        while (end >= 0) {
            val tag = source.substring(from, end)
            if (count(tag, '"') % 2 == 0 && count(tag, '\'') % 2 == 0) return end
            end = source.indexOf('>', end + 1)
        }
        return -1
    }

    private fun count(
        text: String,
        char: Char,
    ): Int {
        var n = 0
        var at = text.indexOf(char)
        while (at >= 0) {
            n++
            at = text.indexOf(char, at + 1)
        }
        return n
    }

    /** `key="value"` の並び（[from]〜[to]）。 */
    private fun attributes(
        document: String,
        tagFrom: Int,
        tagTo: Int,
    ): Map<String, String> {
        // タグの文字だけを切り出して読む（文書のまま indexOf で探すと、無い字をタグの外まで探してしまう）
        val source = document.substring(tagFrom, tagTo)
        val from = 0
        val to = source.length
        var eq = source.indexOf('=', from)
        if (eq !in from until to) return emptyMap()
        val out = linkedMapOf<String, String>()
        var keyFrom = from
        while (eq in keyFrom until to) {
            val key = source.substring(keyFrom, eq).trim()
            var v = eq + 1
            while (v < to && source[v].isWhitespace()) v++
            if (v >= to) break
            val quote = source[v]
            if (quote == '"' || quote == '\'') {
                val valueEnd = source.indexOf(quote, v + 1).let { if (it < 0 || it > to) to else it }
                out[key] = decodeEntities(source.substring(v + 1, valueEnd))
                keyFrom = valueEnd + 1
            } else {
                var close = v
                while (close < to && !source[close].isWhitespace()) close++
                out[key] = decodeEntities(source.substring(v, close))
                keyFrom = close
            }
            eq = source.indexOf('=', keyFrom)
        }
        return out
    }

    fun decodeEntities(text: String): String {
        var amp = text.indexOf('&')
        if (amp < 0) return text
        val out = StringBuilder(text.length)
        var from = 0
        // 1字ずつ見ずに、& から & へ飛ぶ
        while (amp >= 0) {
            out.append(text, from, amp)
            val end = text.indexOf(';', amp)
            val decoded =
                if (end in (amp + 2)..(amp + 10)) {
                    val entity = text.substring(amp + 1, end)
                    when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity == "nbsp" -> " "
                        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePointString)
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePointString)
                        else -> null
                    }
                } else {
                    null
                }
            if (decoded != null) {
                out.append(decoded)
                from = end + 1
            } else {
                out.append('&')
                from = amp + 1
            }
            amp = text.indexOf('&', from)
        }
        out.append(text, from, text.length)
        return out.toString()
    }

    private fun codePointString(code: Int): String? =
        when {
            code < 0 || code > 0x10FFFF -> null
            code < 0x10000 -> code.toChar().toString()
            else -> {
                val v = code - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }
}
