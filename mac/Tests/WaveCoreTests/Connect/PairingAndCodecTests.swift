import Foundation
import XCTest

@testable import WaveCore

final class PairingTests: XCTestCase {
    private var now: Int64 = 0
    private let idA = SoftwareDevice.generate()
    private let idB = SoftwareDevice.generate()

    private func responder(account: String = "account-1", extension ext: String? = nil) -> Pairing.Responder {
        Pairing.Responder(identity: idA, deviceName: "Pixel 3", accountId: account, nowMs: { [unowned self] in self.now }, confirmExtension: ext)
    }

    private func initiator(_ offer: Pairing.Offer, _ identity: DeviceSigner, account: String = "account-1", extension ext: String? = nil) throws
        -> Pairing.Initiator
    {
        try Pairing.Initiator(offer: offer, identity: identity, deviceName: "Windows", accountId: account, helloExtension: ext)
    }

    func testHonestPairingAgreesOnTheCodeAndDirectionalKeys() throws {
        let a = responder(extension: #"{"householdId":"h"}"#)
        let offer = try XCTUnwrap(Pairing.decodeOffer(Pairing.encodeOffer(a.offer)))
        let b = try initiator(offer, idB, extension: #"{"deviceId":"d"}"#)
        let confirmed = try b.onConfirm(try a.onHello(try b.hello()).get()).get()
        XCTAssertNotNil(confirmed.code.range(of: "^[0-9]{6}$", options: .regularExpression))
        let onA = try a.onCodeEntered(confirmed.code).get()
        XCTAssertEqual(onA.sendKey, confirmed.pair.receiveKey)
        XCTAssertEqual(onA.receiveKey, confirmed.pair.sendKey)
        XCTAssertEqual(onA.accountKey, confirmed.pair.accountKey)
        XCTAssertEqual(idB.identityPub, onA.peerPublicKey)
        XCTAssertEqual(idA.identityPub, confirmed.pair.peerPublicKey)
        XCTAssertEqual(#"{"deviceId":"d"}"#, onA.peerExtension)
        XCTAssertEqual(#"{"householdId":"h"}"#, confirmed.pair.peerExtension)
    }

    func testRejectsWrongCodesAccountsExpiryAndReuse() throws {
        let a = responder()
        _ = try a.onHello(try initiator(a.offer, idB).hello()).get()
        XCTAssertEqual(.alreadyUsed, try a.onHello(try initiator(a.offer, idB).hello()).failure)
        XCTAssertEqual(.wrongCode, a.onCodeEntered("x").failure)
        XCTAssertEqual(.wrongCode, a.onCodeEntered("x").failure)
        XCTAssertEqual(.tooManyAttempts, a.onCodeEntered("x").failure)

        let other = responder(account: "account-1")
        XCTAssertEqual(.accountMismatch, try other.onHello(try initiator(other.offer, idB, account: "account-2").hello()).failure)

        let late = responder()
        now += Pairing.lifetimeMs + 1
        XCTAssertEqual(.expired, try late.onHello(try initiator(late.offer, idB).hello()).failure)

        let selfPair = responder()
        XCTAssertEqual(.malformed, try selfPair.onHello(try initiator(selfPair.offer, idA).hello()).failure)
    }

    func testRelayCannotSwapKeysOrForgeSignatures() throws {
        let a = responder()
        var hello = try initiator(a.offer, idB).hello()
        let swap = WaveCrypto.generateEphemeralKeyPair()
        hello.eB = B64.encode(WaveCrypto.encodePublicKey(swap))
        XCTAssertEqual(.malformed, try a.onHello(hello).failure)
        // pkB に対応しない鍵で署名した Hello
        let forger = ForgedSigner(claimedPub: idB.identityPub, real: SoftwareDevice.generate())
        XCTAssertEqual(.badSignature, try a.onHello(try initiator(a.offer, forger).hello()).failure)
    }

    func testOffersAndNamesAreValidated() throws {
        XCTAssertNil(Pairing.decodeOffer("https://example.com"))
        XCTAssertNil(Pairing.decodeOffer("wave-pair:!!!"))
        let a = responder()
        var shortToken = a.offer
        shortToken.token = "AAAA"
        XCTAssertNil(Pairing.decodeOffer(Pairing.encodeOffer(shortToken)))
        var v2 = a.offer
        v2.v = 2
        XCTAssertNil(Pairing.decodeOffer(Pairing.encodeOffer(v2)))
        // QR の形は Kotlin と同じ JSON（{"v":1,"pkA":...,"eA":...,"token":...}）
        let text = Pairing.encodeOffer(a.offer)
        let json = try JSON.parse(try XCTUnwrap(B64.decode(String(text.dropFirst("wave-pair:".count)))))
        XCTAssertEqual(["v", "pkA", "eA", "token"], json.obj?.keys)
        XCTAssertEqual("abc", Pairing.sanitizeName("a\u{202E}b\u{0000}c"))
        XCTAssertEqual("端末", Pairing.sanitizeName("\u{0007}"))
    }

    private final class ForgedSigner: DeviceSigner {
        let claimedPub: Data
        let real: SoftwareDevice

        init(claimedPub: Data, real: SoftwareDevice) {
            self.claimedPub = claimedPub
            self.real = real
        }

        var deviceId: String { WaveCrypto.deviceId(claimedPub) }
        var identityPub: Data { claimedPub }
        func sign(label: String, message: Data) throws -> Data { try real.sign(label: label, message: message) }
    }
}

final class HandoffCodecTests: XCTestCase {
    private func sample() -> HandoffSnapshot { InteropTests.sampleSnapshot() }

    func testValidatesShapeAndClampsTimes() {
        var far = sample()
        far.positionMs = 999_999_999
        let text = HandoffCodec.encode(far)
        guard case .ok(let ok) = HandoffCodec.decode(text, expectedHandoffId: sample().handoffId) else { return XCTFail("読めない") }
        XCTAssertEqual(261_000, ok.positionMs)
        XCTAssertEqual(.rejected("handoff_id"), HandoffCodec.decode(text, expectedHandoffId: "00000000-0000-4000-8000-000000000009"))
        var v2 = sample()
        v2.schemaVersion = 2
        XCTAssertEqual(.unsupportedVersion(2), HandoffCodec.decode(HandoffCodec.encode(v2), expectedHandoffId: sample().handoffId))
        XCTAssertEqual(.unsupportedVersion(nil), HandoffCodec.decode("{}", expectedHandoffId: sample().handoffId))
        XCTAssertEqual(.rejected("unreadable"), HandoffCodec.decode("[", expectedHandoffId: sample().handoffId))
        XCTAssertEqual(.rejected("unreadable"), HandoffCodec.decode(#"{"schemaVersion":1}"#, expectedHandoffId: sample().handoffId))
        var badThumb = sample()
        badThumb.items[0].thumbnailUrl = "https://evil.example/x.jpg"
        guard case .ok(let thumbOk) = HandoffCodec.decode(HandoffCodec.encode(badThumb), expectedHandoffId: sample().handoffId) else {
            return XCTFail("読めない")
        }
        XCTAssertNil(thumbOk.items[0].thumbnailUrl)
        var badVideo = sample()
        badVideo.items = [sample().items[0]]
        badVideo.items[0].videoId = "short"
        XCTAssertEqual(.rejected("video_id"), HandoffCodec.decode(HandoffCodec.encode(badVideo), expectedHandoffId: sample().handoffId))
    }

    func testEscapesLikeKotlinx() {
        // 必要な文字だけを逃がす（制御文字は短い形か \u00xx の小文字）
        XCTAssertEqual(
            #"{"name":"a\"b\\c\n\u0001日本🎵<>&'"}"#, ConnectJSON.encode(ProfileBody(name: "a\"b\\c\n\u{0001}日本🎵<>&'")))
    }

    func testWindowKeepsTwentyBeforeAndThreeHundredAfter() {
        let items = Array(0..<400)
        let (window, index, dropped) = HandoffCodec.window(items, currentIndex: 50)
        XCTAssertEqual(30, window[0])
        XCTAssertEqual(20, index)
        XCTAssertEqual(321, window.count)
        XCTAssertTrue(dropped)
    }
}
