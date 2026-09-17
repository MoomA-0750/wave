package xyz.mooma0750.wave.handoff.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun range(from: Int, until: Int) = (from until until).map { it.toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val prk = Hkdf.extract(hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b })
        assertContentEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), Hkdf.expand(prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42))
    }

    @Test
    fun hkdfMatchesRfc5869TestCase2() {
        val prk = Hkdf.extract(range(0x60, 0xb0), range(0x00, 0x50))
        assertContentEquals(hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"), prk)
        assertContentEquals(
            hex("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87"),
            Hkdf.expand(prk, range(0xb0, 0x100), 82),
        )
    }

    @Test
    fun hkdfMatchesRfc5869TestCase3() {
        val prk = Hkdf.extract(ByteArray(0), ByteArray(22) { 0x0b })
        assertContentEquals(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), prk)
        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), Hkdf.expand(prk, ByteArray(0), 42))
    }

    @Test
    fun ecdhAgreesAndPublicKeysRoundTrip() {
        val a = WaveCrypto.generateEphemeralKeyPair()
        val b = WaveCrypto.generateEphemeralKeyPair()
        val pubA = assertNotNull(WaveCrypto.decodePublicKey(WaveCrypto.encodePublicKey(a.public)))
        val pubB = assertNotNull(WaveCrypto.decodePublicKey(WaveCrypto.encodePublicKey(b.public)))
        assertContentEquals(WaveCrypto.ecdh(a.private, pubB), WaveCrypto.ecdh(b.private, pubA))
    }

    @Test
    fun pointsOffTheCurveAndOtherCurvesAreRejected() {
        val encoded = WaveCrypto.encodePublicKey(WaveCrypto.generateEphemeralKeyPair().public)
        val broken = encoded.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(WaveCrypto.decodePublicKey(broken))
        val p384 = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertNull(WaveCrypto.decodePublicKey(p384.public.encoded))
        assertNull(WaveCrypto.decodePublicKey(ByteArray(10)))
    }

    @Test
    fun signaturesAreBoundToTheirLabel() {
        val key = WaveCrypto.generateEphemeralKeyPair()
        val signature = WaveCrypto.sign(key.private, "wave-relay-auth-v1", byteArrayOf(1, 2, 3))
        assertTrue(WaveCrypto.verify(key.public, "wave-relay-auth-v1", byteArrayOf(1, 2, 3), signature))
        assertFalse(WaveCrypto.verify(key.public, "wave-pair-v1", byteArrayOf(1, 2, 3), signature), "別の用途のラベルでも通った")
        assertFalse(WaveCrypto.verify(key.public, "wave-relay-auth-v1", byteArrayOf(1, 2, 4), signature))
    }

    @Test
    fun sealedDataFailsWithAnotherAadOrKey() {
        val key = WaveCrypto.randomBytes(32)
        val (nonce, ciphertext) = WaveCrypto.seal(key, byteArrayOf(9), "hello".encodeToByteArray())
        assertContentEquals("hello".encodeToByteArray(), WaveCrypto.open(key, nonce, byteArrayOf(9), ciphertext))
        assertNull(WaveCrypto.open(key, nonce, byteArrayOf(8), ciphertext))
        assertNull(WaveCrypto.open(WaveCrypto.randomBytes(32), nonce, byteArrayOf(9), ciphertext))
        assertNull(WaveCrypto.open(key, nonce, byteArrayOf(9), ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }))
    }

    @Test
    fun lengthPrefixingAvoidsAmbiguousConcatenation() {
        assertFalse(WaveCrypto.lengthPrefixed(byteArrayOf(1, 2), byteArrayOf(3)).contentEquals(WaveCrypto.lengthPrefixed(byteArrayOf(1), byteArrayOf(2, 3))))
    }
}
