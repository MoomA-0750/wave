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
    fun concurrentStartsLeaveExactlyOneDevicePlaying() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        // A を見た上で、B と A がほぼ同時に始め直す（どちらも相手の新しいセッションを見ていない）
        a.startPlaying("queue-A2")
        b.startPlaying("queue-B")
        relay.run()
        assertEquals(1, listOf(a, b).count { it.playing }, "A=${a.playing} B=${b.playing}")
    }

    @Test
    fun deviceClocksDoNotDecideWhoWins() {
        now = 5_000_000
        val skewed = SimDevice("C", { relay }, { now }, epochSkewMs = 3_600_000)
        setUp(skewed)
        // 時計が1時間進んだ C が先に始め、B が後から始める → B が勝つ
        skewed.startPlaying("queue-C")
        relay.run()
        b.startPlaying("queue-B")
        relay.run()
        assertTrue(b.playing)
        assertFalse(skewed.playing)
    }

    @Test
    fun anotherAccountCannotStopTheHousehold() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        b.accountTag = "tag-account-2"
        b.startPlaying("queue-other-account")
        relay.run()
        assertTrue(a.playing, "別のアカウントの端末の再生で止まった")
        assertTrue(a.events.any { it is ClientEvent.RemoteChanged && !it.accountMatches })
        // 別のアカウントの端末は引き取れない
        b.playing = false
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
