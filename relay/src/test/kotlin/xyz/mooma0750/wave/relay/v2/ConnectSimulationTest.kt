package xyz.mooma0750.wave.relay.v2

import xyz.mooma0750.wave.handoff.v2.ClientEvent
import xyz.mooma0750.wave.handoff.v2.ConnectClient
import xyz.mooma0750.wave.handoff.v2.MemberChain
import xyz.mooma0750.wave.handoff.v2.RelayV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 端末なしの多端末テスト（Phase 6b）。本物の家の状態と ConnectClient を、決まった順の配送でつなぐ。 */
class ConnectSimulationTest {
    private var now = 1_000_000L
    private lateinit var relay: FakeRelay
    private val a = SimDevice("A", { relay }, { now })
    private val b = SimDevice("B", { relay }, { now })

    private fun setUp(vararg extra: SimDevice) {
        relay = FakeRelay(listOf(a.publicKeyB64), nowMs = { now })
        val genesis = MemberChain.genesis(a, a.keys)
        relay.store.createHousehold(a.deviceId, genesis)
        var records = listOf(genesis)
        for (device in listOf(b) + extra) {
            val chain = (MemberChain.verify(records) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain
            val add = MemberChain.add(a, chain, device.keys)
            relay.store.append(a.deviceId, add)
            records = records + add
        }
        a.connect()
        b.connect()
        extra.forEach { it.connect() }
        relay.run()
    }

    private fun advance(ms: Long) {
        now += ms
        relay.tick()
        listOf(a, b).forEach { it.client.tick() }
        relay.run()
    }

    @Test
    fun takeOverFromAnOnlineDeviceContinuesAtItsPosition() {
        setUp()
        a.startPlaying("queue-A", item = "item-3", position = 42_000)
        relay.run()
        assertTrue(a.playing)
        assertTrue(b.events.any { it is ClientEvent.RemoteChanged && it.writer == a.deviceId && it.cursor.playing })
        assertEquals(a.deviceId, b.client.activeDeviceId)
        // 少し進めてから B が引き取る
        a.positionMs = 50_000
        a.pushCursor()
        relay.run()
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertFalse(a.playing, "引き取られた A が止まっていない")
        assertTrue(a.stoppedBy(b))
        assertTrue(b.playing)
        assertEquals("queue-A", b.queue)
        assertEquals("item-3", b.currentItemId)
        assertEquals(50_000L, b.positionMs)
        assertTrue(b.events.any { it is ClientEvent.TransferCompleted })
        assertEquals(b.deviceId, a.client.activeDeviceId)
        assertTrue(b.client.isWriter)
        assertFalse(a.client.isWriter)
    }

    @Test
    fun takeOverFromAnOfflineDeviceUsesTheLastStateAfterThreeSeconds() {
        setUp()
        a.startPlaying("queue-A", position = 10_000)
        relay.run()
        // A が通信を失う（鳴り続けている）
        a.disconnect()
        now += 20_000
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertFalse(b.playing)
        advance(ConnectClient.TRANSFER_WAIT_MS)
        assertTrue(b.playing)
        // 最後の状態は再生中だったので、サーバーの時計で経過を足す
        assertTrue(b.positionMs >= 10_000 + 20_000, "位置を外挿していない: ${b.positionMs}")
        assertEquals(b.deviceId, relay.store.welcome(b.deviceId, emptyList()).activeDeviceId)
        // A がつながり直すと、B のカーソルを見て止まる（L-4）
        assertTrue(a.playing)
        a.connect()
        relay.run()
        assertFalse(a.playing, "つながり直した A が止まっていない")
        assertTrue(a.stoppedBy(b))
        assertTrue(b.playing)
    }

    @Test
    fun startingPlaybackOnAnotherDeviceStopsThePreviousOne() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        b.startPlaying("queue-B")
        relay.run()
        assertTrue(b.playing)
        assertFalse(a.playing)
        assertTrue(a.stoppedBy(b))
        // A でまた始めると B が止まる
        a.startPlaying("queue-A2")
        relay.run()
        assertTrue(a.playing)
        assertFalse(b.playing)
    }

    @Test
    fun concurrentStartsLeaveOnlyTheLaterClaimPlaying() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        // A を見た上で、A と B がほぼ同時に始め直す（どちらも相手の新しいセッションを見ていない）。中継には A、B の順に届く
        a.startPlaying("queue-A2")
        b.startPlaying("queue-B")
        relay.run()
        assertTrue(b.playing, "claim が後に届いた B が止まった")
        assertFalse(a.playing)
        assertTrue(b.client.isWriter)
        assertFalse(a.client.isWriter)
    }

    @Test
    fun crossingClaimsAreDecidedByClaimOrderEvenWhenActiveChangedArrivesLate() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        // B への active_changed を遅らせる: B は A の yield を、自分と A の claimOrder を知る前に受ける
        relay.hold = { to, message -> to == b.deviceId && message is RelayV2.ActiveChanged }
        b.startPlaying("queue-B")
        a.startPlaying("queue-A2")
        relay.run()
        // 保留している間は、B は受けた順番で止まらない
        assertTrue(b.client.isWriter)
        relay.hold = { _, _ -> false }
        relay.releaseHeld()
        relay.run()
        // claim は B、A の順に届いたので、A が残る
        assertTrue(a.playing, "claim が後に届いた A が止まった: A=${a.events.filterNot { it is ClientEvent.RemoteChanged }} B=${b.events.filterNot { it is ClientEvent.RemoteChanged }} log=${relay.log}")
        assertFalse(b.playing)
        assertTrue(a.client.isWriter)
    }

    @Test
    fun anotherAccountCannotStopTheHousehold() {
        setUp()
        b.accountTag = "tag-account-2"
        a.startPlaying("queue-A")
        relay.run()
        // 家の最新の再生が別のアカウントなので、B は claim しない（手元だけで鳴る）
        b.startPlaying("queue-other-account")
        relay.run()
        assertFalse(b.client.isWriter)
        assertTrue(a.playing, "別のアカウントの端末の再生で止まった")
        assertTrue(a.client.isWriter)
        assertTrue(b.events.any { it is ClientEvent.RemoteChanged && it.writer == a.deviceId && !it.accountMatches })
        // 別のアカウントの端末は、A の再生を引き取れない（最新のカーソルは A のもの）
        assertEquals(a.deviceId, b.client.latestCursorBody()?.first)
        assertFalse(b.client.requestTransfer())
    }

    @Test
    fun serverWithholdingTheYieldStillStopsThePreviousDeviceViaTheCursor() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        relay.drop = { to, message -> to == a.deviceId && message is RelayV2.YieldDelivery }
        b.startPlaying("queue-B")
        relay.run()
        assertFalse(a.playing)
        assertTrue(b.playing)
    }

    @Test
    fun lateYieldedAfterTheThreeSecondFallbackDoesNotStopTheNewDevice() {
        setUp()
        a.startPlaying("queue-A", position = 1_000)
        relay.run()
        // 中継が A へのメッセージを3秒より遅らせる（A は yield を受けられず、鳴り続けている）
        relay.hold = { to, _ -> to == a.deviceId }
        assertTrue(b.client.requestTransfer())
        relay.run()
        advance(ConnectClient.TRANSFER_WAIT_MS)
        assertTrue(b.playing)
        assertTrue(a.playing)
        assertEquals(b.deviceId, relay.store.welcome(b.deviceId, emptyList()).activeDeviceId)
        // 遅れて A に届く: transfer の途中 → yield → B のカーソル → 確定、の順。A は yield で止まり、
        // yielded を送るが、transfer はもう確定しているので中継に断られる（B は止まらない）
        relay.hold = { _, _ -> false }
        relay.releaseHeld()
        relay.run()
        assertFalse(a.playing)
        assertTrue(a.stoppedBy(b))
        assertTrue(a.events.any { it is ClientEvent.ServerError && it.code == "no_pending" }, "遅れた yielded を送っていない: ${a.events}")
        assertTrue(b.playing, "遅れた yielded で B が止まった")
        assertEquals(b.deviceId, relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }.doc.header.writerId)
    }

    @Test
    fun removingADeviceMakesTheWriterRewrapWithoutIt() {
        val c = SimDevice("C", { relay }, { now })
        setUp(c)
        a.startPlaying("queue-A")
        relay.run()
        val before = relay.store.latest().first().doc.recipients.map { it.deviceId }.toSet()
        assertTrue(c.deviceId in before)
        // B が C を外す → A は chain_changed で鎖を取り直し、C を宛先から外して書き直す
        val records = relay.store.chainFrom(0)
        val chain = (MemberChain.verify(records) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain
        b.client.let { relay.fromClient(b.deviceId, RelayV2.ChainAppend(MemberChain.remove(b, chain, c.deviceId))) }
        relay.run()
        val after = relay.store.latest().filter { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }.single().doc.recipients.map { it.deviceId }.toSet()
        assertFalse(c.deviceId in after, "外した端末に包んだままになっている")
        assertTrue(a.deviceId in after && b.deviceId in after)
    }

    @Test
    fun joiningDeviceAcceptsOnlyTheInvitersHouseholdAndItsOwnAdd() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        // C は家の外。A の Confirm（6桁で確かめた通信路）で床を受け取った体で、加入を待つ
        val c = SimDevice("C", { relay }, { now })
        val info = requireNotNull(a.client.joinInfo())
        c.client.expectJoin(info, inviterDeviceId = a.deviceId)
        // A が C を加える前に C がつながれない（中継は家の端末だけを認証する）ので、A が先に加える
        relay.fromClient(a.deviceId, RelayV2.ChainAppend(MemberChain.add(a, requireNotNull(a.client.verifiedChain()), c.keys)))
        relay.run()
        c.connect()
        relay.run()
        assertEquals(a.client.householdId, c.client.householdId)
        // 家の状態を読め、引き取れる
        assertTrue(c.events.any { it is ClientEvent.RemoteChanged && it.writer == a.deviceId })
        assertTrue(c.client.requestTransfer())
        relay.run()
        assertTrue(c.playing)
        assertFalse(a.playing)
    }

    @Test
    fun joiningDeviceRejectsAnotherHouseholdOrAnAddByAnotherSigner() {
        setUp()
        val info = requireNotNull(a.client.joinInfo())
        // B が C を加えた（A の Confirm の相手ではない署名者）→ C は受け入れない
        val c = SimDevice("C", { relay }, { now })
        c.client.expectJoin(info, inviterDeviceId = a.deviceId)
        relay.fromClient(b.deviceId, RelayV2.ChainAppend(MemberChain.add(b, requireNotNull(b.client.verifiedChain()), c.keys)))
        relay.run()
        c.connect()
        relay.run()
        assertEquals(null, c.client.householdId)
        // 別の家の ID を名乗る Confirm → 分岐として知らせる
        val d = SimDevice("D", { relay }, { now })
        d.client.expectJoin(info.copy(householdId = "another-household"), inviterDeviceId = a.deviceId)
        relay.fromClient(a.deviceId, RelayV2.ChainAppend(MemberChain.add(a, requireNotNull(a.client.verifiedChain()), d.keys)))
        relay.run()
        d.connect()
        relay.run()
        assertEquals(null, d.client.householdId)
    }

    @Test
    fun writesResumeAfterReconnecting() {
        setUp()
        a.startPlaying("queue-A", position = 1_000)
        relay.run()
        val before = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }.version
        // 切れている間に位置が変わる（送れない）
        a.disconnect()
        a.positionMs = 90_000
        a.pushCursor()
        relay.run()
        a.connect()
        relay.run()
        val after = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        assertTrue(after.version > before, "つながり直しても書き直していない")
        assertEquals(a.deviceId, after.doc.header.writerId)
        // B から見て、A の新しい位置
        assertEquals(90_000L, b.client.latestCursorBody()?.second?.positionMs)
        // その後の変化もすぐ書ける
        a.positionMs = 95_000
        a.pushCursor()
        relay.run()
        assertEquals(95_000L, b.client.latestCursorBody()?.second?.positionMs)
    }

    @Test
    fun playbackStartedOfflineIsClaimedOnReconnect() {
        setUp()
        b.startPlaying("queue-B")
        relay.run()
        // A は切れている間に、本人が新しく再生を始めた（claim は届かない）
        a.disconnect()
        a.startPlaying("queue-A-offline")
        relay.run()
        assertTrue(b.playing)
        a.connect()
        relay.run()
        // つながった後に claim が届き、後の claim の A が残る
        assertTrue(a.playing, "オフラインで始めた A が止まった")
        assertFalse(b.playing)
        assertEquals(a.deviceId, relay.store.welcome(a.deviceId, emptyList()).activeDeviceId)
    }

    @Test
    fun adoptingDeviceWhoseFirstWriteMissesTheDeadlineClaimsAgain() {
        setUp()
        a.startPlaying("queue-A", position = 5_000)
        relay.run()
        // B の最初の書き込みが中継に届かないまま、transfer の期限を過ぎる
        relay.dropIncoming = { from, message -> from == b.deviceId && message is RelayV2.StatePut }
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertTrue(b.playing)
        relay.dropIncoming = { _, _ -> false }
        now += HouseholdStore.PENDING_MS + 1
        relay.tick()
        relay.run()
        // B は鳴っているので、新しく始めたものとして claim し直し、書ける
        assertTrue(b.playing)
        assertTrue(b.client.isWriter)
        b.positionMs = 7_000
        b.pushCursor()
        relay.run()
        val cursor = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        assertEquals(b.deviceId, cursor.doc.header.writerId)
        assertEquals(b.deviceId, relay.store.welcome(b.deviceId, emptyList()).activeDeviceId)
        assertFalse(a.playing)
    }

    @Test
    fun staleHeadRejectionMakesTheWriterRewrapWithoutTheRemovedDevice() {
        val c = SimDevice("C", { relay }, { now })
        setUp(c)
        a.startPlaying("queue-A")
        relay.run()
        // A に鎖の更新を届けないまま、B が C を外す
        relay.drop = { to, message -> to == a.deviceId && message is RelayV2.ChainChanged }
        val chain = requireNotNull(b.client.verifiedChain())
        relay.fromClient(b.deviceId, RelayV2.ChainAppend(MemberChain.remove(b, chain, c.deviceId)))
        relay.run()
        relay.drop = { _, _ -> false }
        // A は古い頭で書こうとして stale_head で断られ、鎖を取り直して書き直す
        a.positionMs = 12_000
        a.pushCursor()
        relay.run()
        val cursor = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        assertEquals(12_000L, b.client.latestCursorBody()?.second?.positionMs)
        assertFalse(cursor.doc.recipients.any { it.deviceId == c.deviceId }, "外した端末に包んだまま")
    }

    @Test
    fun forkedChainAndStoreResetAreSurfacedAndNotActedOn() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        // サーバーが、同じ seq で別のハッシュを知らせる
        relay.inject(b.deviceId, RelayV2.ChainChanged(1, "forged-hash"))
        relay.run()
        assertTrue(b.events.contains(ClientEvent.ChainForked))
        // サーバーの保存を作り直す（storeId が変わる）
        b.disconnect()
        relay.file.delete()
        relay.store = HouseholdStore(relay.file, listOf(a.publicKeyB64), { now })
        b.connect()
        relay.run()
        assertTrue(b.events.any { it is ClientEvent.StoreReset })
        // 受け入れるまで引き取れない
        assertFalse(b.client.requestTransfer())
    }
}
