package xyz.mooma0750.wave.login

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** evaluateJavascript の結果を文字列に戻す処理（ログインの値を読む入口）。今の挙動を固定する。 */
class DecodeJsStringTest {
    @Test
    fun plainQuotedStringsAreUnwrapped() {
        assertEquals("abc", decodeJsString("\"abc\""))
        assertEquals("a=b; c=d", decodeJsString("\"a=b; c=d\""))
    }

    @Test
    fun emptyNullAndNonStringsAreNull() {
        assertNull(decodeJsString(null))
        assertNull(decodeJsString("\"\""))
        assertNull(decodeJsString("\""))
        assertNull(decodeJsString("null"))
        assertNull(decodeJsString("123"))
        assertNull(decodeJsString("\"abc"))
    }

    @Test
    fun simpleEscapesAreDecoded() {
        assertEquals("a\"b\\c/d", decodeJsString("\"a\\\"b\\\\c\\/d\""))
        assertEquals("A=B", decodeJsString("\"\\u0041\\u003dB\""))
    }

    @Test
    fun controlEscapesAndBrokenEscapesAreRejected() {
        // 改行などを含む値はクッキーとして不正なので受けない
        assertNull(decodeJsString("\"a\\nb\""))
        assertNull(decodeJsString("\"a\\tb\""))
        assertNull(decodeJsString("\"a\\\""))
        assertNull(decodeJsString("\"\\u00\""))
        assertNull(decodeJsString("\"\\uZZZZ\""))
        assertNull(decodeJsString("\"\\x41\""))
    }
}
