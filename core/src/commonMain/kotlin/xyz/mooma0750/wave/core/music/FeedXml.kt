package xyz.mooma0750.wave.core.music

/**
 * ポッドキャストの RSS を読むための、小さな XML の読み取り（commonMain に JVM の XML を持ち込まない）。
 * 要素の名前は接頭辞ごと（`itunes:image` など）で扱う。名前空間は解決しない。
 * 壊れた入力でも例外にせず、読めたところまでの木を返す。
 */
class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
) {
    val children: MutableList<XmlElement> = mutableListOf()
    internal val textBuilder = StringBuilder()

    /** 中の文字（CDATA を含む。子要素の中の文字は含まない）。前後の空白は落とす。 */
    val text: String get() = textBuilder.toString().trim()

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }

    fun childrenNamed(name: String): List<XmlElement> = children.filter { it.name == name }

    /** 子要素の文字。無い・空なら null。 */
    fun childText(name: String): String? = child(name)?.text?.takeIf { it.isNotEmpty() }
}

object FeedXml {
    fun parse(input: String): XmlElement? {
        val root = XmlElement("#document", emptyMap())
        val stack = ArrayDeque<XmlElement>().apply { addLast(root) }
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            if (c != '<') {
                val end = input.indexOf('<', i).let { if (it < 0) n else it }
                stack.last().textBuilder.append(decodeEntities(input.substring(i, end)))
                i = end
                continue
            }
            when {
                input.startsWith("<!--", i) -> i = skipPast(input, i + 4, "-->")
                input.startsWith("<![CDATA[", i) -> {
                    val end = input.indexOf("]]>", i + 9).let { if (it < 0) n else it }
                    stack.last().textBuilder.append(input, i + 9, end)
                    i = (end + 3).coerceAtMost(n)
                }
                input.startsWith("<?", i) -> i = skipPast(input, i + 2, "?>")
                input.startsWith("<!", i) -> i = skipDeclaration(input, i + 2)
                input.startsWith("</", i) -> {
                    val end = input.indexOf('>', i).let { if (it < 0) n else it }
                    val name = input.substring(i + 2, end).trim()
                    // 対応する開きまで閉じる（閉じ忘れを許す）。見つからなければ無視する
                    if (stack.drop(1).any { it.name == name }) {
                        while (stack.size > 1) {
                            if (stack.removeLast().name == name) break
                        }
                    }
                    i = end + 1
                }
                else -> {
                    val (element, selfClosing, next) = readStartTag(input, i + 1) ?: run {
                        stack.last().textBuilder.append('<')
                        i++
                        null
                    } ?: continue
                    stack.last().children += element
                    if (!selfClosing) stack.addLast(element)
                    i = next
                }
            }
        }
        return root.children.firstOrNull()
    }

    private fun skipPast(
        input: String,
        from: Int,
        terminator: String,
    ): Int = input.indexOf(terminator, from).let { if (it < 0) input.length else it + terminator.length }

    /** `<!DOCTYPE … [ … ]>` のように中に `[]` を持つ宣言も読み飛ばす。 */
    private fun skipDeclaration(
        input: String,
        from: Int,
    ): Int {
        var depth = 0
        var i = from
        while (i < input.length) {
            when (input[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return input.length
    }

    private fun readStartTag(
        input: String,
        from: Int,
    ): Triple<XmlElement, Boolean, Int>? {
        var i = from
        val n = input.length
        val nameStart = i
        while (i < n && !input[i].isWhitespace() && input[i] != '>' && input[i] != '/') i++
        val name = input.substring(nameStart, i)
        if (name.isEmpty()) return null
        val attributes = linkedMapOf<String, String>()
        while (i < n) {
            while (i < n && input[i].isWhitespace()) i++
            if (i >= n) break
            if (input[i] == '>') return Triple(XmlElement(name, attributes), false, i + 1)
            if (input.startsWith("/>", i)) return Triple(XmlElement(name, attributes), true, i + 2)
            val keyStart = i
            while (i < n && !input[i].isWhitespace() && input[i] != '=' && input[i] != '>' && input[i] != '/') i++
            val key = input.substring(keyStart, i)
            while (i < n && input[i].isWhitespace()) i++
            if (i < n && input[i] == '=') {
                i++
                while (i < n && input[i].isWhitespace()) i++
                if (i < n && (input[i] == '"' || input[i] == '\'')) {
                    val quote = input[i]
                    val end = input.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    attributes[key] = decodeEntities(input.substring(i + 1, end))
                    i = end + 1
                } else {
                    val valueStart = i
                    while (i < n && !input[i].isWhitespace() && input[i] != '>') i++
                    attributes[key] = decodeEntities(input.substring(valueStart, i))
                }
            } else if (key.isNotEmpty()) {
                attributes[key] = ""
            } else {
                i++
            }
        }
        return Triple(XmlElement(name, attributes), false, n)
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val end = text.indexOf(';', i)
                if (end in (i + 2)..(i + 10)) {
                    val entity = text.substring(i + 1, end)
                    val decoded =
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
                    if (decoded != null) {
                        out.append(decoded)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
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
