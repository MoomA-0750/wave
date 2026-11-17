package xyz.mooma0750.wave.core.lyrics

import java.text.Normalizer

internal actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun isLetterOrNumber(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }
