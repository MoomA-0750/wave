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

/// LibrarySync だけを、版の CAS だけをする中継（library のところだけ）とつないで確かめる。
final class LibrarySyncUnitTests: XCTestCase {
    private typealias TestDevice = ConnectTestSupport.TestDevice
    private static let store = "store-1"

    private final class Relay {
        var stored: LibraryState?
        var inbox: [RelayV2] = []
        var puts = 0

        init(_ stored: LibraryState?) { self.stored = stored }

        func handle(_ message: RelayV2) {
            switch message {
            case .libraryGet:
                inbox.append(.library(.init(state: stored)))
            case .libraryPut(let m):
                puts += 1
                let current = stored?.version ?? 0
                if current != m.expectVersion {
                    inbox.append(.libraryConflict(.init(requestId: m.requestId, latest: stored)))
                    return
                }
                stored = LibraryState(version: current + 1, doc: m.doc, storeId: LibrarySyncUnitTests.store)
                inbox.append(.libraryOk(.init(requestId: m.requestId, version: current + 1)))
            default:
                break
            }
        }
    }

    private var outbox: [RelayV2] = []

    private func feed(_ title: String, _ at: Int64) -> LibraryFeed { LibraryFeed(title: title, updatedAtMs: at) }

    /// device が chain で中継につながる。届いたものが無くなるまで回す。
    private func connect(_ device: TestDevice, _ chain: VerifiedChain, _ relay: Relay) throws -> LibrarySync {
        var ids = 0
        let sync = LibrarySync(
            signer: device, opener: device, send: { [unowned self] in self.outbox.append($0); return true }, chain: { chain },
            storeId: { LibrarySyncUnitTests.store }, epochMs: { 1 }, newId: { ids += 1; return "req-\(ids)" }, onRemote: { _ in })
        sync.onConnected()
        try pump(sync, relay)
        return sync
    }

    private func pump(_ sync: LibrarySync, _ relay: Relay) throws {
        while !outbox.isEmpty || !relay.inbox.isEmpty {
            while !outbox.isEmpty { relay.handle(outbox.removeFirst()) }
            if !relay.inbox.isEmpty { try sync.onMessage(relay.inbox.removeFirst()) }
        }
    }

    func testAnUnreadableRemoteDocumentIsNeverOverwritten() throws {
        // 中継の最新の文書を、この端末が開けない（別の一覧で書かれた・宛先にいない）
        let other = TestDevice()
        let otherChain = try [try MemberChain.genesis(signer: other, keys: other.keys)].verified()
        let doc = try DocSealer.seal(
            signer: other, chain: otherChain, kind: .library, version: 5, prevHash: nil, issuedAtEpochMs: 1,
            plaintext: ConnectJSON.encodeToUTF8(LibraryBody(feeds: ["https://feeds.example/x": feed("X", 5)])))
        let relay = Relay(LibraryState(version: 5, doc: doc, storeId: LibrarySyncUnitTests.store))
        let a = TestDevice()
        let sync = try connect(a, try [try MemberChain.genesis(signer: a, keys: a.keys)].verified(), relay)
        try sync.publish(LibraryBody(feeds: ["https://feeds.example/a": feed("A", 10)]))
        try pump(sync, relay)
        // 書かない（書けば開けなかった登録を消し、食い違いが返り続ける）
        XCTAssertEqual(0, relay.puts)
        XCTAssertNotNil(sync.unreadable)
        XCTAssertEqual(5, relay.stored?.version)
    }

    func testADocumentMissingAMemberIsRewrappedEvenWithTheSameContent() throws {
        let a = TestDevice()
        let pc = TestDevice(.controller)
        let genesis = try MemberChain.genesis(signer: a, keys: a.keys)
        let chain = try [genesis, try MemberChain.add(signer: a, chain: try [genesis].verified(), target: pc.keys)].verified()
        // PC が加わる前に書かれた（宛先が A だけ）
        let same = LibraryBody(feeds: ["https://feeds.example/x": feed("X", 5)])
        let doc = try DocSealer.seal(
            signer: a, chain: chain, kind: .library, version: 3, prevHash: nil, issuedAtEpochMs: 1, plaintext: ConnectJSON.encodeToUTF8(same),
            only: [a.deviceId])
        let relay = Relay(LibraryState(version: 3, doc: doc, storeId: LibrarySyncUnitTests.store))
        let sync = try connect(a, chain, relay)
        XCTAssertNil(sync.unreadable)
        // 中身が同じでも、全員に包み直して1回だけ書く
        XCTAssertEqual(1, relay.puts)
        XCTAssertEqual(4, relay.stored?.version)
        XCTAssertEqual(Set([a.deviceId, pc.deviceId]), Set(relay.stored?.doc.recipients.map(\.deviceId) ?? []))
        try sync.publish(same)
        try pump(sync, relay)
        XCTAssertEqual(1, relay.puts)
    }
}
