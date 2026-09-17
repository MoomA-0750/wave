package xyz.mooma0750.wave.core.session

import com.metrolist.innertubex.models.YouTubeLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeLocaleTest {
    private fun hlGl(hl: String, gl: String) = sanitizedYouTubeLocale(YouTubeLocale(gl = gl, hl = hl)).let { it.hl to it.gl }

    @Test
    fun dropsUnicodeExtension() {
        // Galaxy（Android 16）の既定の言語。そのまま送ると検索が 400 になった
        assertEquals("en-US" to "US", hlGl("en-US-u-mu-celsius", "US"))
        assertEquals("ja-JP" to "JP", hlGl("ja-JP-u-mu-celsius-fw-mon", "JP"))
        assertEquals("en-US" to "US", hlGl("en-US-x-private", "US"))
    }

    @Test
    fun keepsPlainTagsAndScripts() {
        assertEquals("ja-JP" to "JP", hlGl("ja-JP", "JP"))
        assertEquals("zh-Hant-TW" to "TW", hlGl("zh-Hant-TW", "TW"))
        assertEquals("en" to "US", hlGl("en", ""))
    }

    @Test
    fun fallsBackWhenMalformed() {
        assertEquals("en" to "US", hlGl("und", ""))
        assertEquals("en" to "US", hlGl("", "419"))
        assertEquals("en" to "US", hlGl("e\"n", "us"))
    }
}
