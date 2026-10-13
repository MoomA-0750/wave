package xyz.mooma0750.wave.handoff.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EnvelopeSealerTest {
    @Test
    fun deviceIdIsBase64UrlOfPublicKeySha256() {
        val a = WaveCrypto.generateEphemeralKeyPair().public.encoded
        val b = WaveCrypto.generateEphemeralKeyPair().public.encoded
        val id = EnvelopeSealer.deviceId(a)
        // SHA-256 の 32 バイトを、パディングなしの base64url で
        assertEquals(43, id.length)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(WaveCrypto.sha256(a)), id)
        assertEquals(id, EnvelopeSealer.deviceId(a.copyOf()))
        assertNotEquals(id, EnvelopeSealer.deviceId(b))
    }
}
