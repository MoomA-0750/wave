package xyz.mooma0750.wave.watchlink

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchLoginCryptoTest {
    @Test
    fun theWatchOpensWhatThePhoneSealed() {
        val watch = WatchLoginCrypto.newKeyPair()
        val plain = "secret".encodeToByteArray()
        val sealed = WatchLoginCrypto.seal(watch.public.encoded, plain)!!
        assertContentEquals(plain, WatchLoginCrypto.open(watch, sealed))
    }

    @Test
    fun anotherKeyCannotOpen() {
        val watch = WatchLoginCrypto.newKeyPair()
        val sealed = WatchLoginCrypto.seal(watch.public.encoded, "secret".encodeToByteArray())!!
        assertNull(WatchLoginCrypto.open(WatchLoginCrypto.newKeyPair(), sealed))
    }

    @Test
    fun aTamperedMessageCannotBeOpened() {
        val watch = WatchLoginCrypto.newKeyPair()
        val sealed = WatchLoginCrypto.seal(watch.public.encoded, "secret".encodeToByteArray())!!
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(WatchLoginCrypto.open(watch, sealed))
        assertNull(WatchLoginCrypto.open(watch, byteArrayOf(0, 5, 1)))
    }

    @Test
    fun theCodeIsSixDigitsAndStable() {
        val key = WatchLoginCrypto.newKeyPair().public.encoded
        val code = WatchLoginCrypto.codeOf(key)
        assertEquals(code, WatchLoginCrypto.codeOf(key))
        assert(Regex("""\d{3} \d{3}""").matches(code))
    }
}
