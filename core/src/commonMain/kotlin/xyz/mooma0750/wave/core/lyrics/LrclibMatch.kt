package xyz.mooma0750.wave.core.lyrics

/** NFKC（全角・半角や互換の文字をそろえる）。 */
internal expect fun nfkc(text: String): String

/** Unicode の一般カテゴリーが文字（L）か数字（N）か。 */
internal expect fun isLetterOrNumber(codePoint: Int): Boolean

/**
 * LRCLIB の検索の結果が今の曲と同じかの照らし合わせ（Swift・C#・Rust の版と同じ規則）。
 * 長さだけで選ぶと、長さの近い別の曲の歌詞を選んでしまうため。
 */
internal object LrclibMatch {
    /** 曲名が一致しなくても、アーティストが一致して長さがこの秒数以内なら同じ曲とみなす（日本語の曲名がローマ字・英語で登録されていることがあるため）。 */
    const val SAME_LENGTH_WITHOUT_TITLE = 2.0

    private val brackets = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
    private val dash = Regex("""\s[-–—]\s""")
    private val feat = Regex("""\s(?:feat\.?|ft\.|featuring)\s""", RegexOption.IGNORE_CASE)
    private val artistSeparators = Regex("""\s*[,&、/;×]\s*|\s+(?:x|feat\.?|ft\.|featuring|vs\.?)\s+""", RegexOption.IGNORE_CASE)

    /** 比べる形。NFKC にして小文字にし、文字（L）と数字（N）だけを残す。 */
    fun key(text: String): String {
        val lower = nfkc(text).lowercase()
        return buildString {
            var i = 0
            while (i < lower.length) {
                val high = lower[i]
                val pair = high.isHighSurrogate() && i + 1 < lower.length && lower[i + 1].isLowSurrogate()
                val codePoint = if (pair) ((high.code - 0xD800) shl 10) + (lower[i + 1].code - 0xDC00) + 0x10000 else high.code
                if (isLetterOrNumber(codePoint)) append(lower, i, i + if (pair) 2 else 1)
                i += if (pair) 2 else 1
            }
        }
    }

    /** 曲名を比べる形。そのまま、括弧の中の補足を除いた形、「 - 」の後ろを除いた形、「feat.」の後ろを除いた形。 */
    fun titleKeys(title: String): List<String> {
        val full = nfkc(title)
        val withoutBrackets = brackets.replace(full, "")
        val beforeDash = dash.split(withoutBrackets).first()
        val beforeFeat = feat.split(beforeDash).first()
        return distinctKeys(listOf(full, withoutBrackets, beforeDash, beforeFeat))
    }

    /** アーティストを比べる形。全体と、「,」「&」「feat.」「×」などで分けたそれぞれの名前。 */
    fun artistKeys(artist: String): List<String> {
        val full = nfkc(artist)
        return distinctKeys(listOf(full) + artistSeparators.split(full))
    }

    private fun distinctKeys(texts: List<String>): List<String> = texts.map(::key).filter { it.isNotEmpty() }.distinct()

    /** どれかの形が等しいか、片方がもう片方を含めば一致。 */
    fun overlap(
        a: List<String>,
        b: List<String>,
    ): Boolean = a.any { x -> b.any { y -> x in y || y in x } }
}
