package xyz.mooma0750.wave.handoff.v2

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 署名とハッシュの対象になるバイト列を、既知の値で固定する（リファクタで形が1バイトでも変われば落ちる）。
 * どちらのハッシュも署名を含めない（p6b12）ので、署名の値は何でもよい。
 */
class CanonicalBytesTest {
    private val keys = MemberKeys("dev-b", "pub-b", "kx-b", Role.Player, "kxsig-b")

    @Test
    fun chainRecordHashesAreFixed() {
        val genesis = ChainRecord(seq = 0, type = ChainType.Genesis, target = MemberKeys("dev-a", "pub-a", "kx-a", Role.Player, "kxsig-a"), signerId = "dev-a", signature = "sig")
        val add = ChainRecord(householdId = "house", seq = 1, prevHash = "prev", type = ChainType.Add, target = keys, signerId = "dev-a", signature = "sig")
        val remove = ChainRecord(householdId = "house", seq = 2, prevHash = "prev2", type = ChainType.Remove, removedDeviceId = "dev-b", signerId = "dev-a", signature = "sig")
        assertEquals("07McxbsrW_iq4fxMp1hn5SQy6I5gpkAGc1OzMU1ZcZI", MemberChain.hash(genesis))
        assertEquals("Bc9cJdgfyeNXxPZ5HM4SisXRhTNBchPH6MbaOb57ATI", MemberChain.hash(add))
        assertEquals("A6e-7u5LQFx11vmK5Zj6n7QgeG01bctfiCnA9VnjvaU", MemberChain.hash(remove))
        assertEquals(MemberChain.hash(add), MemberChain.hash(add.copy(signature = "other")))
    }

    @Test
    fun sealedDocHashesAreFixed() {
        val header = DocHeader(kind = DocKind.Cursor, householdId = "house", writerId = "dev-a", version = 7, prevHash = "p", headSeq = 3, headHash = "h", issuedAtEpochMs = 1_700_000_000_000)
        val doc = SealedDoc(header, "nonce", "cipher", listOf(DocRecipient("dev-a", "enc-a", "wrap-a"), DocRecipient("dev-b", "enc-b", "wrap-b")), "sig")
        assertEquals("OIhuUfwQMOoU568qY4i4GiaVIZqPkDqmg_gOeXLnaFI", DocSealer.hash(doc))
        assertEquals("6ajZYk-MILk4axIoVv59uvEvaxL1XWV1ijvh2K9c6jQ", DocSealer.hash(doc.copy(header = header.copy(kind = DocKind.Queue, prevHash = null))))
        assertEquals(DocSealer.hash(doc), DocSealer.hash(doc.copy(signature = "other")))
    }
}
