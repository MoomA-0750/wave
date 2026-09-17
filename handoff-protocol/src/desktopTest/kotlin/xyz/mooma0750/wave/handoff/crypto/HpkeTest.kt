package xyz.mooma0750.wave.handoff.crypto

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RFC 9180 付録 A.3（DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM）の base モードのテストベクタ。 */
class HpkeTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val info = hex("4f6465206f6e2061204772656369616e2055726e")
    private val ikmE = hex("4270e54ffd08d79d5928020af4686d8f6b7d35dbe470265f1f5aa22816ce860e")
    private val pkEm = hex("04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4")
    private val skEm = hex("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb")
    private val ikmR = hex("668b37171f1072f3cf12ea8a236a45df23fc13b82af3609ad1e354f6ef817550")
    private val pkRm = hex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0")
    private val skRm = hex("f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2")
    private val sharedSecret = hex("c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8")
    private val key = hex("868c066ef58aae6dc589b6cfdd18f97e")
    private val baseNonce = hex("4e0bc5018beba4bf004cca59")
    private val exporterSecret = hex("14ad94af484a7ad3ef40e9f3be99ecc6fa9036df9d4920548424df127ee0d99f")
    private val pt = hex("4265617574792069732074727574682c20747275746820626561757479")

    private val encryptions =
        listOf(
            Triple(0L, "436f756e742d30", "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"),
            Triple(1L, "436f756e742d31", "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"),
            Triple(2L, "436f756e742d32", "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"),
            Triple(4L, "436f756e742d34", "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"),
            Triple(255L, "436f756e742d323535", "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"),
            Triple(256L, "436f756e742d323536", "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"),
        )

    @Test
    fun deriveKeyPairMatchesVectors() {
        assertEquals(BigInteger(1, skEm), Hpke.deriveSecretScalar(ikmE))
        assertEquals(BigInteger(1, skRm), Hpke.deriveSecretScalar(ikmR))
    }

    @Test
    fun encapAndKeyScheduleMatchVectors() {
        val pkE = assertNotNull(Hpke.deserializePublicKey(pkEm))
        val pkR = assertNotNull(Hpke.deserializePublicKey(pkRm))
        assertContentEquals(pkEm, Hpke.serializePublicKey(pkE))
        val ephemeral = Hpke.keyPairOf(BigInteger(1, skEm), pkE)
        val (shared, enc) = Hpke.encapForTest(pkR, ephemeral)
        assertContentEquals(pkEm, enc)
        assertContentEquals(sharedSecret, shared)
        val context = Hpke.keySchedule(shared, info)
        assertContentEquals(key, context.key)
        assertContentEquals(baseNonce, context.baseNonce)
        assertContentEquals(exporterSecret, context.exporterSecret)
        for ((seq, aad, ct) in encryptions) {
            assertContentEquals(hex(ct), context.seal(seq, hex(aad), pt), "seq=$seq")
            assertContentEquals(pt, context.open(seq, hex(aad), hex(ct)), "seq=$seq")
        }
    }

    @Test
    fun openWithRecipientDhRoundTripsAndRejectsTampering() {
        val pkR = assertNotNull(Hpke.deserializePublicKey(pkRm))
        val recipient = Hpke.keyPairOf(BigInteger(1, skRm), pkR)
        val dh = { peer: java.security.interfaces.ECPublicKey -> WaveCrypto.ecdh(recipient.private, peer) }
        val sealed = Hpke.seal(pkR, info, hex("01"), pt)
        assertContentEquals(pt, Hpke.open(sealed.enc, pkR, dh, info, hex("01"), sealed.ciphertext))
        // aad・info・暗号文・enc のどれが違っても開けない
        assertNull(Hpke.open(sealed.enc, pkR, dh, info, hex("02"), sealed.ciphertext))
        assertNull(Hpke.open(sealed.enc, pkR, dh, hex("00"), hex("01"), sealed.ciphertext))
        assertNull(Hpke.open(sealed.enc, pkR, dh, info, hex("01"), sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        val otherEnc = Hpke.serializePublicKey(WaveCrypto.generateEphemeralKeyPair().public as java.security.interfaces.ECPublicKey)
        assertNull(Hpke.open(otherEnc, pkR, dh, info, hex("01"), sealed.ciphertext))
        // 別の受け手の鍵では開けない
        val other = WaveCrypto.generateEphemeralKeyPair()
        val otherPub = other.public as java.security.interfaces.ECPublicKey
        assertNull(Hpke.open(sealed.enc, otherPub, { WaveCrypto.ecdh(other.private, it) }, info, hex("01"), sealed.ciphertext))
    }

    @Test
    fun rejectsMalformedEncodedPoints() {
        // 長さ違い・圧縮形・無限遠点・曲線外の点
        assertNull(Hpke.deserializePublicKey(pkEm.copyOf(64)))
        assertNull(Hpke.deserializePublicKey(byteArrayOf(0x02) + pkEm.copyOfRange(1, 33)))
        assertNull(Hpke.deserializePublicKey(byteArrayOf(0x00)))
        assertNull(Hpke.deserializePublicKey(ByteArray(65).also { it[0] = 0x04 }))
        val offCurve = pkEm.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNull(Hpke.deserializePublicKey(offCurve))
        val prefixed = pkEm.copyOf().also { it[0] = 0x03 }
        assertNull(Hpke.deserializePublicKey(prefixed))
    }
}
