package xyz.mooma0750.wave.handoff.v2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocSealerTest {
    private val a = TestDevice()
    private val b = TestDevice()
    private val pc = TestDevice(Role.Controller)
    private val genesis = MemberChain.genesis(a, a.keys)
    private val records = listOf(genesis, MemberChain.add(a, listOf(genesis).verified(), b.keys))
    private val withPc = records + MemberChain.add(a, records.verified(), pc.keys)
    private val chain = withPc.verified()
    private val body = "cursor-body".encodeToByteArray()

    private fun seal(
        signer: DeviceSigner = a,
        kind: DocKind = DocKind.Cursor,
        onChain: VerifiedChain = chain,
    ) = DocSealer.seal(signer, onChain, kind, version = 7, prevHash = null, issuedAtEpochMs = 1, plaintext = body)

    @Test
    fun everyMemberOpensAndOthersCannot() {
        val doc = seal()
        for (device in listOf(a, b, pc)) {
            val opened = assertIs<DocOpen.Ok>(DocSealer.open(doc, chain, device))
            assertContentEquals(body, opened.plaintext)
            assertFalse(opened.staleRecipients)
        }
        assertEquals(DocOpen.Rejected("not_for_me"), DocSealer.open(doc, chain, TestDevice()))
        // 大きさは 4KB 単位
        assertEquals(0, java.util.Base64.getUrlDecoder().decode(doc.ciphertext).size.minus(16) % 4096)
    }

    @Test
    fun rejectsTamperingSubstitutionAndWrongRoles() {
        val doc = seal()
        // 見出し・暗号文・宛先の包みのどれを変えても署名で落ちる
        assertEquals(DocOpen.Rejected("signature"), DocSealer.open(doc.copy(header = doc.header.copy(version = 8)), chain, b))
        assertEquals(DocOpen.Rejected("signature"), DocSealer.open(doc.copy(ciphertext = seal().ciphertext), chain, b))
        // 宛先一覧から1件削る・入れ替える（サーバーによる）
        assertEquals(DocOpen.Rejected("signature"), DocSealer.open(doc.copy(recipients = doc.recipients.filter { it.deviceId != pc.deviceId }), chain, b))
        val other = seal()
        val swapped = doc.recipients.map { r -> if (r.deviceId == b.deviceId) other.recipients.first { it.deviceId == b.deviceId } else r }
        assertEquals(DocOpen.Rejected("signature"), DocSealer.open(doc.copy(recipients = swapped), chain, b))
        // 家の外の端末が書いた（署名者を家の端末と名乗っても、署名が合わない）
        val outsider = TestDevice()
        val outsiderDoc = runCatching { seal(signer = outsider) }
        assertTrue(outsiderDoc.isFailure, "家の外の端末で封じられた")
        val forgedWriter = doc.copy(signature = seal(signer = b).signature)
        assertEquals(DocOpen.Rejected("signature"), DocSealer.open(forgedWriter, chain, b))
        // controller はカーソル・キューを書けない。命令は書ける
        assertEquals(DocOpen.Rejected("role"), DocSealer.open(seal(signer = pc), chain, a))
        assertIs<DocOpen.Ok>(DocSealer.open(seal(signer = pc, kind = DocKind.Command), chain, a))
        // 別の家
        val otherHousehold = listOf(MemberChain.genesis(b, b.keys)).verified()
        assertEquals(DocOpen.Rejected("household"), DocSealer.open(doc, otherHousehold, b))
    }

    @Test
    fun headOfTheWritersListIsChecked() {
        val doc = seal()
        // 書き手の頭が自分より新しい → 鎖を取り直す
        assertEquals(DocOpen.NeedChain(chain.headSeq), DocSealer.open(doc, records.verified(), b))
        // 同じ seq で別の枝 → 分岐
        val branch = (records + MemberChain.add(b, records.verified(), TestDevice().keys)).verified()
        assertEquals(DocOpen.Fork, DocSealer.open(doc, branch, b))
        // 外された端末が宛先にいる（古い一覧で書かれた）→ 開けるが印が付く
        val afterRemove = (withPc + MemberChain.remove(b, chain, pc.deviceId)).verified()
        val opened = assertIs<DocOpen.Ok>(DocSealer.open(doc, afterRemove, b))
        assertTrue(opened.staleRecipients)
        // 書いた後に外された書き手の doc は受けない
        val removedWriterChain = (withPc + MemberChain.remove(a, chain, b.deviceId)).verified()
        assertEquals(DocOpen.Rejected("writer_not_member"), DocSealer.open(seal(signer = b), removedWriterChain, a))
        // 外した後に書いた doc は、外された端末に包まない
        val afterRemoveDoc = seal(onChain = afterRemove)
        assertFalse(afterRemoveDoc.recipients.any { it.deviceId == pc.deviceId })
    }
}
