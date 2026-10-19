import CryptoKit
import Foundation
import XCTest

@testable import WaveCore

/// Android（Kotlin）・Windows（C#）との相互運用。
/// Kotlin の CSharpInteropTest が固定のテスト鍵で作った成果物（Fixtures/connect/kotlin.json）と、C# の InteropTests が同じ鍵で作った成果物
/// （Fixtures/connect/csharp.json）を Swift で検証・開く。Swift で作った成果物は、同じ形に書き出して同じ規則で検証する。
final class InteropTests: XCTestCase {
    static let handoffId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    /// mac/Tests/WaveCoreTests/Connect から、リポジトリの windows/Wave.Core.Tests/Fixtures/connect へ（mac.sh が Mac へ同じ並びで送る）。
    static func fixtureURL(_ name: String) -> URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("windows/Wave.Core.Tests/Fixtures/connect").appendingPathComponent(name)
    }

    static func loadFixture(_ name: String) throws -> JSON { try JSON.parse(try Data(contentsOf: fixtureURL(name))) }

    private var kotlin: JSON!

    override func setUpWithError() throws {
        kotlin = try InteropTests.loadFixture("kotlin.json")
    }

    private func str(_ node: JSON?, _ key: String, file: StaticString = #filePath, line: UInt = #line) throws -> String {
        try XCTUnwrap(node?[key]?.str, "\(key) が無い", file: file, line: line)
    }

    private func device(_ name: String) throws -> SoftwareDevice {
        let keys = kotlin["keys"]?[name]
        return try SoftwareDevice.fromPkcs8(
            identity: try XCTUnwrap(B64.decode(try str(keys, "identity"))), kx: try XCTUnwrap(B64.decode(try str(keys, "kx"))))
    }

    private func decode<T: ConnectCodable>(_ type: T.Type, _ node: JSON?, file: StaticString = #filePath, line: UInt = #line) throws -> T {
        try XCTUnwrap(ConnectJSON.decode(type, json: try XCTUnwrap(node, file: file, line: line)), "読めない", file: file, line: line)
    }

    private static func ephemeral(_ b64: String) throws -> P256.KeyAgreement.PrivateKey {
        try SoftwareDevice.agreementKey(pkcs8: try XCTUnwrap(B64.decode(b64)))
    }

    func testDeviceIdsAndMemberKeysMatchKotlin() throws {
        let a = try device("a")
        XCTAssertEqual(try str(kotlin["deviceIds"], "a"), a.deviceId)
        XCTAssertEqual(try str(kotlin["deviceIds"], "pc"), try device("pc").deviceId)
        let keys = try decode(MemberKeys.self, kotlin["memberKeys"]?["a"])
        XCTAssertEqual(B64.encode(a.identityPub), keys.identityPub)
        XCTAssertEqual(B64.encode(Hpke.serializePublicKey(a.kxPub)), keys.kxPub)
    }

    func testVerifiesKotlinChain() throws {
        try checkChain(kotlin)
        let records = try kotlin["chain"].items.map { try decode(ChainRecord.self, $0) }
        let chain = try records.verified()
        XCTAssertEqual(.controller, chain.members[try str(kotlin["deviceIds"], "pc")]?.keys.role)
    }

    func testOpensKotlinDocs() throws {
        try checkDocs(kotlin)
        for entry in kotlin["docs"].items {
            let plaintext = try str(entry, "plaintext")
            if try str(entry, "name") == "cursor" {
                let cursor = try XCTUnwrap(ConnectJSON.decode(CursorBody.self, from: plaintext))
                XCTAssertEqual(.one, cursor.repeat)
                XCTAssertNil(cursor.sessionStartVersion)
            }
        }
    }

    func testRelayMessagesRoundTripToTheSameText() throws {
        try checkRelay(kotlin)
        XCTAssertGreaterThan(kotlin["relay"].arr?.count ?? 0, 30)
        XCTAssertEqual(.stateGet, RelayV2.decode(#"{"op":"state_get"}"#))
        // 判別子が先頭でなくても、知らない項目があっても読む。知らない op は nil
        XCTAssertEqual(.chainGet(.init(fromSeq: 3)), RelayV2.decode(#"{"fromSeq":3,"x":1,"op":"chain_get"}"#))
        XCTAssertNil(RelayV2.decode(#"{"op":"nope"}"#))
        XCTAssertNil(RelayV2.decode(#"{"op":"chain_ok","headSeq":1}"#))
        // null 可の項目は欠けていても nil
        guard case .activeChanged(let active)? = RelayV2.decode(#"{"op":"active_changed","claimOrder":2}"#) else { return XCTFail("読めない") }
        XCTAssertNil(active.activeDeviceId)
    }

    func testHandoffCodecMatchesKotlin() throws {
        try checkHandoff(kotlin)
        let text = try str(kotlin["handoff"], "text")
        guard case .ok(let snapshot) = HandoffCodec.decode(text, expectedHandoffId: InteropTests.handoffId) else { return XCTFail("読めない") }
        XCTAssertEqual("夜に駆ける \"quoted\" 🎵", snapshot.items[0].title)
        XCTAssertEqual(.radio(seedVideoId: "abcdefghijA"), snapshot.source)
        XCTAssertEqual(.userAdded, snapshot.items[1].origin)
    }

    func testPairsWithKotlinOnBothSides() throws {
        let p = kotlin["pairing"]
        try checkPairing(kotlin, responderName: try str(p, "responderName"))
        // C# の Initiator（Kotlin の B と同じ使い捨ての鍵）の送る鍵も Kotlin と同じ
        let offer = try XCTUnwrap(Pairing.decodeOffer(try str(p, "offer")))
        let initiator = try Pairing.Initiator(
            offer: offer, identity: try device("b"), deviceName: try str(p, "initiatorName"), accountId: try str(p, "accountId"),
            helloExtension: try str(p, "helloExtension"), ephemeral: try InteropTests.ephemeral(try str(p, "initiatorEphemeral")))
        let confirmed = try initiator.onConfirm(try XCTUnwrap(Pairing.decodeConfirm(try XCTUnwrap(p?["confirm"])))).get()
        XCTAssertEqual(try str(p, "bSendKey"), B64.encode(confirmed.pair.sendKey))
        XCTAssertEqual("Pixel 3", confirmed.pair.peerDeviceName)
        let joinInfo = try XCTUnwrap(ConnectJSON.decode(ConnectClient.JoinInfo.self, from: try XCTUnwrap(confirmed.pair.peerExtension)))
        XCTAssertEqual(try str(kotlin, "householdId"), joinInfo.householdId)
        XCTAssertEqual(confirmed.pair.peerExtension, ConnectJSON.encode(joinInfo))
    }

    /// C# が（Kotlin のテスト鍵で）作った成果物を、Swift で同じ規則で検証して開く。
    func testOpensCSharpArtifacts() throws {
        let csharp = try InteropTests.loadFixture("csharp.json")
        try checkArtifacts(csharp, responderName: "Pixel 3")
    }

    /// Swift で作った成果物を、C# が書き出すのと同じ形の JSON にし、Kotlin・C# の成果物と同じ規則で検証する。
    func testSwiftArtifactsPassTheSameChecks() throws {
        let output = try swiftArtifacts()
        // 文字列を通して読み直す（書き出したものを他の端末が読むのと同じ）
        try checkArtifacts(try JSON.parse(ConnectJSON.serialize(output)), responderName: "Pixel 3")
    }

    // MARK: 共通の確かめ（kotlin.json・csharp.json・Swift の成果物に同じ規則を当てる）

    private func checkArtifacts(_ f: JSON, responderName: String) throws {
        try checkChain(f)
        try checkDocs(f)
        try checkRelay(f)
        try checkHandoff(f)
        try checkPairing(f, responderName: responderName)
    }

    private func checkChain(_ f: JSON) throws {
        let nodes = f["chain"].items
        let records = try nodes.map { try decode(ChainRecord.self, $0) }
        guard case .ok(let chain) = MemberChain.verify(records) else { return XCTFail("鎖が検証を通らない") }
        XCTAssertEqual(try str(f, "householdId"), chain.householdId)
        XCTAssertEqual(f["chainHashes"].arr?.compactMap(\.str), records.map(MemberChain.hash))
        XCTAssertEqual([try device("a").deviceId, try device("b").deviceId, try device("pc").deviceId], chain.memberIds)
        XCTAssertEqual(.controller, chain.members[try device("pc").deviceId]?.keys.role)
        // JSON の形も同じ（読んで書き直すと元と同じ文字列）
        for node in nodes { XCTAssertEqual(ConnectJSON.serialize(node), ConnectJSON.encode(try decode(ChainRecord.self, node))) }
        let tag = f["accountTag"]
        XCTAssertEqual(try str(tag, "tag"), AccountTag.of(householdId: try str(tag, "householdId"), accountId: try str(tag, "accountId")))
    }

    private func checkDocs(_ f: JSON) throws {
        let chain = try f["chain"].items.map { try decode(ChainRecord.self, $0) }.verified()
        let devices = try ["a", "b", "pc"].map(device)
        let docs = f["docs"].items
        XCTAssertEqual(["queue", "cursor", "command", "profile", "library"], try docs.map { try str($0, "name") })
        for entry in docs {
            let doc = try decode(SealedDoc.self, entry["doc"])
            XCTAssertEqual(try str(entry, "hash"), DocSealer.hash(doc))
            XCTAssertEqual(ConnectJSON.serialize(try XCTUnwrap(entry["doc"])), ConnectJSON.encode(doc))
            let plaintext = try str(entry, "plaintext")
            for device in devices {
                guard case .ok(_, let opened, _) = DocSealer.open(doc, chain: chain, me: device) else { return XCTFail("開けない") }
                XCTAssertEqual(plaintext, String(decoding: opened, as: UTF8.self))
            }
            switch try str(entry, "name") {
            case "queue":
                let queue = try XCTUnwrap(ConnectJSON.decode(QueueBody.self, from: plaintext))
                XCTAssertEqual(plaintext, ConnectJSON.encode(queue))
                guard case .ok = HandoffCodec.decode(queue.snapshot, expectedHandoffId: InteropTests.handoffId) else { return XCTFail("キューが読めない") }
            case "cursor":
                let cursor = try XCTUnwrap(ConnectJSON.decode(CursorBody.self, from: plaintext))
                XCTAssertEqual(plaintext, ConnectJSON.encode(cursor))
            case "command":
                let command = try XCTUnwrap(ConnectJSON.decode(CommandBody.self, from: plaintext))
                guard case .yield = command else { return XCTFail("yield ではない") }
                XCTAssertEqual(plaintext, ConnectJSON.encode(command))
            case "profile":
                XCTAssertEqual("Windows PC", ConnectJSON.decode(ProfileBody.self, from: plaintext)?.name)
            case "library":
                // ライブラリ（ADR-0013）。読んで書き直すと同じ文字列（Kotlin の Map の並びのまま）
                let library = try XCTUnwrap(ConnectJSON.decode(LibraryBody.self, from: plaintext))
                XCTAssertEqual(InteropTests.sampleLibrary(), library)
                XCTAssertEqual(plaintext, ConnectJSON.encode(library))
            default:
                XCTFail("知らない doc")
            }
        }
    }

    private func checkRelay(_ f: JSON) throws {
        let texts = f["relay"].arr?.compactMap(\.str) ?? []
        XCTAssertGreaterThan(texts.count, 30)
        for text in texts {
            let message = try XCTUnwrap(RelayV2.decode(text), text)
            XCTAssertEqual(text, RelayV2.encode(message))
        }
    }

    private func checkHandoff(_ f: JSON) throws {
        let text = try str(f["handoff"], "text")
        guard case .ok(let snapshot) = HandoffCodec.decode(text, expectedHandoffId: InteropTests.handoffId) else { return XCTFail("読めない") }
        XCTAssertEqual(text, HandoffCodec.encode(snapshot))
    }

    private func checkPairing(_ f: JSON, responderName: String) throws {
        let p = f["pairing"]
        let offer = try XCTUnwrap(Pairing.decodeOffer(try str(p, "offer")))
        let hello = try XCTUnwrap(Pairing.decodeHello(try XCTUnwrap(p?["hello"])))
        let confirm = try XCTUnwrap(Pairing.decodeConfirm(try XCTUnwrap(p?["confirm"])))
        let code = try str(p, "code")

        // Swift の Responder（相手の A と同じ使い捨ての鍵・token）が相手の Hello を受ける
        let responder = Pairing.Responder(
            identity: try device("a"), deviceName: responderName, accountId: try str(p, "accountId"), nowMs: { 0 },
            confirmAccountId: try str(p, "accountId"), confirmExtension: try str(p, "confirmExtension"),
            ephemeral: try InteropTests.ephemeral(try str(p, "responderEphemeral")), token: try XCTUnwrap(B64.decode(offer.token)))
        XCTAssertEqual(offer, responder.offer)
        XCTAssertNil(try responder.onHello(hello).failure)
        XCTAssertEqual(.wrongCode, responder.onCodeEntered(code == "000000" ? "111111" : "000000").failure)
        let pairOnA = try responder.onCodeEntered(code).get()
        XCTAssertEqual(try str(p, "aSendKey"), B64.encode(pairOnA.sendKey))
        if let receive = p?["aReceiveKey"]?.str { XCTAssertEqual(receive, B64.encode(pairOnA.receiveKey)) }
        if let account = p?["accountKey"]?.str { XCTAssertEqual(account, B64.encode(pairOnA.accountKey)) }
        XCTAssertEqual(try str(p, "helloExtension"), pairOnA.peerExtension)
        XCTAssertEqual("Windows test", pairOnA.peerDeviceName)
        // 拡張は ConnectJSON の MemberKeys
        XCTAssertEqual(try device("b").deviceId, ConnectJSON.decode(MemberKeys.self, from: try XCTUnwrap(pairOnA.peerExtension))?.deviceId)

        // Swift の Initiator（相手の B と同じ使い捨ての鍵）が相手の Confirm を受け、同じ6桁を出す
        let initiator = try Pairing.Initiator(
            offer: offer, identity: try device("b"), deviceName: try str(p, "initiatorName"), accountId: try str(p, "accountId"),
            helloExtension: try str(p, "helloExtension"), ephemeral: try InteropTests.ephemeral(try str(p, "initiatorEphemeral")))
        let confirmed = try initiator.onConfirm(confirm).get()
        XCTAssertEqual(code, confirmed.code)
        XCTAssertEqual(pairOnA.sendKey, confirmed.pair.receiveKey)
        XCTAssertEqual(pairOnA.receiveKey, confirmed.pair.sendKey)
        XCTAssertEqual(responderName, confirmed.pair.peerDeviceName)
        let joinInfo = try XCTUnwrap(ConnectJSON.decode(ConnectClient.JoinInfo.self, from: try XCTUnwrap(confirmed.pair.peerExtension)))
        XCTAssertEqual(try str(f, "householdId"), joinInfo.householdId)
        XCTAssertEqual(confirmed.pair.peerExtension, ConnectJSON.encode(joinInfo))
    }

    // MARK: Swift で作る成果物（C# の WriteCSharpFixtures と同じもの）

    private func swiftArtifacts() throws -> JSON {
        let a = try device("a")
        let b = try device("b")
        let pc = try device("pc")
        let genesis = try MemberChain.genesis(signer: a, keys: try a.memberKeys(role: .player))
        let addB = try MemberChain.add(signer: a, chain: try [genesis].verified(), target: try b.memberKeys(role: .player))
        let addPc = try MemberChain.add(signer: b, chain: try [genesis, addB].verified(), target: try pc.memberKeys(role: .controller))
        let records = [genesis, addB, addPc]
        let chain = try records.verified()
        let tag = AccountTag.of(householdId: chain.householdId, accountId: "account-1")

        let snapshotText = HandoffCodec.encode(InteropTests.sampleSnapshot())
        let queueBody = QueueBody(accountTag: tag, snapshot: snapshotText)
        let queueDoc = try DocSealer.seal(
            signer: a, chain: chain, kind: .queue, version: 1, prevHash: nil, issuedAtEpochMs: 1_700_000_000_001, plaintext: ConnectJSON.encodeToUTF8(queueBody))
        let cursorBody = CursorBody(
            queueRef: DocSealer.hash(queueDoc), currentItemId: "00000000-0000-4000-8000-000000000001", positionMs: 42_000, playing: true, repeat: .one,
            sessionBase: 3, sessionStartVersion: 4, deviceSeq: 7, listenedMs: 30_000, historySent: false, accountTag: tag,
            startedAtEpochMs: 1_700_000_000_002)
        let cursorDoc = try DocSealer.seal(
            signer: a, chain: chain, kind: .cursor, version: 4, prevHash: "prev-hash", issuedAtEpochMs: 1_700_000_000_003,
            plaintext: ConnectJSON.encodeToUTF8(cursorBody))
        let command = CommandBody.yield(.init(claimId: "claim-0000000000000001", from: b.deviceId, to: a.deviceId, sessionBase: 4, accountTag: tag))
        let commandDoc = try DocSealer.seal(
            signer: b, chain: chain, kind: .command, version: 1, prevHash: nil, issuedAtEpochMs: 1_700_000_000_004, plaintext: ConnectJSON.encodeToUTF8(command))
        let profile = ProfileBody(name: "Windows PC")
        let profileDoc = try DocSealer.seal(
            signer: pc, chain: chain, kind: .profile, version: 1, prevHash: nil, issuedAtEpochMs: 1_700_000_000_005, plaintext: ConnectJSON.encodeToUTF8(profile))
        // ライブラリ（ADR-0013）は操作だけの端末（PC）も書ける
        let libraryBody = InteropTests.sampleLibrary()
        let libraryDoc = try DocSealer.seal(
            signer: pc, chain: chain, kind: .library, version: 1, prevHash: nil, issuedAtEpochMs: 1_700_000_000_006, plaintext: ConnectJSON.encodeToUTF8(libraryBody))

        // ペアリング（どちらも Swift）。使い捨ての鍵を書き出し、確かめる側が同じ鍵で相手の側を再現する
        let joinInfo = ConnectClient.JoinInfo(
            householdId: chain.householdId, headSeq: chain.headSeq, headHash: chain.headHash, storeId: "store-1", cursorFloor: 4, queueFloor: 1)
        let joinInfoJson = ConnectJSON.encode(joinInfo)
        let memberKeysJson = ConnectJSON.encode(try b.memberKeys(role: .player))
        let eA = WaveCrypto.generateEphemeralKeyPair()
        let eB = WaveCrypto.generateEphemeralKeyPair()
        let responder = Pairing.Responder(
            identity: a, deviceName: "Pixel 3", accountId: "account-1", nowMs: { 0 }, confirmAccountId: "account-1", confirmExtension: joinInfoJson,
            ephemeral: eA, token: WaveCrypto.randomBytes(Pairing.tokenBytes))
        let offerText = Pairing.encodeOffer(responder.offer)
        let initiator = try Pairing.Initiator(
            offer: try XCTUnwrap(Pairing.decodeOffer(offerText)), identity: b, deviceName: "Windows \u{202E}test", accountId: "account-1",
            helloExtension: memberKeysJson, ephemeral: eB)
        let hello = try initiator.hello()
        let confirm = try responder.onHello(hello).get()
        let confirmed = try initiator.onConfirm(confirm).get()
        let pairOnA = try responder.onCodeEntered(confirmed.code).get()

        let stored = StoredState(
            kind: .cursor, version: 4, doc: cursorDoc, storeId: "store-1", receivedAtServerMs: 1, serverNowMs: 2, activeDeviceId: a.deviceId)
        let tokenHash = B64.encode(responder.tokenHash)
        let relay: [RelayV2] = [
            .challenge(.init(host: "relay.example", nonce: "bm9uY2U")),
            .auth(.init(publicKey: B64.encode(a.identityPub), signature: "c2ln")),
            .welcome(.init(deviceId: a.deviceId, storeId: "store-1")),
            .welcome(
                .init(
                    deviceId: a.deviceId, storeId: "store-1", householdId: chain.householdId, headSeq: chain.headSeq, headHash: chain.headHash,
                    activeDeviceId: a.deviceId, online: [a.deviceId], states: [stored], claimOrders: [(a.deviceId, 3)])),
            .householdCreate(.init(genesis: genesis)),
            .chainGet(.init()),
            .chain(.init(records: records)),
            .chainAppend(.init(record: addPc)),
            .chainOk(.init(headSeq: 2, headHash: chain.headHash)),
            .stateGet,
            .statePut(.init(requestId: "req-1", kind: .queue, expectVersion: 0, doc: queueDoc)),
            .stateOk(.init(requestId: "req-1", kind: .queue, version: 1)),
            .stateConflict(.init(requestId: "req-2", kind: .cursor)),
            .stateConflict(.init(requestId: "req-2", kind: .cursor, latest: stored)),
            .state(.init(stored: stored)),
            .claim(.init(claimId: "claim-0000000000000001", yieldDoc: commandDoc)),
            .transfer(.init(claimId: "claim-0000000000000001", basedOnVersion: 4, yieldDoc: commandDoc)),
            .yieldDelivery(.init(claimId: "claim-0000000000000001", yieldDoc: commandDoc)),
            .yielded(.init(claimId: "claim-0000000000000001", expectVersion: 4, doc: cursorDoc)),
            .release(.init(claimId: "claim-0000000000000001")),
            .activeChanged(.init(claimOrder: 5)),
            .activeChanged(.init(activeDeviceId: a.deviceId, claimId: "c", claimOrder: 6, pendingOwner: b.deviceId)),
            .pendingExpired(.init(claimId: "c")),
            .presence(.init(online: [a.deviceId, b.deviceId])),
            .pairingOpen(.init(tokenHash: tokenHash)),
            .pairHelloIn(.init(token: responder.offer.token, hello: Pairing.toJSON(hello))),
            .pairHello(.init(tokenHash: tokenHash, hello: Pairing.toJSON(hello))),
            .pairConfirm(.init(tokenHash: tokenHash, confirm: Pairing.toJSON(confirm))),
            .pairConfirmed(.init(confirm: Pairing.toJSON(confirm))),
            .error(.init(code: "not_active")),
            .error(.init(code: "stale_head", requestId: "req-1")),
            .libraryGet,
            .libraryPut(.init(requestId: "req-3", expectVersion: 0, doc: libraryDoc)),
            .libraryOk(.init(requestId: "req-3", version: 1)),
            .libraryConflict(.init(requestId: "req-4")),
            .libraryConflict(.init(requestId: "req-4", latest: LibraryState(version: 1, doc: libraryDoc, storeId: "store-1"))),
            .library(.init()),
            .library(.init(state: LibraryState(version: 1, doc: libraryDoc, storeId: "store-1"))),
        ]

        func docEntry(_ name: String, _ doc: SealedDoc, _ plaintext: String) -> JSON {
            .obj(["name": .string(name), "doc": doc.toJSON(), "plaintext": .string(plaintext), "hash": .string(DocSealer.hash(doc))])
        }

        return .obj([
            "chain": .array(records.map { $0.toJSON() }),
            "chainHashes": .array(records.map { .string(MemberChain.hash($0)) }),
            "householdId": .string(chain.householdId),
            "memberKeysJson": .string(memberKeysJson),
            "accountTag": .obj(["householdId": .string(chain.householdId), "accountId": "account-1", "tag": .string(tag)]),
            "docs": .array([
                docEntry("queue", queueDoc, ConnectJSON.encode(queueBody)),
                docEntry("cursor", cursorDoc, ConnectJSON.encode(cursorBody)),
                docEntry("command", commandDoc, ConnectJSON.encode(command)),
                docEntry("profile", profileDoc, ConnectJSON.encode(profile)),
                docEntry("library", libraryDoc, ConnectJSON.encode(libraryBody)),
            ]),
            "handoff": .obj(["handoffId": .string(InteropTests.handoffId), "text": .string(snapshotText)]),
            "relay": .array(relay.map { .string(RelayV2.encode($0)) }),
            "pairing": .obj([
                "offer": .string(offerText),
                "responderEphemeral": .string(B64.encode(eA.derRepresentation)),
                "initiatorEphemeral": .string(B64.encode(eB.derRepresentation)),
                "accountId": "account-1",
                "initiatorName": "Windows \u{202E}test",
                "hello": Pairing.toJSON(hello),
                "confirm": Pairing.toJSON(confirm),
                "code": .string(confirmed.code),
                "helloExtension": .string(memberKeysJson),
                "confirmExtension": .string(joinInfoJson),
                "aSendKey": .string(B64.encode(pairOnA.sendKey)),
            ]),
        ])
    }

    /// Kotlin の CSharpInteropTest の sampleLibrary と同じもの（並びも同じ: 読んだ JSON から作る）。
    static func sampleLibrary() -> LibraryBody {
        let json = #"{"v":1,"feeds":{"https://feeds.example/show":{"title":"番組 \"A\" 🎙","author":"作者","thumbnailUrl":"https://img.example/a.jpg","addedAtMs":1700000000000,"removed":false,"updatedAtMs":1700000000000},"https://feeds.example/old":{"title":"","author":null,"thumbnailUrl":null,"addedAtMs":0,"removed":true,"updatedAtMs":1700000000010}},"positions":{"https://feeds.example/1.mp3":{"positionSeconds":60,"durationSeconds":600,"finished":false,"updatedAtMs":1700000000020},"https://feeds.example/2.mp3":{"positionSeconds":5,"durationSeconds":null,"finished":true,"updatedAtMs":1700000000030}}}"#
        return ConnectJSON.decode(LibraryBody.self, from: json)!
    }

    /// Kotlin の CSharpInteropTest の sampleSnapshot と同じもの。
    static func sampleSnapshot() -> HandoffSnapshot {
        HandoffSnapshot(
            schemaVersion: HandoffCodec.schemaVersion,
            handoffId: handoffId,
            items: [
                HandoffItem(
                    itemId: "00000000-0000-4000-8000-000000000001", videoId: "abcdefghijA", title: "夜に駆ける \"quoted\" 🎵",
                    artists: [HandoffRef(name: "YOASOBI", browseId: "UCsynthetic000000000000000")], album: HandoffRef(name: "THE BOOK"),
                    durationSeconds: 261, thumbnailUrl: "https://lh3.googleusercontent.com/x=w120-h120", origin: .source),
                HandoffItem(itemId: "00000000-0000-4000-8000-000000000002", videoId: "abcdefghijB", title: "second", artists: [], origin: .userAdded),
            ],
            currentItemId: "00000000-0000-4000-8000-000000000001",
            positionMs: 42_000,
            playing: true,
            repeat: .all,
            source: .radio(seedVideoId: "abcdefghijA"),
            radioPlaylistId: "RDsynthetic",
            exhausted: false,
            listenedMs: 30_000,
            historySent: true,
            capturedAtEpochMs: 1_700_000_000_000)
    }
}
