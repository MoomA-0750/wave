package xyz.mooma0750.wave.playback

import okhttp3.CookieJar
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 音声用のクライアントが認証情報を持たないこと（ADR-0003）。 */
class MediaHttpTest {
    private val client = MediaHttp.create()

    @Test
    fun hasNoCookiesAuthenticatorInterceptorsOrCache() {
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertNull(client.cache)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
    }
}
