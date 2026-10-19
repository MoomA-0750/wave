package xyz.mooma0750.wave.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFoldTest {
    @Test
    fun caseWidthAndKanaTypeAreIgnored() {
        // 大文字・小文字
        assertTrue(TextFold.matches("yoasobi", "YOASOBI"))
        // 全角・半角の英数字
        assertTrue(TextFold.matches("ＹＯＡＳＯＢＩ", "yoasobi"))
        assertTrue(TextFold.matches("yoasobi", "ＹＯＡＳＯＢＩ"))
        assertTrue(TextFold.matches("2024", "ライブ２０２４"))
        // ひらがな・カタカナ
        assertTrue(TextFold.matches("よあそび", "ヨアソビ"))
        assertTrue(TextFold.matches("ニカケル", "夜にかける"))
        // 半角のカタカナ（濁点・半濁点をつなぐ）
        assertTrue(TextFold.matches("ｶﾞｰﾙ", "ガール"))
        assertTrue(TextFold.matches("ぱんぷきん", "ﾊﾟﾝﾌﾟｷﾝ"))
        assertTrue(TextFold.matches("ｳﾞｧｲｵﾘﾝ", "ヴァイオリン"))
        // 合成用の濁点で書いたカナ
        assertTrue(TextFold.matches("ガ", "ガ"))
        // 違う字は当たらない
        assertFalse(TextFold.matches("かけ", "カゲ"))
        assertFalse(TextFold.matches("ﾊﾟ", "バ"))
    }

    @Test
    fun everyTermMustBeFoundInOneOfTheTexts() {
        // 空白（全角も）で区切った語がすべて、どれかの文に含まれていれば当たり
        assertTrue(TextFold.matches("夜に　yoasobi", "夜に駆ける", "YOASOBI"))
        assertFalse(TextFold.matches("夜に ado", "夜に駆ける", "YOASOBI"))
        // 語が無ければ全部当たる。null の文は飛ばす
        assertTrue(TextFold.matches("  ", "なんでも"))
        assertTrue(TextFold.matches("lofi", "Lo-Fi", null, "lofi girl"))
    }

    @Test
    fun foldKeepsOtherCharactersAsTheyAre() {
        assertEquals("ヨアソビ ガール 夜 abc!", TextFold.fold("よあそび　ｶﾞｰﾙ 夜 ＡＢＣ！"))
        // つなげない濁点は全角の濁点にする
        assertEquals("ア゛", TextFold.fold("ｱﾞ"))
    }
}
