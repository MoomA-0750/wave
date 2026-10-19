package xyz.mooma0750.wave.core.music

/**
 * 一覧の中を探すときの照らし方（プレイリストの中の検索・マイライブラリの検索）。
 * 大文字・小文字、全角・半角、ひらがな・カタカナを区別しない（Mac・iPhone の `fold`、Windows の
 * `IgnoreCase | IgnoreWidth | IgnoreKanaType` と同じ）。打った語を空白（全角も）で区切り、すべて含まれていれば当たり。
 * 画面にも通信にも依らない。
 */
object TextFold {
    /** 打った語が、文のどれかにすべて含まれているか（語が無ければ当たり）。 */
    fun matches(
        query: String,
        vararg texts: String?,
    ): Boolean = matchesTerms(terms(query), texts.asList())

    /** [terms] で区切った語が、文のどれかにすべて含まれているか（たくさんの行を照らすときに、語を区切り直さない）。 */
    fun matchesTerms(
        terms: List<String>,
        texts: List<String?>,
    ): Boolean {
        if (terms.isEmpty()) return true
        val haystack = texts.filterNotNull().joinToString(" ") { fold(it) }
        return terms.all { it in haystack }
    }

    /** 打った語を照らす形にして空白で区切る。 */
    fun terms(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /**
     * 照らす形にする: 全角の英数字・記号は半角に、全角の空白は半角に、半角のカタカナは全角に（濁点・半濁点はつなぐ）、
     * ひらがなはカタカナに、英字は小文字にする。
     */
    fun fold(text: String): String =
        buildString(text.length) {
            for (raw in text) {
                var c = raw
                when (c) {
                    '　' -> c = ' '
                    in '！'..'～' -> c = c - 0xFEE0
                    in '｡'..'ﾟ' -> {
                        // 濁点・半濁点は前のカナにつなぐ（つなげなければ全角の濁点にする）
                        if (c == 'ﾞ' || c == 'ﾟ') {
                            appendMark(c == 'ﾞ')
                            continue
                        }
                        c = HALFWIDTH_KANA[c - '｡']
                    }
                    // 合成用の濁点・半濁点（「カ」＋「゙」）も、つないだ1文字にそろえる
                    '゙', '゚' -> {
                        appendMark(c == '゙')
                        continue
                    }
                }
                if (c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ') c = c + 0x60
                append(c.lowercaseChar())
            }
        }

    /** 前の文字に濁点（voiced）・半濁点をつなぐ。つなげなければ全角の濁点・半濁点を足す。 */
    private fun StringBuilder.appendMark(voiced: Boolean) {
        val last = lastOrNull()
        val joined =
            when {
                last == null -> null
                voiced && last == 'ウ' -> 'ヴ'
                // カ〜ト・ハ〜ホは、すぐ後ろの符号が濁点の付いた字（ガ・バ など）
                voiced && (last in 'カ'..'ト' || last in 'ハ'..'ホ') && last + 1 in VOICED -> last + 1
                // ハ・ヒ・フ・ヘ・ホの2つ後ろが半濁点の付いた字（パ など）
                !voiced && last in 'ハ'..'ホ' && (last - 'ハ') % 3 == 0 -> last + 2
                voiced && (last == 'ワ' || last == 'ヲ') -> if (last == 'ワ') 'ヷ' else 'ヺ'
                else -> null
            }
        if (joined != null) {
            setCharAt(length - 1, joined)
        } else {
            append(if (voiced) '゛' else '゜')
        }
    }

    /** 濁点の付いたカタカナ（ガ〜ド、バ〜ボ）。前の文字＋1 がここにあれば濁点をつなげる。 */
    private const val VOICED = "ガギグゲゴザジズゼゾダヂヅデドバビブベボ"

    /** U+FF61〜U+FF9D の半角の文字に当たる全角の文字（濁点・半濁点の U+FF9E・U+FF9F は別に扱う）。 */
    private const val HALFWIDTH_KANA = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"
}
