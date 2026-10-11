import CryptoKit
import Foundation
import XCTest

@testable import WaveCore

/// テストの道具。
enum ConnectTestSupport {
    static func hex(_ s: String) -> Data {
        let chars = Array(s.replacingOccurrences(of: " ", with: ""))
        var bytes: [UInt8] = []
        var i = 0
        while i + 1 < chars.count {
            bytes.append(UInt8(String(chars[i...i + 1]), radix: 16)!)
            i += 2
        }
        return Data(bytes)
    }

    struct NotVerified: Error {}

    /// テスト用の端末（役割を持つ）。
    final class TestDevice: DeviceSigner, KxOpener {
        private let inner = SoftwareDevice.generate()
        let role: Role

        init(_ role: Role = .player) { self.role = role }

        var deviceId: String { inner.deviceId }
        var identityPub: Data { inner.identityPub }
        var kxPub: EcPublicKey { inner.kxPub }
        func sign(label: String, message: Data) throws -> Data { try inner.sign(label: label, message: message) }
        func dh(_ peer: EcPublicKey) throws -> Data { try inner.dh(peer) }

        private var cachedKeys: MemberKeys?
        var keys: MemberKeys {
            if let cachedKeys { return cachedKeys }
            let made = try! inner.memberKeys(role: role)
            cachedKeys = made
            return made
        }
    }
}

extension Array where Element == ChainRecord {
    func verified() throws -> VerifiedChain {
        guard case .ok(let chain) = MemberChain.verify(self) else { throw ConnectTestSupport.NotVerified() }
        return chain
    }
}

final class ConnectCryptoTests: XCTestCase {
    private func hex(_ s: String) -> Data { ConnectTestSupport.hex(s) }

    func testHkdfMatchesRfc5869Case1() {
        let ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        let salt = hex("000102030405060708090a0b0c")
        let info = hex("f0f1f2f3f4f5f6f7f8f9")
        let prk = Hkdf.extract(salt: salt, ikm: ikm)
        XCTAssertEqual(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        XCTAssertEqual(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), Hkdf.expand(prk: prk, info: info, length: 42))
    }

    func testBase64UrlIsUnpaddedAndStrict() {
        XCTAssertEqual("-_8", B64.encode(Data([0xfb, 0xff])))
        XCTAssertEqual(Data([0xfb, 0xff]), B64.decode("-_8"))
        XCTAssertEqual(Data([0xfb, 0xff]), B64.decode("-_8="))
        XCTAssertNil(B64.decode("+/8"))
        XCTAssertNil(B64.decode("a b"))
        XCTAssertNil(B64.decode("A"))
        XCTAssertEqual(Data(), B64.decode(""))
    }

    func testSignaturesAreDerAndLabeled() throws {
        let key = WaveCrypto.generateSigningKey()
        let pub = WaveCrypto.publicKeyOf(key)
        let signature = try WaveCrypto.sign(key, label: "label", message: Data([1, 2, 3]))
        // DER の SEQUENCE（JCA の SHA256withECDSA と同じ形）
        XCTAssertEqual(0x30, signature.first)
        XCTAssertTrue(WaveCrypto.verify(pub, label: "label", message: Data([1, 2, 3]), signature: signature))
        XCTAssertFalse(WaveCrypto.verify(pub, label: "other", message: Data([1, 2, 3]), signature: signature))
        XCTAssertFalse(WaveCrypto.verify(pub, label: "label", message: Data([1, 2, 4]), signature: signature))
    }

    func testEcdhIsTheRawXCoordinate() throws {
        let a = WaveCrypto.generateEphemeralKeyPair()
        let b = WaveCrypto.generateEphemeralKeyPair()
        let ab = try WaveCrypto.ecdh(a, WaveCrypto.publicKeyOf(b))
        XCTAssertEqual(32, ab.count)
        XCTAssertEqual(ab, try WaveCrypto.ecdh(b, WaveCrypto.publicKeyOf(a)))
        // 共有点の x 座標そのもの: 秘密が 1 の鍵との ECDH は、相手の公開点（a の秘密 × 生成元）の x 座標になる
        // （Windows 版は素朴な楕円曲線の掛け算で確かめる。ここでは掛け算を書かずに、ハッシュしていないことを確かめる）
        let one = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0, count: 31) + Data([1]))
        XCTAssertEqual(WaveCrypto.publicKeyOf(a).x, try WaveCrypto.ecdh(one, WaveCrypto.publicKeyOf(a)))
        XCTAssertEqual(WaveCrypto.publicKeyOf(a).x, try WaveCrypto.ecdh(a, WaveCrypto.publicKeyOf(one)))
    }

    func testPublicKeysMustBeCanonicalP256() {
        let key = WaveCrypto.generateSigningKey()
        let spki = key.publicKey.derRepresentation
        XCTAssertNotNil(WaveCrypto.decodePublicKey(spki))
        // P-384 は受けない
        XCTAssertNil(WaveCrypto.decodePublicKey(P384.Signing.PrivateKey().publicKey.derRepresentation))
        // 圧縮点の SPKI（読み直すと別のバイト列になる形）は受けない
        XCTAssertNil(WaveCrypto.decodePublicKey(compressedSpki(key.publicKey)))
        // 末尾にごみ
        XCTAssertNil(WaveCrypto.decodePublicKey(spki + Data([0])))
        // 曲線外の点
        var bad = [UInt8](spki)
        bad[bad.count - 1] ^= 1
        XCTAssertNil(WaveCrypto.decodePublicKey(Data(bad)))
        XCTAssertNil(WaveCrypto.decodePublicKey(Data(count: 201)))
    }

    /// 同じ点を圧縮点（33バイト）で書いた SPKI。
    private func compressedSpki(_ key: P256.Signing.PublicKey) -> Data {
        let full = [UInt8](key.derRepresentation)
        // 非圧縮の SPKI の頭（26バイト）: 30 59 30 13 ...OID... 03 42 00 → 長さを詰め直す
        let algorithm = full[2..<23]  // 30 13 ... の AlgorithmIdentifier（21バイト）
        let compressed = [UInt8](key.compressedRepresentation)
        let bitString: [UInt8] = [0x03, UInt8(compressed.count + 1), 0x00] + compressed
        let body = Array(algorithm) + bitString
        return Data([0x30, UInt8(body.count)] + body)
    }

    func testAesGcmRoundTripsAndRejectsTampering() throws {
        let key = WaveCrypto.randomBytes(32)
        let (nonce, ciphertext) = try WaveCrypto.seal(key: key, aad: Data([9]), plaintext: Data([1, 2, 3]))
        XCTAssertEqual(3 + 16, ciphertext.count)
        XCTAssertEqual(Data([1, 2, 3]), WaveCrypto.open(key: key, nonce: nonce, aad: Data([9]), ciphertext: ciphertext))
        XCTAssertNil(WaveCrypto.open(key: key, nonce: nonce, aad: Data([8]), ciphertext: ciphertext))
        XCTAssertNil(WaveCrypto.open(key: key, nonce: nonce.prefix(11), aad: Data([9]), ciphertext: ciphertext))
    }

    func testLengthPrefixedIsBigEndian() {
        XCTAssertEqual(hex("00000002 0102 00000000"), WaveCrypto.lengthPrefixed(Data([1, 2]), Data()))
    }
}

/// RFC 9180 付録 A.3（DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM）の base モードのテストベクタ。
final class HpkeTests: XCTestCase {
    private func hex(_ s: String) -> Data { ConnectTestSupport.hex(s) }

    private lazy var info = hex("4f6465206f6e2061204772656369616e2055726e")
    private lazy var ikmE = hex("4270e54ffd08d79d5928020af4686d8f6b7d35dbe470265f1f5aa22816ce860e")
    private lazy var pkEm = hex(
        "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4")
    private lazy var skEm = hex("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb")
    private lazy var ikmR = hex("668b37171f1072f3cf12ea8a236a45df23fc13b82af3609ad1e354f6ef817550")
    private lazy var pkRm = hex(
        "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0")
    private lazy var skRm = hex("f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2")
    private lazy var sharedSecret = hex("c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8")
    private lazy var key = hex("868c066ef58aae6dc589b6cfdd18f97e")
    private lazy var baseNonce = hex("4e0bc5018beba4bf004cca59")
    private lazy var exporterSecret = hex("14ad94af484a7ad3ef40e9f3be99ecc6fa9036df9d4920548424df127ee0d99f")
    private lazy var pt = hex("4265617574792069732074727574682c20747275746820626561757479")

    private let encryptions: [(seq: Int64, aad: String, ct: String)] = [
        (0, "436f756e742d30", "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"),
        (1, "436f756e742d31", "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"),
        (2, "436f756e742d32", "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"),
        (4, "436f756e742d34", "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"),
        (255, "436f756e742d323535", "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"),
        (256, "436f756e742d323536", "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"),
    ]

    func testDeriveKeyPairMatchesVectors() {
        XCTAssertEqual(skEm, Hpke.deriveSecretScalar(ikmE))
        XCTAssertEqual(skRm, Hpke.deriveSecretScalar(ikmR))
    }

    func testEncapAndKeyScheduleMatchVectors() throws {
        let pkE = try XCTUnwrap(Hpke.deserializePublicKey(pkEm))
        let pkR = try XCTUnwrap(Hpke.deserializePublicKey(pkRm))
        XCTAssertEqual(pkEm, Hpke.serializePublicKey(pkE))
        let ephemeral = try Hpke.keyPairOf(skEm)
        XCTAssertEqual(pkE, WaveCrypto.publicKeyOf(ephemeral))
        let (shared, enc) = try Hpke.encapForTest(recipient: pkR, ephemeral: ephemeral)
        XCTAssertEqual(pkEm, enc)
        XCTAssertEqual(sharedSecret, shared)
        let context = Hpke.keySchedule(sharedSecret: shared, info: info)
        XCTAssertEqual(key, context.key)
        XCTAssertEqual(baseNonce, context.baseNonce)
        XCTAssertEqual(exporterSecret, context.exporterSecret)
        for (seq, aad, ct) in encryptions {
            XCTAssertEqual(hex(ct), try context.seal(sequence: seq, aad: hex(aad), plaintext: pt))
            XCTAssertEqual(pt, context.open(sequence: seq, aad: hex(aad), ciphertext: hex(ct)))
        }
    }

    func testOpenWithRecipientDhRoundTripsAndRejectsTampering() throws {
        let pkR = try XCTUnwrap(Hpke.deserializePublicKey(pkRm))
        let recipient = try Hpke.keyPairOf(skRm)
        let dh: (EcPublicKey) throws -> Data = { try WaveCrypto.ecdh(recipient, $0) }
        let sealedBox = try Hpke.seal(recipient: pkR, info: info, aad: Data([1]), plaintext: pt)
        XCTAssertEqual(pt, Hpke.open(enc: sealedBox.enc, recipientPublic: pkR, dh: dh, info: info, aad: Data([1]), ciphertext: sealedBox.ciphertext))
        XCTAssertNil(Hpke.open(enc: sealedBox.enc, recipientPublic: pkR, dh: dh, info: info, aad: Data([2]), ciphertext: sealedBox.ciphertext))
        XCTAssertNil(Hpke.open(enc: sealedBox.enc, recipientPublic: pkR, dh: dh, info: Data([0]), aad: Data([1]), ciphertext: sealedBox.ciphertext))
        var flipped = [UInt8](sealedBox.ciphertext)
        flipped[0] ^= 1
        XCTAssertNil(Hpke.open(enc: sealedBox.enc, recipientPublic: pkR, dh: dh, info: info, aad: Data([1]), ciphertext: Data(flipped)))
        let other = WaveCrypto.generateEphemeralKeyPair()
        XCTAssertNil(
            Hpke.open(
                enc: Hpke.serializePublicKey(WaveCrypto.publicKeyOf(other)), recipientPublic: pkR, dh: dh, info: info, aad: Data([1]),
                ciphertext: sealedBox.ciphertext))
        let otherPub = WaveCrypto.publicKeyOf(other)
        XCTAssertNil(
            Hpke.open(
                enc: sealedBox.enc, recipientPublic: otherPub, dh: { try WaveCrypto.ecdh(other, $0) }, info: info, aad: Data([1]),
                ciphertext: sealedBox.ciphertext))
    }

    func testRejectsMalformedEncodedPoints() {
        let bytes = [UInt8](pkEm)
        XCTAssertNil(Hpke.deserializePublicKey(Data(bytes[0..<64])))
        XCTAssertNil(Hpke.deserializePublicKey(Data([0x02] + bytes[1..<33])))
        XCTAssertNil(Hpke.deserializePublicKey(Data([0x00])))
        XCTAssertNil(Hpke.deserializePublicKey(Data([0x04] + [UInt8](repeating: 0, count: 64))))
        var offCurve = bytes
        offCurve[64] ^= 1
        XCTAssertNil(Hpke.deserializePublicKey(Data(offCurve)))
        var prefixed = bytes
        prefixed[0] = 0x03
        XCTAssertNil(Hpke.deserializePublicKey(Data(prefixed)))
    }
}
