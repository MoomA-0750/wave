import CryptoKit
import Foundation

/// RFC 9180 HPKE の base モード、`DHKEM(P-256, HKDF-SHA256)`・`HKDF-SHA256`・`AES-128-GCM`（Android 版の Hpke と同じ）。
/// 付録 A.3 のテストベクタで確かめる。書き込みごとの内容鍵を、家の端末それぞれの受け取りの鍵（kx）へ包むのに使う。
/// 受け手の DH は関数で受ける。公開鍵の送る形は非圧縮 SEC1（65バイト）。
/// CryptoKit の HPKE は受け手の DH を関数で受けられないので使わず、Windows 版（Hpke.cs）と同じ手順で組む。
public enum Hpke {
    private static let kemId = 0x0010
    private static let kdfId = 0x0001
    private static let aeadId = 0x0001
    private static let modeBase: UInt8 = 0x00
    private static let nSecret = 32
    private static let nK = 16
    private static let nN = 12
    public static let nEnc = 65

    private static let versionLabel = Data("HPKE-v1".utf8)
    private static let kemSuite = Data("KEM".utf8) + i2osp(Int64(kemId), 2)
    private static let hpkeSuite = Data("HPKE".utf8) + i2osp(Int64(kemId), 2) + i2osp(Int64(kdfId), 2) + i2osp(Int64(aeadId), 2)

    /// P-256 の位数 n（ビッグエンディアン）。
    private static let order: [UInt8] = [
        0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xbc, 0xe6, 0xfa, 0xad, 0xa7, 0x17, 0x9e, 0x84, 0xf3, 0xb9, 0xca, 0xc2, 0xfc, 0x63, 0x25, 0x51,
    ]

    /// 封じた結果。enc は使い捨ての鍵の公開鍵（SEC1）。
    public struct Sealed: Equatable, Sendable {
        public let enc: Data
        public let ciphertext: Data
    }

    /// 受け手の公開鍵へ、1回だけの暗号化（シーケンス番号 0）。
    public static func seal(recipient: EcPublicKey, info: Data, aad: Data, plaintext: Data) throws -> Sealed {
        try seal(recipient: recipient, info: info, aad: aad, plaintext: plaintext, ephemeral: WaveCrypto.generateEphemeralKeyPair())
    }

    /// テスト用: 使い捨ての鍵を外から与える。
    static func seal(recipient: EcPublicKey, info: Data, aad: Data, plaintext: Data, ephemeral: P256.KeyAgreement.PrivateKey) throws -> Sealed {
        let (sharedSecret, enc) = try encap(recipient: recipient, ephemeral: ephemeral)
        return Sealed(enc: enc, ciphertext: try keySchedule(sharedSecret: sharedSecret, info: info).seal(sequence: 0, aad: aad, plaintext: plaintext))
    }

    /// 開く。認証に失敗したら nil。
    /// - Parameters:
    ///   - recipientPublic: 受け手自身の公開鍵（kem_context に入る）
    ///   - dh: 受け手の秘密鍵で、渡された公開鍵との ECDH の x 座標（32バイト）を返す
    public static func open(enc: Data, recipientPublic: EcPublicKey, dh: (EcPublicKey) throws -> Data, info: Data, aad: Data, ciphertext: Data) -> Data? {
        guard let sharedSecret = decap(enc: enc, recipientPublic: recipientPublic, dh: dh) else { return nil }
        return keySchedule(sharedSecret: sharedSecret, info: info).open(sequence: 0, aad: aad, ciphertext: ciphertext)
    }

    /// 非圧縮 SEC1（0x04 ‖ X ‖ Y）。
    public static func serializePublicKey(_ key: EcPublicKey) -> Data { key.x963 }

    /// 非圧縮 SEC1 を読む。長さ・形・曲線上の点を確かめる（曲線上の点かは CryptoKit の読み込みが確かめる）。
    public static func deserializePublicKey(_ encoded: Data) -> EcPublicKey? {
        let bytes = [UInt8](encoded)
        if bytes.count != nEnc || bytes[0] != 0x04 { return nil }
        guard let key = try? P256.KeyAgreement.PublicKey(x963Representation: bytes) else { return nil }
        return WaveCrypto.decodePublicKey(key.derRepresentation)
    }

    /// テスト用: 付録 A.3 の ikm から秘密鍵のスカラーを導く（DeriveKeyPair、P-256 のビットマスクは 0xff）。ビッグエンディアンの32バイト。
    static func deriveSecretScalar(_ ikm: Data) -> Data {
        let prk = labeledExtract(kemSuite, salt: Data(), label: "dkp_prk", ikm: ikm)
        for counter in 0...255 {
            let bytes = [UInt8](labeledExpand(kemSuite, prk: prk, label: "candidate", info: Data([UInt8(counter)]), length: nSecret))
            // 0 < candidate < n（同じ長さのビッグエンディアンなので辞書順で比べられる）
            if bytes.contains(where: { $0 != 0 }) && bytes.lexicographicallyPrecedes(order) { return Data(bytes) }
        }
        preconditionFailure("DeriveKeyPair failed")
    }

    /// テスト用: 秘密のスカラーから ECDH の鍵の組を作る。
    static func keyPairOf(_ secret: Data) throws -> P256.KeyAgreement.PrivateKey {
        try P256.KeyAgreement.PrivateKey(rawRepresentation: secret)
    }

    private static func encap(recipient: EcPublicKey, ephemeral: P256.KeyAgreement.PrivateKey) throws -> (sharedSecret: Data, enc: Data) {
        guard let validated = WaveCrypto.decodePublicKey(recipient.spki) else { throw ConnectError.invalidArgument("invalid recipient key") }
        let dh = try WaveCrypto.ecdh(ephemeral, validated)
        let enc = serializePublicKey(WaveCrypto.publicKeyOf(ephemeral))
        return (extractAndExpand(dh: dh, kemContext: enc + serializePublicKey(validated)), enc)
    }

    /// テスト用: 付録 A.3 の shared_secret の確認。
    static func encapForTest(recipient: EcPublicKey, ephemeral: P256.KeyAgreement.PrivateKey) throws -> (sharedSecret: Data, enc: Data) {
        try encap(recipient: recipient, ephemeral: ephemeral)
    }

    private static func decap(enc: Data, recipientPublic: EcPublicKey, dh: (EcPublicKey) throws -> Data) -> Data? {
        guard let ephemeral = deserializePublicKey(enc) else { return nil }
        guard let shared = try? dh(ephemeral) else { return nil }
        if shared.count != nSecret { return nil }
        return extractAndExpand(dh: shared, kemContext: Data(enc) + serializePublicKey(recipientPublic))
    }

    private static func extractAndExpand(dh: Data, kemContext: Data) -> Data {
        let prk = labeledExtract(kemSuite, salt: Data(), label: "eae_prk", ikm: dh)
        return labeledExpand(kemSuite, prk: prk, label: "shared_secret", info: kemContext, length: nSecret)
    }

    struct Context {
        let key: Data
        let baseNonce: Data
        let exporterSecret: Data

        func seal(sequence: Int64, aad: Data, plaintext: Data) throws -> Data {
            try WaveCrypto.aesGcmSeal(key: key, nonce: nonceFor(sequence), aad: aad, plaintext: plaintext)
        }

        func open(sequence: Int64, aad: Data, ciphertext: Data) -> Data? {
            WaveCrypto.aesGcmOpen(key: key, nonce: nonceFor(sequence), aad: aad, ciphertext: ciphertext)
        }

        private func nonceFor(_ sequence: Int64) -> Data {
            var nonce = [UInt8](baseNonce)
            let seq = [UInt8](i2osp(sequence, nN))
            for i in 0..<nonce.count { nonce[i] ^= seq[i] }
            return Data(nonce)
        }
    }

    static func keySchedule(sharedSecret: Data, info: Data) -> Context {
        let pskIdHash = labeledExtract(hpkeSuite, salt: Data(), label: "psk_id_hash", ikm: Data())
        let infoHash = labeledExtract(hpkeSuite, salt: Data(), label: "info_hash", ikm: info)
        let keyScheduleContext = Data([modeBase]) + pskIdHash + infoHash
        let secret = labeledExtract(hpkeSuite, salt: sharedSecret, label: "secret", ikm: Data())
        return Context(
            key: labeledExpand(hpkeSuite, prk: secret, label: "key", info: keyScheduleContext, length: nK),
            baseNonce: labeledExpand(hpkeSuite, prk: secret, label: "base_nonce", info: keyScheduleContext, length: nN),
            exporterSecret: labeledExpand(hpkeSuite, prk: secret, label: "exp", info: keyScheduleContext, length: nSecret))
    }

    private static func labeledExtract(_ suite: Data, salt: Data, label: String, ikm: Data) -> Data {
        Hkdf.extract(salt: salt, ikm: versionLabel + suite + WaveCrypto.utf8(label) + ikm)
    }

    private static func labeledExpand(_ suite: Data, prk: Data, label: String, info: Data, length: Int) -> Data {
        Hkdf.expand(prk: prk, info: i2osp(Int64(length), 2) + versionLabel + suite + WaveCrypto.utf8(label) + info, length: length)
    }

    private static func i2osp(_ value: Int64, _ length: Int) -> Data {
        var output = [UInt8](repeating: 0, count: length)
        for i in 0..<length {
            let shift = length - 1 - i
            output[i] = shift >= 8 ? 0 : UInt8(truncatingIfNeeded: UInt64(bitPattern: value) >> (8 * UInt64(shift)))
        }
        return Data(output)
    }
}
