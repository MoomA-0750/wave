import Foundation
import XCTest

@testable import WaveCore

/// 端末なしの多端末テスト（Kotlin の ConnectSimulationTest の主要な筋）。偽の中継と ConnectClient を、決まった順の配送でつなぐ。
final class ConnectSimulationTests: XCTestCase {
    private var now: Int64 = 1_000_000
    private var relay: FakeRelay!
    private var a: SimDevice!
    private var b: SimDevice!
    private let snapshot = HandoffCodec.encode(InteropTests.sampleSnapshot())

    override func setUp() {
        relay = FakeRelay(nowMs: { [unowned self] in self.now })
        a = SimDevice("A", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now })
        b = SimDevice("B", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now })
    }

    private func joined(_ device: SimDevice) -> Bool { device.events.contains(.joined) }

    /// A が家を作り、B がペアリング（拡張で鍵の記述と床を渡す）で加わる。
    private func setUpHousehold() throws {
        a.client.beginCreateHousehold()
        a.connect()
        try relay.run()
        XCTAssertTrue(relay.fromClient(a.deviceId, .householdCreate(.init(genesis: try MemberChain.genesis(signer: a.keys, keys: a.memberKeys)))))
        try relay.run()
        XCTAssertTrue(joined(a))
        XCTAssertNotNil(a.client.householdId)

        let responder = Pairing.Responder(
            identity: a.keys, deviceName: "A", accountId: "account-1", nowMs: { [unowned self] in self.now },
            confirmExtension: ConnectJSON.encode(try XCTUnwrap(a.client.currentJoinInfo())))
        let initiator = try Pairing.Initiator(
            offer: try XCTUnwrap(Pairing.decodeOffer(Pairing.encodeOffer(responder.offer))), identity: b.keys, deviceName: "B", accountId: "account-1",
            helloExtension: ConnectJSON.encode(b.memberKeys))
        let confirm = try responder.onHello(try initiator.hello()).get()
        let confirmed = try initiator.onConfirm(confirm).get()
        let onA = try responder.onCodeEntered(confirmed.code).get()
        // A: B を家に加える
        let memberKeys = try XCTUnwrap(ConnectJSON.decode(MemberKeys.self, from: try XCTUnwrap(onA.peerExtension)))
        XCTAssertTrue(
            relay.fromClient(
                a.deviceId, .chainAppend(.init(record: try MemberChain.add(signer: a.keys, chain: try XCTUnwrap(a.client.verifiedChain()), target: memberKeys)))))
        try relay.run()
        // B: Confirm の床で加入を待ち、つなぐ
        b.client.expectJoin(
            try XCTUnwrap(ConnectJSON.decode(ConnectClient.JoinInfo.self, from: try XCTUnwrap(confirmed.pair.peerExtension))), inviterDeviceId: a.deviceId)
        b.connect()
        try relay.run()
        XCTAssertTrue(joined(b))
        XCTAssertEqual(a.client.householdId, b.client.householdId)
        XCTAssertEqual(2, a.client.members.count)
    }

    private func advance(_ ms: Int64) throws {
        now += ms
        relay.tick()
        try a.client.tick()
        try b.client.tick()
        try relay.run()
    }

    func testCreateJoinPlayTakeOverAndStop() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot, item: "00000000-0000-4000-8000-000000000001", position: 42_000)
        try relay.run()
        XCTAssertTrue(a.playing)
        XCTAssertTrue(a.client.isWriter)
        XCTAssertTrue(
            b.events.contains {
                if case .remoteChanged(let writer, let cursor, let matches) = $0 { return writer == a.deviceId && cursor.playing && matches }
                return false
            })
        XCTAssertEqual(a.deviceId, b.client.activeDeviceId)
        XCTAssertTrue(b.client.remotePlayingNow())
        // 表示用: B は A のキューを開ける
        let latest = try XCTUnwrap(b.client.latestQueueBody())
        XCTAssertEqual(a.deviceId, latest.writer)
        guard case .ok = HandoffCodec.decode(latest.queue.snapshot, expectedHandoffId: InteropTests.sampleSnapshot().handoffId) else {
            return XCTFail("キューが読めない")
        }
        XCTAssertEqual("00000000-0000-4000-8000-000000000001", b.client.latestCursorBody()?.cursor.currentItemId)

        // 少し進めてから B が引き取る
        a.positionMs = 50_000
        try a.pushCursor()
        try relay.run()
        XCTAssertTrue(try b.client.requestTransfer())
        try relay.run()
        XCTAssertFalse(a.playing, "引き取られた A が止まっていない")
        XCTAssertTrue(a.stoppedBy(b))
        XCTAssertTrue(b.playing)
        XCTAssertEqual(snapshot, b.queue)
        XCTAssertEqual("00000000-0000-4000-8000-000000000001", b.currentItemId)
        XCTAssertEqual(50_000, b.positionMs)
        XCTAssertTrue(b.events.contains(.transferCompleted(from: a.deviceId)))
        XCTAssertEqual(b.deviceId, a.client.activeDeviceId)
        XCTAssertTrue(b.client.isWriter)
        XCTAssertFalse(a.client.isWriter)

        // A でまた始めると B が止まる
        try a.startPlaying(snapshot)
        try relay.run()
        XCTAssertTrue(a.playing)
        XCTAssertFalse(b.playing)
        XCTAssertTrue(b.stoppedBy(a))
        XCTAssertEqual(a.deviceId, relay.activeDeviceId)
    }

    func testTakeOverFromAnOfflineDeviceUsesTheLastStateAfterThreeSeconds() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot, position: 10_000)
        try relay.run()
        // A が通信を失う（鳴り続けている）
        a.disconnect()
        now += 20_000
        XCTAssertTrue(try b.client.requestTransfer())
        try relay.run()
        XCTAssertFalse(b.playing)
        try advance(ConnectClient.transferWaitMs)
        XCTAssertTrue(b.playing)
        // 最後の状態は再生中だったので、サーバーの時計で経過を足す
        XCTAssertTrue(b.positionMs >= 10_000 + 20_000, "位置を外挿していない: \(b.positionMs)")
        XCTAssertEqual(b.deviceId, relay.activeDeviceId)
        // A がつながり直すと、B のカーソルを見て止まる
        XCTAssertTrue(a.playing)
        a.connect()
        try relay.run()
        XCTAssertFalse(a.playing, "つながり直した A が止まっていない")
        XCTAssertTrue(a.stoppedBy(b))
        XCTAssertTrue(b.playing)
    }

    func testConcurrentStartsLeaveOnlyTheLaterClaimPlaying() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        try a.startPlaying(snapshot + " ")
        try b.startPlaying(snapshot)
        try relay.run()
        XCTAssertTrue(b.playing, "claim が後に届いた B が止まった")
        XCTAssertFalse(a.playing)
        XCTAssertTrue(b.client.isWriter)
        XCTAssertFalse(a.client.isWriter)
    }

    func testAnotherAccountCannotStopTheHousehold() throws {
        try setUpHousehold()
        b.tag = "tag-account-2"
        try a.startPlaying(snapshot)
        try relay.run()
        try b.startPlaying(snapshot)
        try relay.run()
        XCTAssertFalse(b.client.isWriter)
        XCTAssertTrue(a.playing, "別のアカウントの端末の再生で止まった")
        XCTAssertTrue(
            b.events.contains {
                if case .remoteChanged(let writer, _, let matches) = $0 { return writer == a.deviceId && !matches }
                return false
            })
        XCTAssertFalse(try b.client.requestTransfer())
    }

    func testRestartFromPersistedJsonKeepsTheHousehold() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot, position: 5_000)
        try relay.run()
        // B のプロセスが落ちて、保存（JSON）から起き直す
        b.disconnect()
        try relay.run()
        b.restart()
        XCTAssertEqual(a.client.householdId, b.client.householdId)
        b.connect()
        try relay.run()
        XCTAssertTrue(b.client.stateSettled)
        XCTAssertFalse(
            b.events.contains {
                switch $0 {
                case .storeReset, .chainForked, .chainInvalid: return true
                default: return false
                }
            })
        XCTAssertTrue(try b.client.requestTransfer())
        try relay.run()
        XCTAssertTrue(b.playing)
        XCTAssertFalse(a.playing)
    }

    func testDeviceThatWasNotExpectingAHouseholdRejectsIt() throws {
        try setUpHousehold()
        // 加入も家を作るのも待っていない端末は、中継が見せた家を信じない
        let stranger = SimDevice("C", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now })
        stranger.connect()
        try relay.run()
        XCTAssertNil(stranger.client.householdId)
        XCTAssertTrue(stranger.events.contains(.chainInvalid(reason: "unexpected_household")))
    }

    func testRemoteControlReachesThePlayingDeviceOnce() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        XCTAssertTrue(try b.client.sendControl(.pause))
        try relay.run()
        XCTAssertFalse(a.playing, "遠隔の一時停止に従っていない")
        XCTAssertTrue(a.client.isWriter, "一時停止しても書き手のまま")
        try a.pushCursor()
        try relay.run()
        XCTAssertTrue(try b.client.sendControl(.seek, positionMs: 90_000))
        try relay.run()
        XCTAssertEqual(90_000, a.positionMs)
        XCTAssertEqual(2, a.controls.count)
        XCTAssertFalse(try a.client.sendControl(.play))
        XCTAssertNotNil(b.client.remoteNowPosition())
    }

    func testNextThatArrivesAfterTheSongAlreadyChangedIsIgnored() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        // 曲の終わりぎわに「次へ」を押した。届く前に、鳴らしている端末は自分で次の曲へ進んだ
        XCTAssertTrue(try b.client.sendControl(.next))
        a.currentItemId = "item-1"
        try a.pushCursor()
        try relay.run()
        XCTAssertTrue(a.controls.isEmpty)
        // 今の曲を見て押した「次へ」には従う
        XCTAssertTrue(try b.client.sendControl(.next))
        try relay.run()
        XCTAssertEqual(1, a.controls.count)
    }

    func testRemoteControlFromAnotherAccountOrReplayedIsIgnored() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        b.tag = "tag-account-2"
        XCTAssertFalse(try b.client.sendControl(.pause))
        b.tag = "tag-account-1"
        var captured: RelayV2?
        relay.onFromClient = { [b] from, message in
            if from == b!.deviceId, case .control = message { captured = message }
        }
        XCTAssertTrue(try b.client.sendControl(.pause))
        try relay.run()
        XCTAssertEqual(1, a.controls.count)
        relay.inject(a.deviceId, try XCTUnwrap(captured))
        try relay.run()
        XCTAssertEqual(1, a.controls.count)
    }

    func testRemoteControlHeldBackForMoreThanFiveMinutesIsIgnored() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        var held: RelayV2?
        relay.onFromClient = { [b] from, message in
            if from == b!.deviceId, case .control = message { held = message }
        }
        a.playing = false
        try a.pushCursor()
        try relay.run()
        XCTAssertTrue(try b.client.sendControl(.play))
        // 中継が届けるのを6分遅らせる（送った分も差し込んだ分も、届くのは6分後）
        now += 6 * 60 * 1000
        relay.inject(a.deviceId, try XCTUnwrap(held))
        try relay.run()
        XCTAssertTrue(a.controls.isEmpty)
        XCTAssertFalse(a.playing)
    }

    func testChoosingAnotherDeviceMovesPlaybackThere() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        XCTAssertTrue(try a.client.requestRemoteTransfer(b.deviceId))
        try relay.run()
        XCTAssertTrue(b.playing, "選んだ B で鳴っていない")
        XCTAssertFalse(a.playing)
        XCTAssertTrue(b.client.isWriter)
    }

    func testChoosingASongWhileAnotherDevicePlaysPlaysItThere() throws {
        try setUpHousehold()
        try a.startPlaying(snapshot)
        try relay.run()
        var moved = InteropTests.sampleSnapshot()
        moved.currentItemId = moved.items[moved.items.count - 1].itemId
        let other = HandoffCodec.encode(moved)
        b.queue = other
        b.currentItemId = moved.currentItemId
        b.positionMs = 0
        try b.client.setLocalQueue(other)
        try b.pushCursor()
        XCTAssertTrue(try b.client.playOn(a.deviceId))
        try relay.run()
        XCTAssertTrue(a.playing, "A で鳴っていない")
        XCTAssertEqual(other, a.queue)
        XCTAssertFalse(b.playing)
        XCTAssertTrue(a.client.isWriter)
    }

    func testDeviceNamesReachTheWholeHousehold() throws {
        try setUpHousehold()
        XCTAssertTrue(try b.client.sendProfile("Bee"))
        try relay.run()
        XCTAssertTrue(a.events.contains(.profile(deviceId: b.deviceId, name: "Bee", kind: nil)))
    }
}
