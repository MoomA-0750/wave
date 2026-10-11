import Foundation
import XCTest

@testable import WaveCore

private typealias TestDevice = ConnectTestSupport.TestDevice

/// 署名とハッシュの対象のバイト列を、Kotlin の CanonicalBytesTest と同じ既知の値で固定する。
final class CanonicalBytesTests: XCTestCase {
    private func keys(_ id: String, _ pub: String, _ kx: String, _ sig: String) -> MemberKeys {
        MemberKeys(deviceId: id, identityPub: pub, kxPub: kx, role: .player, kxSignature: sig)
    }

    func testChainRecordHashesMatchKotlin() {
        let genesis = ChainRecord(seq: 0, type: .genesis, target: keys("dev-a", "pub-a", "kx-a", "kxsig-a"), signerId: "dev-a", signature: "sig")
        let add = ChainRecord(
            householdId: "house", seq: 1, prevHash: "prev", type: .add, target: keys("dev-b", "pub-b", "kx-b", "kxsig-b"), signerId: "dev-a", signature: "sig")
        let remove = ChainRecord(householdId: "house", seq: 2, prevHash: "prev2", type: .remove, removedDeviceId: "dev-b", signerId: "dev-a", signature: "sig")
        XCTAssertEqual("07McxbsrW_iq4fxMp1hn5SQy6I5gpkAGc1OzMU1ZcZI", MemberChain.hash(genesis))
        XCTAssertEqual("Bc9cJdgfyeNXxPZ5HM4SisXRhTNBchPH6MbaOb57ATI", MemberChain.hash(add))
        XCTAssertEqual("A6e-7u5LQFx11vmK5Zj6n7QgeG01bctfiCnA9VnjvaU", MemberChain.hash(remove))
        var other = add
        other.signature = "other"
        XCTAssertEqual(MemberChain.hash(add), MemberChain.hash(other))
    }

    func testSealedDocHashesMatchKotlin() {
        let header = DocHeader(
            kind: .cursor, householdId: "house", writerId: "dev-a", version: 7, prevHash: "p", headSeq: 3, headHash: "h", issuedAtEpochMs: 1_700_000_000_000)
        let doc = SealedDoc(
            header: header, nonce: "nonce", ciphertext: "cipher",
            recipients: [
                DocRecipient(deviceId: "dev-a", enc: "enc-a", wrappedKey: "wrap-a"), DocRecipient(deviceId: "dev-b", enc: "enc-b", wrappedKey: "wrap-b"),
            ],
            signature: "sig")
        XCTAssertEqual("OIhuUfwQMOoU568qY4i4GiaVIZqPkDqmg_gOeXLnaFI", DocSealer.hash(doc))
        var queue = doc
        queue.header.kind = .queue
        queue.header.prevHash = nil
        XCTAssertEqual("6ajZYk-MILk4axIoVv59uvEvaxL1XWV1ijvh2K9c6jQ", DocSealer.hash(queue))
        var resigned = doc
        resigned.signature = "other"
        XCTAssertEqual(DocSealer.hash(doc), DocSealer.hash(resigned))
    }
}

final class MemberChainTests: XCTestCase {
    private let a = TestDevice()
    private let b = TestDevice()
    private let pc = TestDevice(.controller)

    private func genesisAndB() throws -> [ChainRecord] {
        let genesis = try MemberChain.genesis(signer: a, keys: a.keys)
        return [genesis, try MemberChain.add(signer: a, chain: [genesis].verified(), target: b.keys)]
    }

    private func invalidReason(_ records: [ChainRecord], file: StaticString = #filePath, line: UInt = #line) -> String? {
        guard case .invalid(_, let reason) = MemberChain.verify(records) else {
            XCTFail("invalid ではない", file: file, line: line)
            return nil
        }
        return reason
    }

    func testGenesisAddAndRemoveVerify() throws {
        let records = try genesisAndB()
        let chain = try records.verified()
        XCTAssertEqual([a.deviceId, b.deviceId], chain.memberIds)
        XCTAssertEqual(MemberChain.hash(records[0]), chain.householdId)
        let withPc = records + [try MemberChain.add(signer: b, chain: chain, target: pc.keys)]
        let removed = withPc + [try MemberChain.remove(signer: a, chain: try withPc.verified(), deviceId: b.deviceId)]
        let finalChain = try removed.verified()
        XCTAssertEqual([a.deviceId, pc.deviceId], finalChain.memberIds)
        XCTAssertEqual([b.deviceId], finalChain.removed)
        XCTAssertEqual(3, finalChain.headSeq)
    }

    func testRejectsForgedOrUnauthorizedRecords() throws {
        let records = try genesisAndB()
        let chain = try records.verified()
        let outsider = TestDevice()
        XCTAssertEqual("signer_not_member", invalidReason(records + [try MemberChain.add(signer: outsider, chain: chain, target: TestDevice().keys)]))
        var forged = try MemberChain.add(signer: outsider, chain: chain, target: TestDevice().keys)
        forged.signerId = a.deviceId
        XCTAssertEqual("signature", invalidReason(records + [forged]))
        let withPc = records + [try MemberChain.add(signer: a, chain: chain, target: pc.keys)]
        XCTAssertEqual("add_by_controller", invalidReason(withPc + [try MemberChain.add(signer: pc, chain: try withPc.verified(), target: TestDevice().keys)]))
        XCTAssertEqual("remove_by_controller", invalidReason(withPc + [try MemberChain.remove(signer: pc, chain: try withPc.verified(), deviceId: b.deviceId)]))
        guard case .ok = MemberChain.verify(withPc + [try MemberChain.remove(signer: pc, chain: try withPc.verified(), deviceId: pc.deviceId)]) else {
            return XCTFail("controller が自分を外せない")
        }
        let onlyA = records + [try MemberChain.remove(signer: a, chain: chain, deviceId: b.deviceId)]
        XCTAssertEqual("last_player", invalidReason(onlyA + [try MemberChain.remove(signer: a, chain: try onlyA.verified(), deviceId: a.deviceId)]))
        XCTAssertEqual("signer_not_member", invalidReason(onlyA + [try MemberChain.add(signer: b, chain: try onlyA.verified(), target: TestDevice().keys)]))
    }

    func testRejectsBrokenLinksAndKeys() throws {
        let records = try genesisAndB()
        var broken = records[1]
        broken.prevHash = "x"
        XCTAssertEqual("link", invalidReason([records[0], broken]))
        XCTAssertEqual("link", invalidReason([try MemberChain.genesis(signer: b, keys: b.keys), records[1]]))
        let genesisOnly = try [records[0]].verified()
        var wrongKx = b.keys
        wrongKx.kxPub = TestDevice().keys.kxPub
        XCTAssertEqual("add_keys", invalidReason([records[0], try MemberChain.add(signer: a, chain: genesisOnly, target: wrongKx)]))
        var wrongId = b.keys
        wrongId.deviceId = TestDevice().deviceId
        XCTAssertEqual("add_keys", invalidReason([records[0], try MemberChain.add(signer: a, chain: genesisOnly, target: wrongId)]))
        XCTAssertEqual("add_existing", invalidReason(records + [try MemberChain.add(signer: a, chain: try records.verified(), target: b.keys)]))
    }

    func testDetectsRollbackAndForkAgainstTrustedHead() throws {
        let records = try genesisAndB()
        let chain = try records.verified()
        let head = MemberChain.Head(seq: chain.headSeq, hash: chain.headHash)
        guard case .rollback = MemberChain.verify(Array(records.prefix(1)), trusted: head) else { return XCTFail("rollback ではない") }
        let branch = [records[0], try MemberChain.add(signer: a, chain: try [records[0]].verified(), target: TestDevice().keys)]
        guard case .fork = MemberChain.verify(branch, trusted: head) else { return XCTFail("fork ではない") }
        guard case .ok = MemberChain.verify(records + [try MemberChain.add(signer: b, chain: chain, target: pc.keys)], trusted: head) else {
            return XCTFail("ok ではない")
        }
    }
}

final class DocSealerTests: XCTestCase {
    private let a = TestDevice()
    private let b = TestDevice()
    private let pc = TestDevice(.controller)
    private var records: [ChainRecord] = []
    private var withPc: [ChainRecord] = []
    private var chain: VerifiedChain!
    private let body = Data("cursor-body".utf8)

    override func setUpWithError() throws {
        let genesis = try MemberChain.genesis(signer: a, keys: a.keys)
        records = [genesis, try MemberChain.add(signer: a, chain: try [genesis].verified(), target: b.keys)]
        withPc = records + [try MemberChain.add(signer: a, chain: try records.verified(), target: pc.keys)]
        chain = try withPc.verified()
    }

    private func seal(signer: DeviceSigner? = nil, kind: DocKind = .cursor, onChain: VerifiedChain? = nil) throws -> SealedDoc {
        try DocSealer.seal(signer: signer ?? a, chain: onChain ?? chain, kind: kind, version: 7, prevHash: nil, issuedAtEpochMs: 1, plaintext: body)
    }

    private func isOk(_ result: DocOpen) -> Bool {
        if case .ok = result { return true }
        return false
    }

    func testEveryMemberOpensAndOthersCannot() throws {
        let doc = try seal()
        for device in [a, b, pc] {
            guard case .ok(_, let plaintext, let stale) = DocSealer.open(doc, chain: chain, me: device) else { return XCTFail("開けない") }
            XCTAssertEqual(body, plaintext)
            XCTAssertFalse(stale)
        }
        XCTAssertEqual(.rejected("not_for_me"), DocSealer.open(doc, chain: chain, me: TestDevice()))
        XCTAssertEqual(0, (B64.decode(doc.ciphertext)!.count - 16) % 4096)
    }

    func testRejectsTamperingSubstitutionAndWrongRoles() throws {
        let doc = try seal()
        var changedVersion = doc
        changedVersion.header.version = 8
        XCTAssertEqual(.rejected("signature"), DocSealer.open(changedVersion, chain: chain, me: b))
        var swappedCiphertext = doc
        swappedCiphertext.ciphertext = try seal().ciphertext
        XCTAssertEqual(.rejected("signature"), DocSealer.open(swappedCiphertext, chain: chain, me: b))
        var dropped = doc
        dropped.recipients = doc.recipients.filter { $0.deviceId != pc.deviceId }
        XCTAssertEqual(.rejected("signature"), DocSealer.open(dropped, chain: chain, me: b))
        let other = try seal()
        var swapped = doc
        swapped.recipients = doc.recipients.map { r in r.deviceId == b.deviceId ? other.recipients.first { $0.deviceId == b.deviceId }! : r }
        XCTAssertEqual(.rejected("signature"), DocSealer.open(swapped, chain: chain, me: b))
        XCTAssertThrowsError(try seal(signer: TestDevice()))
        var resigned = doc
        resigned.signature = try seal(signer: b).signature
        XCTAssertEqual(.rejected("signature"), DocSealer.open(resigned, chain: chain, me: b))
        XCTAssertEqual(.rejected("role"), DocSealer.open(try seal(signer: pc), chain: chain, me: a))
        XCTAssertTrue(isOk(DocSealer.open(try seal(signer: pc, kind: .command), chain: chain, me: a)))
        let otherHousehold = try [try MemberChain.genesis(signer: b, keys: b.keys)].verified()
        XCTAssertEqual(.rejected("household"), DocSealer.open(doc, chain: otherHousehold, me: b))
    }

    func testHeadOfTheWritersListIsChecked() throws {
        let doc = try seal()
        XCTAssertEqual(.needChain(headSeq: chain.headSeq), DocSealer.open(doc, chain: try records.verified(), me: b))
        let branch = try (records + [try MemberChain.add(signer: b, chain: try records.verified(), target: TestDevice().keys)]).verified()
        XCTAssertEqual(.fork, DocSealer.open(doc, chain: branch, me: b))
        let afterRemove = try (withPc + [try MemberChain.remove(signer: b, chain: chain, deviceId: pc.deviceId)]).verified()
        guard case .ok(_, _, let stale) = DocSealer.open(doc, chain: afterRemove, me: b) else { return XCTFail("開けない") }
        XCTAssertTrue(stale)
        let removedWriterChain = try (withPc + [try MemberChain.remove(signer: a, chain: chain, deviceId: b.deviceId)]).verified()
        XCTAssertEqual(.rejected("writer_not_member"), DocSealer.open(try seal(signer: b), chain: removedWriterChain, me: a))
        XCTAssertFalse(try seal(onChain: afterRemove).recipients.contains { $0.deviceId == pc.deviceId })
    }
}

final class PlaybackArbiterTests: XCTestCase {
    private typealias Arbiter = PlaybackArbiter

    private func decide(_ mine: Arbiter.Session, _ evidence: Arbiter.Evidence, _ myOrder: Int64? = nil, _ otherOrder: Int64? = nil) -> Arbiter.Result {
        Arbiter.decide(myId: "A", mine: mine, evidence: evidence, myClaimOrder: myOrder, otherClaimOrder: otherOrder)
    }

    func testLaterSessionWinsRegardlessOfClaimOrders() {
        let a = Arbiter.Session(base: 9, startVersion: 10)
        XCTAssertEqual(Arbiter.Result(.stop, .otherStartedAfterMine), decide(a, .yield(from: "B", sessionBase: 12), 9, 1))
        XCTAssertEqual(.stop, decide(a, .playingCursor(from: "B", sessionBase: 10, sessionStartVersion: 13)).decision)
        XCTAssertEqual(Arbiter.Result(.keep, .mineStartedAfterOther), decide(a, .playingCursor(from: "B", sessionBase: 4, sessionStartVersion: 5), 1, 9))
    }

    func testConcurrentStartsAreSettledByClaimOrderAndDeferredUntilKnown() {
        let a = Arbiter.Session(base: 20, startVersion: 21)
        let bCursor = Arbiter.Evidence.playingCursor(from: "B", sessionBase: 20, sessionStartVersion: 22)
        XCTAssertEqual(Arbiter.Result(.stop, .concurrent), decide(a, bCursor, 5, 6))
        XCTAssertEqual(Arbiter.Result(.keep, .concurrent), decide(a, bCursor, 6, 5))
        XCTAssertEqual(.defer, decide(a, bCursor, nil, 6).decision)
        XCTAssertEqual(.defer, decide(a, bCursor, 5, nil).decision)
        let offlineStart = Arbiter.Session(base: 20, startVersion: nil)
        XCTAssertEqual(.stop, decide(offlineStart, .yield(from: "B", sessionBase: 20), 3, 4).decision)
        XCTAssertEqual(.keep, decide(offlineStart, .yield(from: "B", sessionBase: 20), 4, 3).decision)
        XCTAssertEqual(
            .selfEvidence,
            Arbiter.decide(
                myId: "B", mine: Arbiter.Session(base: 30, startVersion: 31), evidence: .playingCursor(from: "B", sessionBase: 30, sessionStartVersion: 31),
                myClaimOrder: nil, otherClaimOrder: nil
            ).reason)
    }
}
