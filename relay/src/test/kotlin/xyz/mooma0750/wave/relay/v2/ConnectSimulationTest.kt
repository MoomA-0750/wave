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
        // A は家を作ると押した端末、ほかは A の Confirm の床（genesis の頭）で加入を待つ端末（最初の鎖を無条件に信じない）
        a.client.beginCreateHousehold()
        val genesisInfo = ConnectClient.JoinInfo(MemberChain.hash(genesis), 0, MemberChain.hash(genesis), relay.store.storeId, 0, 0)
        (listOf(b) + extra).forEach { it.client.expectJoin(genesisInfo, a.deviceId) }
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
    fun stateRelabeledWithAnotherVersionIsIgnored() {
        setUp()
        a.startPlaying("queue-A", position = 1_000)
        relay.run()
        val real = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        // A の本物の書き込みを2つ作る
        a.positionMs = 2_000
        a.pushCursor()
        relay.run()
        val second = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        // 中継が、B に「前の版の番号で、後の doc」を見せる → 見出しの版と合わないので捨てる
        relay.inject(b.deviceId, RelayV2.State(second.copy(version = real.version)))
        relay.run()
        assertEquals(2_000L, b.client.latestCursorBody()?.second?.positionMs)
    }

    @Test
    fun resetStoreServingOldStateIsIgnoredUntilAccepted() {
        setUp()
        a.startPlaying("queue-A", position = 1_000)
        relay.run()
        a.positionMs = 50_000
        a.pushCursor()
        relay.run()
        val old = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Cursor }
        // 中継が保存を作り直し（storeId が変わる）、古い状態を最新として見せる
        b.disconnect()
        relay.file.delete()
        relay.store = HouseholdStore(relay.file, listOf(a.publicKeyB64), { now })
        b.connect()
        relay.inject(b.deviceId, RelayV2.State(old.copy(storeId = relay.store.storeId)))
        relay.run()
        assertTrue(b.events.any { it is ClientEvent.StoreReset })
        // 受け入れるまで、その状態を表示にも取り込みにも使わない
        assertEquals(50_000L, b.client.latestCursorBody()?.second?.positionMs)
        assertFalse(b.client.requestTransfer())
    }

    @Test
    fun takeOverFromAPlayingDeviceStartsAtItsCurrentPositionNotItsLastEvent() {
        setUp()
        a.startPlaying("queue-A", item = "item-2", position = 0)
        relay.run()
        // A はカーソルを1回だけ書いて3分鳴り続ける（その間の出来事は無い）
        now += 180_000
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertTrue(b.playing)
        assertTrue(b.positionMs in 180_000..181_000, "最後の出来事の位置から始まった: ${b.positionMs}")
    }

    @Test
    fun playbackStartedBeforeTheAccountTagExistsIsClaimedLater() {
        setUp()
        b.startPlaying("queue-B")
        relay.run()
        // A はまだ Verified でない（タグが無い）うちに再生を始めた
        a.accountTag = null
        a.startPlaying("queue-A")
        relay.run()
        assertFalse(a.client.isWriter)
        a.accountTag = "tag-account-1"
        a.client.onAccountTagAvailable()
        relay.run()
        assertTrue(a.client.isWriter, "タグができても claim しなかった")
        assertTrue(a.playing)
        assertFalse(b.playing)
    }

    @Test
    fun switchingAccountsClearsTheLocalQueueSoItIsNotWrittenWithTheNewTag() {
        setUp()
        a.startPlaying("queue-account-1")
        relay.run()
        val before = relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Queue }.version
        // ログアウトして別のアカウントでログインし、つながり直す
        a.disconnect()
        a.client.clearLocal()
        a.accountTag = "tag-account-2"
        a.connect()
        relay.run()
        assertFalse(a.client.isWriter)
        assertEquals(before, relay.store.latest().single { it.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Queue }.version, "前のアカウントのキューを書き直した")
    }

    @Test
    fun deviceNotExpectingAHouseholdRejectsARelayMadeHousehold() {
        setUp()
        // 中継が自分の鍵で家を作り、家に入っていない端末 D を加えた鎖を見せる
        val attacker = SimDevice("X", { relay }, { now })
        val d = SimDevice("D", { relay }, { now })
        val genesis = MemberChain.genesis(attacker, attacker.keys)
        val add = MemberChain.add(attacker, (MemberChain.verify(listOf(genesis)) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain, d.keys)
        d.client.onMessage(RelayV2.Chain(listOf(genesis, add)))
        assertEquals(null, d.client.householdId)
        assertTrue(d.events.any { it is ClientEvent.ChainInvalid })
    }

    @Test
    fun transferInterruptedByDisconnectIsDroppedNotAdoptedLater() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        relay.hold = { to, message -> to == a.deviceId && message is RelayV2.YieldDelivery }
        assertTrue(b.client.requestTransfer())
        relay.run()
        b.disconnect()
        assertTrue(b.events.any { it is ClientEvent.TransferFailed && it.reason == "disconnected" })
        now += 300_000
        b.client.tick()
        b.connect()
        relay.run()
        assertFalse(b.playing, "つながり直した後に古い状態から取り込んだ")
        assertTrue(b.client.requestTransfer(), "次の「ここで再生」が効かない")
    }

    @Test
    fun joiningDeviceAcceptsTheChainOnlyAfterItsInviterAddsIt() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        val c = SimDevice("C", { relay }, { now })
        c.client.expectJoin(a.client.joinInfo()!!, a.deviceId)
        // A の追記の前に取った鎖: まだ加えられていないので、受け入れずに待つ
        c.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertEquals(null, c.client.householdId)
        assertFalse(c.events.any { it is ClientEvent.Joined || it is ClientEvent.ChainInvalid })
        // B（招いた端末でない）が C を加えた鎖は、加入として受け入れない
        val chain = a.client.verifiedChain()!!
        relay.store.append(b.deviceId, MemberChain.add(b, chain, c.keys))
        c.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertEquals(null, c.client.householdId, "招いた端末でない端末の add で加入した")
        // 招いた A が加えた鎖なら受け入れる
        val c2 = SimDevice("C2", { relay }, { now })
        c2.client.expectJoin(a.client.joinInfo()!!, a.deviceId)
        relay.run()
        val latest = (MemberChain.verify(relay.store.chainFrom(0)) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain
        relay.store.append(a.deviceId, MemberChain.add(a, latest, c2.keys))
        c2.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertEquals(a.client.householdId, c2.client.householdId)
        assertTrue(c2.events.contains(ClientEvent.Joined))
    }

    @Test
    fun remotePlayingNowNeedsTheWriterToBeConnected() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        assertTrue(b.client.remotePlayingNow())
        // 別のアカウントの再生は「他の端末で鳴っている」に数えない
        b.accountTag = "tag-account-2"
        assertFalse(b.client.remotePlayingNow())
        b.accountTag = "tag-account-1"
        // A が落ちた（最後のカーソルは再生中のまま）: 再生ボタンを止め続けない
        a.disconnect()
        relay.run()
        assertFalse(b.client.remotePlayingNow(), "つながっていない端末の最後の「再生中」で鳴っている扱いにした")
        a.connect()
        relay.run()
        assertTrue(b.client.remotePlayingNow())
    }

    @Test
    fun remoteCursorFloorIsPersistedAtMostEveryThirtySeconds() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        advance(31_000)
        val before = b.persistCount
        // A がシークを続ける（カーソルが10回届く）
        repeat(10) {
            a.positionMs += 1_000
            a.pushCursor()
            relay.run()
        }
        assertTrue(b.persistCount - before <= 1, "他の端末のカーソルのたびに保存した: ${b.persistCount - before}")
        val floor = b.persisted.cursorFloor
        // 時間が経てば、上がった床を保存する
        advance(31_000)
        assertTrue(b.persisted.cursorFloor > floor, "上がった床が保存されない")
    }

    /** 朝の2台の確認と同じ順番（家を作る→加わる→再生→引き取り→引き取られた端末で曲を選ぶ→切れて戻る）。 */
    @Test
    fun theTwoDeviceWalkthroughInOrder() {
        setUp()
        // 1. A で再生して3分たつ
        a.startPlaying("queue-A", item = "item-3")
        relay.run()
        advance(180_000)
        assertEquals(a.deviceId, b.client.activeDeviceId)
        // 2. B が「ここで再生」→ 同じ曲の3分から続く（最後の出来事の位置ではない）
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertTrue(b.playing)
        assertFalse(a.playing)
        assertEquals("item-3", b.currentItemId)
        assertTrue(b.positionMs >= 180_000, "位置が続いていない: ${b.positionMs}")
        assertTrue(a.client.hasRetreated)
        // 3. 引き取られた A で曲を選ぶ → A が鳴り、B が止まる（相手のキューに置き換わらない）
        a.startPlaying("queue-A2", item = "item-9")
        relay.run()
        assertTrue(a.playing)
        assertFalse(b.playing)
        assertTrue(b.stoppedBy(a))
        assertEquals("queue-A2", a.queue)
        assertTrue(b.client.hasRetreated)
        // 4. B が通信を失うと、B はもう「最新の状態をそろえた」とは言わない（古い状態で判断しない）
        b.disconnect()
        assertFalse(b.client.stateSettled)
        // 5. つながり直すと、A が鳴っていることが分かる（裏の再生ボタンは鳴らさない側に倒れる）
        b.connect()
        relay.run()
        assertTrue(b.client.stateSettled)
        assertTrue(b.client.remotePlayingNow())
        // 6. B が前面で引き取り直せる
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertTrue(b.playing)
        assertFalse(a.playing)
        assertEquals("queue-A2", b.queue)
        assertEquals("item-9", b.currentItemId)
    }

    @Test
    fun stateIsSettledOnlyAfterTheWelcomeOfTheCurrentConnection() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        assertTrue(b.client.stateSettled)
        b.disconnect()
        // メモリに A のカーソルが残っていても、今の接続でそろえていない
        assertTrue(b.client.latestCursorBody() != null)
        assertFalse(b.client.stateSettled, "切れた後も最新の状態をそろえた扱いにした")
        b.connect()
        relay.run()
        assertTrue(b.client.stateSettled)
    }

    @Test
    fun welcomeDoesNotDropATransferSentJustBefore() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        relay.hold = { to, message -> to == a.deviceId && message is RelayV2.YieldDelivery }
        assertTrue(b.client.requestTransfer())
        relay.run()
        // つながり直した直後の welcome の処理（RelayClientV2 は welcome の前に Connected にする）
        b.client.onMessage(relay.store.welcome(b.deviceId, listOf(a.deviceId, b.deviceId)))
        relay.run()
        assertFalse(b.events.any { it is ClientEvent.TransferFailed }, "welcome の処理で transfer を捨てた")
        relay.hold = { _, _ -> false }
        relay.releaseHeld()
        relay.run()
        assertTrue(b.playing, "引き取れない")
    }

    @Test
    fun endingLocalPlaybackWritesAPausedCursorWithTheFinalTag() {
        setUp()
        a.startPlaying("queue-A")
        relay.run()
        assertTrue(b.client.remotePlayingNow())
        // A でログインが切り替わった（今のタグはもう使えない）
        a.accountTag = null
        a.client.endLocal("tag-account-1")
        relay.run()
        assertFalse(a.client.isWriter)
        assertFalse(b.client.remotePlayingNow(), "中継に「再生中」が残った")
        assertEquals(false, b.client.latestCursorBody()?.second?.playing)
    }

    @Test
    fun yieldedWithoutACapturedPositionStillAddsTheElapsedTime() {
        setUp()
        a.startPlaying("queue-A", position = 10_000)
        relay.run()
        a.pauseCapturesPosition = false
        now += 60_000
        assertTrue(b.client.requestTransfer())
        relay.run()
        assertTrue(b.playing)
        assertTrue(b.positionMs >= 70_000, "位置が進んでいない: ${b.positionMs}")
    }

    @Test
    fun joinWaitSurvivesAProcessRestartAndCanBeCancelled() {
        setUp()
        val c = SimDevice("C", { relay }, { now })
        c.client.expectJoin(a.client.joinInfo()!!, a.deviceId)
        c.restart()
        val chain = a.client.verifiedChain()!!
        relay.store.append(a.deviceId, MemberChain.add(a, chain, c.keys))
        c.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertTrue(c.events.contains(ClientEvent.Joined), "起き直した後に加入を受け入れない")
        // 取り下げた待ちでは、後で鎖を受けても黙って家に入らない
        val d = SimDevice("D", { relay }, { now })
        d.client.expectJoin(a.client.joinInfo()!!, a.deviceId)
        d.client.cancelJoin()
        d.restart()
        relay.store.append(a.deviceId, MemberChain.add(a, a.client.verifiedChain()!!.let { (MemberChain.verify(relay.store.chainFrom(0)) as xyz.mooma0750.wave.handoff.v2.ChainVerification.Ok).chain }, d.keys))
        d.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertEquals(null, d.client.householdId)
    }

    @Test
    fun creatingDeviceRejectsSomeoneElsesGenesis() {
        setUp()
        val c = SimDevice("C", { relay }, { now })
        c.client.beginCreateHousehold()
        c.restart()
        // A の genesis（自分のではない）
        c.client.onMessage(RelayV2.Chain(relay.store.chainFrom(0)))
        assertEquals(null, c.client.householdId)
        assertTrue(c.events.any { it is ClientEvent.ChainInvalid })
    }

    @Test
    fun deviceWhoseStorageWasResetCanJoinAgainWithoutANewAdd() {
        setUp()
        // B の保存を作り直した（同じ鍵のまま、家の記録だけ無い）
        b.disconnect()
        b.restart(xyz.mooma0750.wave.handoff.v2.ClientPersisted())
        // 加入の手順をやり直す: A は B が既にいるので add を送らない、B は A の頭の鎖に自分がいれば受け入れる
        b.client.expectJoin(a.client.joinInfo()!!, a.deviceId)
        b.connect()
        relay.run()
        assertEquals(a.client.householdId, b.client.householdId)
        assertTrue(b.events.contains(ClientEvent.Joined))
    }

    @Test
    fun sameQueueIsNotPutAgain() {
        setUp()
        var queuePuts = 0
        relay.dropIncoming = { from, message ->
            if (from == a.deviceId && message is RelayV2.StatePut && message.kind == xyz.mooma0750.wave.handoff.v2.StateKind.Queue) queuePuts++
            false
        }
        a.startPlaying("queue-A")
        relay.run()
        assertEquals(1, queuePuts)
        a.client.setLocalQueue("queue-A")
        a.pushCursor()
        relay.run()
        assertEquals(1, queuePuts, "中身が同じキューを置き直した")
        a.client.setLocalQueue("queue-A2")
        relay.run()
        assertEquals(2, queuePuts)
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
