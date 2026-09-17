package xyz.mooma0750.wave.relay.v2

import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.ChainRecord
import xyz.mooma0750.wave.handoff.v2.ChainVerification
import xyz.mooma0750.wave.handoff.v2.DeviceSigner
import xyz.mooma0750.wave.handoff.v2.DocKind
import xyz.mooma0750.wave.handoff.v2.DocSealer
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.MemberKeys
import xyz.mooma0750.wave.handoff.v2.RelayV2
import xyz.mooma0750.wave.handoff.v2.Role
import xyz.mooma0750.wave.handoff.v2.StateKind
import xyz.mooma0750.wave.handoff.v2.VerifiedChain
import java.io.File
import java.nio.file.Files
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RelayTestDevice(
    role: Role = Role.Player,
) : DeviceSigner {
    private val identity = WaveCrypto.generateEphemeralKeyPair()
    val kx = WaveCrypto.generateEphemeralKeyPair()
    override val identityPub: ByteArray = WaveCrypto.encodePublicKey(identity.public)
    override val deviceId: String = EnvelopeSealer.deviceId(identityPub)
    val publicKeyB64: String = Base64.getUrlEncoder().withoutPadding().encodeToString(identityPub)
    val keys: MemberKeys = MemberChain.memberKeys(this, kx.public as ECPublicKey, role)

    override fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = WaveCrypto.sign(identity.private, label, message)
}

class HouseholdStoreTest {
    private var now = 1_000L
    private val dir = Files.createTempDirectory("household").toFile()
    private val file = File(dir, "household.json")
    private val a = RelayTestDevice()
    private val b = RelayTestDevice()
    private val pc = RelayTestDevice(Role.Controller)

    private fun store() = HouseholdStore(file, listOf(a.publicKeyB64), nowMs = { now })

    private fun verified(records: List<ChainRecord>): VerifiedChain = (MemberChain.verify(records) as ChainVerification.Ok).chain

    /** A が家を作り、B と PC を加えた店。 */
    private fun household(): Pair<HouseholdStore, VerifiedChain> {
        val s = store()
        val genesis = MemberChain.genesis(a, a.keys)
        assertIs<HouseholdStore.ChainResult.Ok>(s.createHousehold(a.deviceId, genesis))
        val addB = MemberChain.add(a, verified(listOf(genesis)), b.keys)
        assertIs<HouseholdStore.ChainResult.Ok>(s.append(a.deviceId, addB))
        val addPc = MemberChain.add(b, verified(listOf(genesis, addB)), pc.keys)
        assertIs<HouseholdStore.ChainResult.Ok>(s.append(b.deviceId, addPc))
        return s to verified(listOf(genesis, addB, addPc))
    }

    private fun doc(
        signer: DeviceSigner,
        chain: VerifiedChain,
        kind: DocKind,
        version: Long,
    ) = DocSealer.seal(signer, chain, kind, version, null, now, "x".encodeToByteArray())

    private fun put(
        s: HouseholdStore,
        signer: RelayTestDevice,
        chain: VerifiedChain,
        expect: Long,
        kind: StateKind = StateKind.Cursor,
    ) = s.put(signer.deviceId, RelayV2.StatePut("r$expect", kind, expect, doc(signer, chain, if (kind == StateKind.Cursor) DocKind.Cursor else DocKind.Queue, expect + 1)))

    private val claimId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun onlyBootstrapCreatesAndOnlyMembersAuthenticate() {
        val s = store()
        assertNull(s.authKey(b.deviceId))
        assertEquals(HouseholdStore.ChainResult.Rejected("not_bootstrap"), s.createHousehold(b.deviceId, MemberChain.genesis(b, b.keys)))
        val (withHousehold, _) = household()
        assertNull(withHousehold.authKey(RelayTestDevice().deviceId))
        assertEquals(b.identityPub.toList(), WaveCrypto.encodePublicKey(withHousehold.authKey(b.deviceId)!!).toList())
    }

    @Test
    fun storeIdSurvivesRestartAndUnreadableFileStopsStartup() {
        val (s, chain) = household()
        s.claim(a.deviceId, claimId, doc(a, chain, DocKind.Command, 1))
        assertIs<HouseholdStore.PutResult.Ok>(put(s, a, chain, 0))
        val restarted = store()
        assertEquals(s.storeId, restarted.storeId)
        assertEquals(1L, restarted.latest().single().version)
        file.writeText("{broken")
        assertFailsWith<Exception> { store() }
    }

    @Test
    fun writesNeedTheActiveDeviceAndACurrentHeader() {
        val (s, chain) = household()
        assertEquals(HouseholdStore.PutResult.Rejected("not_active"), put(s, a, chain, 0))
        assertIs<HouseholdStore.ClaimResult.Ok>(s.claim(a.deviceId, claimId, doc(a, chain, DocKind.Command, 1)))
        assertIs<HouseholdStore.PutResult.Ok>(put(s, a, chain, 0))
        // 版の CAS: 同じ expect をもう一度（返事が落ちて送り直した）→ 最新を返す
        val conflict = assertIs<HouseholdStore.PutResult.Conflict>(put(s, a, chain, 0))
        assertEquals(1L, conflict.latest?.version)
        // 見出しの書き手が送り手と違う、版が expect+1 でない、古い一覧
        assertEquals(HouseholdStore.PutResult.Rejected("writer_mismatch"), s.put(a.deviceId, RelayV2.StatePut("x", StateKind.Cursor, 1, doc(b, chain, DocKind.Cursor, 2))))
        assertEquals(HouseholdStore.PutResult.Rejected("version"), s.put(a.deviceId, RelayV2.StatePut("x", StateKind.Cursor, 1, doc(a, chain, DocKind.Cursor, 5))))
        val older = verified(s.chainFrom(0).take(2))
        assertEquals(HouseholdStore.PutResult.Rejected("stale_head"), s.put(a.deviceId, RelayV2.StatePut("x", StateKind.Cursor, 1, doc(a, older, DocKind.Cursor, 2))))
        // controller は書けない・claim できない
        assertEquals(HouseholdStore.ClaimResult.Rejected("not_player"), s.claim(pc.deviceId, claimId, doc(pc, chain, DocKind.Command, 1)))
        // キューは別の版
        assertIs<HouseholdStore.PutResult.Ok>(put(s, a, chain, 0, StateKind.Queue))
    }

    @Test
    fun claimMovesActiveInArrivalOrderWithoutCas() {
        val (s, chain) = household()
        val first = assertIs<HouseholdStore.ClaimResult.Ok>(s.claim(a.deviceId, claimId, doc(a, chain, DocKind.Command, 1))).activeChanged
        val second = assertIs<HouseholdStore.ClaimResult.Ok>(s.claim(b.deviceId, "1f8fad5b-d9cb-469f-a165-70867728950e", doc(b, chain, DocKind.Command, 1))).activeChanged
        assertEquals(b.deviceId, second.activeDeviceId)
        assertEquals(first.claimOrder + 1, second.claimOrder)
        assertEquals(HouseholdStore.PutResult.Rejected("not_active"), put(s, a, chain, 0))
        // yield の doc が送り手のものでなければ断る
        assertEquals(HouseholdStore.ClaimResult.Rejected("bad_command"), s.claim(a.deviceId, claimId, doc(b, chain, DocKind.Command, 1)))
    }

    @Test
    fun transferConfirmsOnTheFirstCursorOrFallsBackToThePreviousActive() {
        val (s, chain) = household()
        s.claim(a.deviceId, claimId, doc(a, chain, DocKind.Command, 1))
        assertIs<HouseholdStore.PutResult.Ok>(put(s, a, chain, 0))
        // 版が進んでいたら断って最新を返す
        assertIs<HouseholdStore.ClaimResult.Conflict>(s.transfer(b.deviceId, "2f8fad5b-d9cb-469f-a165-70867728950e", 0, doc(b, chain, DocKind.Command, 1)))
        val transfer = assertIs<HouseholdStore.ClaimResult.Ok>(s.transfer(b.deviceId, "2f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(b, chain, DocKind.Command, 1))).activeChanged
        assertEquals(a.deviceId, transfer.activeDeviceId)
        assertEquals(b.deviceId, transfer.pendingOwner)
        // 前の再生中の端末（A）の最後のカーソルを CAS で受ける。関係ない端末の yielded は断る
        assertEquals(HouseholdStore.PutResult.Rejected("not_pending_previous"), s.yielded(b.deviceId, RelayV2.Yielded("2f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(b, chain, DocKind.Cursor, 2))))
        assertIs<HouseholdStore.PutResult.Ok>(s.yielded(a.deviceId, RelayV2.Yielded("2f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(a, chain, DocKind.Cursor, 2))))
        // 遅れた2通目の yielded は CAS で負ける
        assertIs<HouseholdStore.PutResult.Conflict>(s.yielded(a.deviceId, RelayV2.Yielded("2f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(a, chain, DocKind.Cursor, 2))))
        // B の最初のカーソルで確定
        val confirmed = assertIs<HouseholdStore.PutResult.Ok>(put(s, b, chain, 2))
        assertEquals(b.deviceId, confirmed.activeChanged?.activeDeviceId)
        assertEquals(b.deviceId, s.welcome(b.deviceId, emptyList()).activeDeviceId)

        // 次の transfer は、B が切れたら前の再生中の端末（B→A の方向では B）に戻る
        s.transfer(a.deviceId, "3f8fad5b-d9cb-469f-a165-70867728950e", 3, doc(a, chain, DocKind.Command, 1))
        assertNull(s.expirePending())
        val (expiredId, back) = assertIs<Pair<String, RelayV2.ActiveChanged>>(s.expirePending(disconnected = a.deviceId))
        assertEquals("3f8fad5b-d9cb-469f-a165-70867728950e", expiredId)
        assertEquals(b.deviceId, back.activeDeviceId)
        // 期限切れ
        s.transfer(a.deviceId, "4f8fad5b-d9cb-469f-a165-70867728950e", 3, doc(a, chain, DocKind.Command, 1))
        now += HouseholdStore.PENDING_MS + 1
        assertEquals("4f8fad5b-d9cb-469f-a165-70867728950e", s.expirePending()?.first)
        // 別の端末の release は受けない
        s.transfer(a.deviceId, "5f8fad5b-d9cb-469f-a165-70867728950e", 3, doc(a, chain, DocKind.Command, 1))
        assertNull(s.release(b.deviceId, "5f8fad5b-d9cb-469f-a165-70867728950e"))
        assertEquals(b.deviceId, s.release(a.deviceId, "5f8fad5b-d9cb-469f-a165-70867728950e")?.activeDeviceId)
    }

    @Test
    fun claimOrdersAndLatestStatesArriveInWelcomeAndSurviveRestart() {
        val (s, chain) = household()
        s.claim(a.deviceId, claimId, doc(a, chain, DocKind.Command, 1))
        assertIs<HouseholdStore.PutResult.Ok>(put(s, a, chain, 0))
        s.claim(b.deviceId, "1f8fad5b-d9cb-469f-a165-70867728950e", doc(b, chain, DocKind.Command, 1))
        val welcome = store().welcome(pc.deviceId, emptyList())
        assertEquals(mapOf(a.deviceId to 1L, b.deviceId to 2L), welcome.claimOrders)
        assertEquals(1, welcome.states.size)
        assertEquals(b.deviceId, welcome.activeDeviceId)
        // claim が transfer の途中を捨てたら、呼ぶ側が知らせられる
        s.transfer(a.deviceId, "2f8fad5b-d9cb-469f-a165-70867728950e", 1, doc(a, chain, DocKind.Command, 1))
        assertNull(s.takeDroppedPending())
        s.claim(b.deviceId, "3f8fad5b-d9cb-469f-a165-70867728950e", doc(b, chain, DocKind.Command, 1))
        assertEquals("2f8fad5b-d9cb-469f-a165-70867728950e", s.takeDroppedPending())
        assertNull(s.takeDroppedPending())
    }

    @Test
    fun chainAppendIsLinearAndRemovalClearsActive() {
        val (s, chain) = household()
        s.claim(b.deviceId, claimId, doc(b, chain, DocKind.Command, 1))
        // 同じ頭に2つ積もうとすると、2つ目は Conflict
        val removePc = MemberChain.remove(a, chain, pc.deviceId)
        val addOther = MemberChain.add(b, chain, RelayTestDevice().keys)
        assertIs<HouseholdStore.ChainResult.Ok>(s.append(a.deviceId, removePc))
        assertIs<HouseholdStore.ChainResult.Conflict>(s.append(b.deviceId, addOther))
        // 外された端末は認証できない
        assertNull(s.authKey(pc.deviceId))
        // 再生中の端末を外すと空になる
        val afterPc = verified(s.chainFrom(0))
        assertIs<HouseholdStore.ChainResult.Ok>(s.append(a.deviceId, MemberChain.remove(a, afterPc, b.deviceId)))
        assertNull(s.welcome(a.deviceId, emptyList()).activeDeviceId)
        // 家の外の署名は断る
        assertEquals(HouseholdStore.ChainResult.Rejected("not_member"), s.append(b.deviceId, MemberChain.add(b, verified(s.chainFrom(0)), RelayTestDevice().keys)))
        assertNotEquals(chain.headHash, s.welcome(a.deviceId, emptyList()).headHash)
    }
}
