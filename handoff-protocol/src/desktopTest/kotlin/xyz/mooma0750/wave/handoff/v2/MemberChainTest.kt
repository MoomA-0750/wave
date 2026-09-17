package xyz.mooma0750.wave.handoff.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemberChainTest {
    private val a = TestDevice()
    private val b = TestDevice()
    private val pc = TestDevice(Role.Controller)

    private fun genesisAndB(): List<ChainRecord> {
        val genesis = MemberChain.genesis(a, a.keys)
        val addB = MemberChain.add(a, listOf(genesis).verified(), b.keys)
        return listOf(genesis, addB)
    }

    private fun invalidReason(records: List<ChainRecord>): String = assertIs<ChainVerification.Invalid>(MemberChain.verify(records)).reason

    @Test
    fun genesisAddAndRemoveVerify() {
        val records = genesisAndB()
        val chain = records.verified()
        assertEquals(setOf(a.deviceId, b.deviceId), chain.members.keys)
        assertEquals(MemberChain.hash(records[0]), chain.householdId)
        val withPc = records + MemberChain.add(b, chain, pc.keys)
        val removed = withPc + MemberChain.remove(a, withPc.verified(), b.deviceId)
        val finalChain = removed.verified()
        assertEquals(setOf(a.deviceId, pc.deviceId), finalChain.members.keys)
        assertEquals(setOf(b.deviceId), finalChain.removed)
        assertEquals(3L, finalChain.headSeq)
    }

    @Test
    fun rejectsForgedOrUnauthorizedRecords() {
        val records = genesisAndB()
        val chain = records.verified()
        // 家の外の端末の署名で加える（サーバーが足した端末）
        val outsider = TestDevice()
        assertEquals("signer_not_member", invalidReason(records + MemberChain.add(outsider, chain, TestDevice().keys)))
        // 署名者を家の端末と名乗るが、署名は外の鍵
        val forged = MemberChain.add(outsider, chain, TestDevice().keys).copy(signerId = a.deviceId)
        assertEquals("signature", invalidReason(records + forged))
        // controller は player を加えられない（L-11）
        val withPc = records + MemberChain.add(a, chain, pc.keys)
        assertEquals("add_by_controller", invalidReason(withPc + MemberChain.add(pc, withPc.verified(), TestDevice().keys)))
        // controller は他の端末を外せない。自分だけ
        assertEquals("remove_by_controller", invalidReason(withPc + MemberChain.remove(pc, withPc.verified(), b.deviceId)))
        assertIs<ChainVerification.Ok>(MemberChain.verify(withPc + MemberChain.remove(pc, withPc.verified(), pc.deviceId)))
        // 最後の player は外せない
        val onlyA = records + MemberChain.remove(a, chain, b.deviceId)
        assertEquals("last_player", invalidReason(onlyA + MemberChain.remove(a, onlyA.verified(), a.deviceId)))
        // 外された端末は、その後に記録を署名できない
        assertEquals("signer_not_member", invalidReason(onlyA + MemberChain.add(b, onlyA.verified(), TestDevice().keys)))
    }

    @Test
    fun rejectsBrokenLinksAndKeys() {
        val records = genesisAndB()
        // prevHash の付け替え
        assertEquals("link", invalidReason(listOf(records[0], records[1].copy(prevHash = "x"))))
        // 別の家の記録を混ぜる
        val otherGenesis = MemberChain.genesis(b, b.keys)
        assertEquals("link", invalidReason(listOf(otherGenesis, records[1])))
        // kx の自己署名が他人のもの（kx 公開鍵のすり替え）
        val swapped = b.keys.copy(kxPub = TestDevice().keys.kxPub)
        assertEquals("add_keys", invalidReason(listOf(records[0], MemberChain.add(a, listOf(records[0]).verified(), swapped))))
        // deviceId が身元鍵と合わない
        val wrongId = b.keys.copy(deviceId = TestDevice().deviceId)
        assertEquals("add_keys", invalidReason(listOf(records[0], MemberChain.add(a, listOf(records[0]).verified(), wrongId))))
        // 同じ端末を2回加える
        assertEquals("add_existing", invalidReason(records + MemberChain.add(a, records.verified(), b.keys)))
    }

    @Test
    fun detectsRollbackAndForkAgainstTrustedHead() {
        val records = genesisAndB()
        val chain = records.verified()
        val head = MemberChain.Head(chain.headSeq, chain.headHash)
        // 短い鎖（サーバーが巻き戻した）
        assertIs<ChainVerification.Rollback>(MemberChain.verify(records.take(1), head))
        // CAS で負けた本物の記録で作った、同じ長さの別の枝
        val branch = listOf(records[0], MemberChain.add(a, listOf(records[0]).verified(), TestDevice().keys))
        assertIs<ChainVerification.Fork>(MemberChain.verify(branch, head))
        // 伸びた鎖は受ける
        val longer = records + MemberChain.add(b, chain, pc.keys)
        assertTrue(MemberChain.verify(longer, head) is ChainVerification.Ok)
    }
}
