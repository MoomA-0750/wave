import Foundation
import XCTest

@testable import WaveCore

/// ライブラリの同期（ADR-0013）。Kotlin の LibrarySyncSimulationTest と同じ筋を、偽の中継と ConnectClient で確かめる。
final class LibrarySyncTests: XCTestCase {
    private var now: Int64 = 1_000_000
    private var relay: FakeRelay!
    private var a: SimDevice!
    private var b: SimDevice!
    private var pc: SimDevice!

    override func setUp() {
        relay = FakeRelay(nowMs: { [unowned self] in self.now })
        a = SimDevice("A", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now })
        b = SimDevice("B", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now })
        pc = SimDevice("PC", relay: { [unowned self] in self.relay }, clock: { [unowned self] in self.now }, role: .controller)
    }

    /// A が家を作り、B と PC を加える。connectPc が false なら PC はまだつながない。
    private func setUpHousehold(supportsLibrary: Bool = true, connectPc: Bool = false) throws {
        relay.supportsLibrary = supportsLibrary
        a.client.beginCreateHousehold()
        a.connect()
        try relay.run()
        XCTAssertTrue(relay.fromClient(a.deviceId, .householdCreate(.init(genesis: try MemberChain.genesis(signer: a.keys, keys: a.memberKeys)))))
        try relay.run()
        try join(b)
        try join(pc, connect: connectPc)
        // A は家を作る前につないだので、最初の library_get は家の外として断られている。家に入ったときに頼み直していること
    }

    private func join(_ device: SimDevice, connect: Bool = true) throws {
        let responder = Pairing.Responder(
            identity: a.keys, deviceName: "A", accountId: "account-1", nowMs: { [unowned self] in self.now },
            confirmExtension: ConnectJSON.encode(try XCTUnwrap(a.client.currentJoinInfo())))
        let initiator = try Pairing.Initiator(
            offer: try XCTUnwrap(Pairing.decodeOffer(Pairing.encodeOffer(responder.offer))), identity: device.keys, deviceName: device.name,
            accountId: "account-1", helloExtension: ConnectJSON.encode(device.memberKeys))
        let confirm = try responder.onHello(try initiator.hello()).get()
        let confirmed = try initiator.onConfirm(confirm).get()
        let onA = try responder.onCodeEntered(confirmed.code).get()
        let memberKeys = try XCTUnwrap(ConnectJSON.decode(MemberKeys.self, from: try XCTUnwrap(onA.peerExtension)))
        XCTAssertTrue(
            relay.fromClient(
                a.deviceId, .chainAppend(.init(record: try MemberChain.add(signer: a.keys, chain: try XCTUnwrap(a.client.verifiedChain()), target: memberKeys)))))
        try relay.run()
        device.client.expectJoin(
            try XCTUnwrap(ConnectJSON.decode(ConnectClient.JoinInfo.self, from: try XCTUnwrap(confirmed.pair.peerExtension))), inviterDeviceId: a.deviceId)
        if connect {
            device.connect()
            try relay.run()
            XCTAssertTrue(device.events.contains(.joined))
        }
    }

    private func feed(_ title: String, _ at: Int64, removed: Bool = false) -> LibraryFeed { LibraryFeed(title: title, removed: removed, updatedAtMs: at) }

    func testSubscriptionReachesTheOtherDeviceAndRemovalWins() throws {
        try setUpHousehold()
        try a.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 10)]))
        try relay.run()
        XCTAssertEqual("X", b.library?.feeds["https://x/f"]?.title)
        // B が外す（後の時刻）→ A でも外れる
        try b.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 20, removed: true)]))
        try relay.run()
        XCTAssertEqual(true, a.library?.feeds["https://x/f"]?.removed)
        XCTAssertEqual(2, relay.libraryState?.version)
    }

    func testConcurrentWritesAreMergedAfterConflict() throws {
        try setUpHousehold()
        // 両方が同じ版の上に書こうとする（届く前に）
        try a.client.library.publish(LibraryBody(feeds: ["https://a/f": feed("A", 10)]))
        try b.client.library.publish(LibraryBody(
            feeds: ["https://b/f": feed("B", 11)],
            positions: ["https://b/1.mp3": LibraryPosition(positionSeconds: 60, durationSeconds: 600, finished: false, updatedAtMs: 11)]))
        try relay.run()
        XCTAssertEqual(2, relay.libraryState?.version, "食い違った方が合わせて書き直していない")
        XCTAssertEqual(Set(["https://a/f", "https://b/f"]), Set(a.library.map { Array($0.feeds.keys) } ?? []))
        XCTAssertEqual(Set(["https://a/f", "https://b/f"]), Set(b.library.map { Array($0.feeds.keys) } ?? []))
        XCTAssertEqual(60, a.library?.positions["https://b/1.mp3"]?.positionSeconds)
    }

    func testADeviceThatWasOfflineCatchesUpAndControllersCanWrite() throws {
        try setUpHousehold(connectPc: false)
        try a.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 10)]))
        try relay.run()
        // PC（操作だけの端末）は後からつながる。持っていた登録は合わせて書く
        try pc.client.library.publish(LibraryBody(feeds: ["https://p/f": feed("P", 5)]))
        pc.connect()
        try relay.run()
        XCTAssertEqual(Set(["https://x/f", "https://p/f"]), Set(pc.library.map { Array($0.feeds.keys) } ?? []))
        XCTAssertEqual(Set(["https://x/f", "https://p/f"]), Set(a.library.map { Array($0.feeds.keys) } ?? []))
    }

    /// 中継がライブラリだけを失った（同じ保存のまま）: 覚えた版のまま書き続けず、0 から書き直す（Kotlin の b31ea9c と同じ）。
    func testARelayThatLostTheLibraryIsWrittenFromTheStartWithoutLooping() throws {
        try setUpHousehold()
        try a.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 10)]))
        try relay.run()
        relay.dropLibrary()
        var puts = 0
        relay.onFromClient = { _, message in if case .libraryPut = message { puts += 1 } }
        // 覚えた版（1）のまま書くと食い違いが返る。中継に無いと分かったら 0 から書き直す
        try a.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 10), "https://y/f": feed("Y", 11)]))
        try relay.run()
        XCTAssertEqual(1, relay.libraryState?.version)
        XCTAssertEqual(2, puts)
        XCTAssertEqual(Set(["https://x/f", "https://y/f"]), Set(b.library.map { Array($0.feeds.keys) } ?? []))
    }

    func testSameContentIsNotWrittenAgain() throws {
        try setUpHousehold()
        let body = LibraryBody(feeds: ["https://x/f": feed("X", 10)])
        try a.client.library.publish(body)
        try relay.run()
        try b.client.library.publish(body)
        try a.client.library.publish(body)
        try relay.run()
        XCTAssertEqual(1, relay.libraryState?.version)
    }

    /// 古い中継（library を知らない）: unsupported が返るだけで、連携の他の動きは変わらない。
    func testOldRelayOnlyAnswersUnsupported() throws {
        try setUpHousehold(supportsLibrary: false)
        try a.client.library.publish(LibraryBody(feeds: ["https://x/f": feed("X", 10)]))
        try relay.run()
        XCTAssertNil(relay.libraryState)
        XCTAssertNil(b.library)
        XCTAssertTrue(a.events.contains(.serverError(code: "unsupported")))
        XCTAssertEqual(3, a.client.members.count)
        // 再生の受け渡しは今までどおり
        try a.startPlaying(HandoffCodec.encode(InteropTests.sampleSnapshot()), item: "00000000-0000-4000-8000-000000000001", position: 1_000)
        try relay.run()
        XCTAssertTrue(a.client.isWriter)
        XCTAssertEqual(a.deviceId, b.client.activeDeviceId)
    }

    /// 中継の文書の中身は Kotlin の LibraryBody と同じ JSON（kotlinx の既定値・null の出し方）。
    func testBodyReadsKotlinJson() throws {
        let kotlin = #"{"v":1,"feeds":{"https://a.example/f":{"title":"A","author":null,"thumbnailUrl":null,"addedAtMs":10,"removed":false,"updatedAtMs":10},"https://b.example/f":{"title":"","author":null,"thumbnailUrl":null,"addedAtMs":0,"removed":true,"updatedAtMs":20}},"positions":{"https://a.example/1.mp3":{"positionSeconds":60,"durationSeconds":600,"finished":false,"updatedAtMs":11}}}"#
        let body = try XCTUnwrap(ConnectJSON.decode(LibraryBody.self, from: kotlin))
        XCTAssertEqual(1, body.v)
        XCTAssertEqual("A", body.feeds["https://a.example/f"]?.title)
        XCTAssertEqual(true, body.feeds["https://b.example/f"]?.removed)
        XCTAssertEqual(600, body.positions["https://a.example/1.mp3"]?.durationSeconds)
        // 書くと同じ文字列になる（キューの順は URL の順。Kotlin の出す順とは並びだけが違ってよい）
        XCTAssertEqual(kotlin, ConnectJSON.encode(body))
        // 欠けた既定値（古い・短い形）も読める
        let short = try XCTUnwrap(ConnectJSON.decode(LibraryBody.self, from: #"{"feeds":{"https://a/f":{"title":"A","updatedAtMs":3}}}"#))
        XCTAssertEqual(false, short.feeds["https://a/f"]?.removed)
        XCTAssertTrue(short.positions.isEmpty)
    }

    func testMergeKeepsNewerAndPrefersOtherOnTies() {
        let mine = LibraryBody(feeds: ["u": feed("mine", 10), "m": feed("M", 1)])
        let other = LibraryBody(feeds: ["u": feed("other", 10), "o": feed("O", 1)])
        let merged = mine.merge(other)
        XCTAssertEqual("other", merged.feeds["u"]?.title)
        XCTAssertEqual(Set(["u", "m", "o"]), Set(merged.feeds.keys))
        XCTAssertEqual("mine", LibraryBody(feeds: ["u": feed("mine", 11)]).merge(other).feeds["u"]?.title)
        let many = LibraryBody(positions: Dictionary(uniqueKeysWithValues: (0..<600).map {
            ("p\($0)", LibraryPosition(positionSeconds: 1, updatedAtMs: Int64($0)))
        }))
        let trimmed = many.merge(LibraryBody())
        XCTAssertEqual(LibraryBody.maxPositions, trimmed.positions.count)
        XCTAssertNil(trimmed.positions["p0"])
    }

    func testRelayMessagesRoundTrip() throws {
        for message: RelayV2 in [.libraryGet, .libraryOk(.init(requestId: "r", version: 3)), .libraryConflict(.init(requestId: "r")), .library(.init())] {
            XCTAssertEqual(message, RelayV2.decode(RelayV2.encode(message)))
        }
        XCTAssertEqual(#"{"op":"library_get"}"#, RelayV2.encode(.libraryGet))
        XCTAssertEqual(#"{"op":"library","state":null}"#, RelayV2.encode(.library(.init())))
    }
}
