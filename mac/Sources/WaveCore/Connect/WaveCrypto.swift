import CryptoKit
import Foundation

/// 連携（Connect v2）の呼び出しの誤り（C# の ArgumentException に当たる。値は入れない）。
public enum ConnectError: Error, Equatable {
    case invalidArgument(String)
}

/// base64url（パディングなし）。読むときは Java の URL デコーダと同じく、パディングの有無どちらも受ける。
public enum B64 {
    public static func encode<D: DataProtocol>(_ data: D) -> String {
        var text = Data(data).base64EncodedString()
        while text.hasSuffix("=") { text.removeLast() }
        return text.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }

    /// 読めなければ nil（URL 用でない文字・空白・長さの崩れたもの）。
    public static func decode(_ text: String?) -> Data? {
        guard let text else { return nil }
        var body = Substring(text)
        var padding = 0
        while body.hasSuffix("=") && padding < 3 {
            body = body.dropLast()
            padding += 1
        }
        if padding > 0 && (padding > 2 || text.utf16.count % 4 != 0) { return nil }
        if body.utf16.count % 4 == 1 { return nil }
        for c in body.unicodeScalars {
            switch c {
            case "A"..."Z", "a"..."z", "0"..."9", "-", "_": continue
            default: return nil
            }
        }
        var standard = body.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        standard += String(repeating: "=", count: (4 - standard.utf16.count % 4) % 4)
        return Data(base64Encoded: standard)
    }
}

/// 検証済みの P-256 の公開鍵。送る形（X.509 SubjectPublicKeyInfo、正規形）と座標を持つ。
/// 作るのは `WaveCrypto.decodePublicKey` と `Hpke.deserializePublicKey`（と、CryptoKit の鍵から取り出すとき）だけ（曲線上の点を確かめたもの）。
public final class EcPublicKey: Equatable, @unchecked Sendable {
    /// 送る形（SPKI）。
    public let spki: Data
    public let x: Data
    public let y: Data

    init(spki: Data, x: Data, y: Data) {
        self.spki = spki
        self.x = x
        self.y = y
    }

    /// CryptoKit の鍵から（型が P-256 を保証するので検証済み）。
    init(signing key: P256.Signing.PublicKey) {
        let point = [UInt8](key.x963Representation)
        spki = key.derRepresentation
        x = Data(point[1..<33])
        y = Data(point[33..<65])
    }

    init(agreement key: P256.KeyAgreement.PublicKey) {
        let point = [UInt8](key.x963Representation)
        spki = key.derRepresentation
        x = Data(point[1..<33])
        y = Data(point[33..<65])
    }

    /// 非圧縮 SEC1（0x04 ‖ X ‖ Y）。
    var x963: Data { Data([0x04]) + x + y }

    func signingKey() throws -> P256.Signing.PublicKey { try P256.Signing.PublicKey(x963Representation: x963) }

    func agreementKey() throws -> P256.KeyAgreement.PublicKey { try P256.KeyAgreement.PublicKey(x963Representation: x963) }

    public static func == (a: EcPublicKey, b: EcPublicKey) -> Bool { a.spki == b.spki }
}

/// Phase 6 の暗号の部品（Android 版の WaveCrypto と同じ形）。CryptoKit の ECDH・ECDSA・AES-GCM・HMAC だけを使い、暗号そのものは書かない。
/// 署名は JCA と同じ DER 形式、ECDH の共有秘密は x 座標そのもの（ハッシュしない）。曲線は P-256。
public enum WaveCrypto {
    /// 使い捨ての鍵（ペアリング・HPKE）。メモリだけに置く。
    public static func generateEphemeralKeyPair() -> P256.KeyAgreement.PrivateKey { P256.KeyAgreement.PrivateKey() }

    /// 身元の署名鍵（ソフトウェアで持つ場合）。
    public static func generateSigningKey() -> P256.Signing.PrivateKey { P256.Signing.PrivateKey() }

    /// 公開鍵の送る形（X.509 SubjectPublicKeyInfo）。
    public static func encodePublicKey(_ key: P256.Signing.PrivateKey) -> Data { key.publicKey.derRepresentation }

    public static func encodePublicKey(_ key: P256.KeyAgreement.PrivateKey) -> Data { key.publicKey.derRepresentation }

    /// 鍵の組の公開鍵を、検証済みの形で取り出す。
    public static func publicKeyOf(_ key: P256.Signing.PrivateKey) -> EcPublicKey { EcPublicKey(signing: key.publicKey) }

    public static func publicKeyOf(_ key: P256.KeyAgreement.PrivateKey) -> EcPublicKey { EcPublicKey(agreement: key.publicKey) }

    /// 受け取った公開鍵を読む。P-256 で、正規形で、曲線上の点のときだけ（不正な曲線の点を使った攻撃を防ぐ）。
    /// 曲線上の点かは CryptoKit の読み込みが確かめる（曲線の外の点は読み込めない）。
    public static func decodePublicKey(_ encoded: Data) -> EcPublicKey? {
        if encoded.count > 200 { return nil }
        guard let key = try? P256.Signing.PublicKey(derRepresentation: encoded) else { return nil }
        // 送る形を正規化したものだけ（圧縮点など、読み直すと別のバイト列になる形を受けると、端末と中継で端末 ID が食い違う）
        guard key.derRepresentation == encoded else { return nil }
        let point = [UInt8](key.x963Representation)
        guard point.count == 65, point[0] == 0x04 else { return nil }
        return EcPublicKey(spki: Data(encoded), x: Data(point[1..<33]), y: Data(point[33..<65]))
    }

    /// 端末 ID・指紋: 署名公開鍵の送る形の SHA-256。
    public static func fingerprint(_ encodedPublicKey: Data) -> Data { sha256(encodedPublicKey) }

    /// 端末 ID（指紋の base64url）。
    public static func deviceId(_ encodedPublicKey: Data) -> String { B64.encode(fingerprint(encodedPublicKey)) }

    public static func sha256(_ parts: Data...) -> Data { sha256(parts) }

    public static func sha256(_ parts: [Data]) -> Data {
        var hash = SHA256()
        for part in parts { hash.update(data: part) }
        return Data(hash.finalize())
    }

    /// ECDH の共有秘密（x 座標そのもの、32バイト）。
    public static func ecdh(_ privateKey: P256.KeyAgreement.PrivateKey, _ publicKey: EcPublicKey) throws -> Data {
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey.agreementKey())
        return secret.withUnsafeBytes { Data($0) }
    }

    /// 用途ラベルを前置して署名する（DER 形式）。
    public static func sign(_ privateKey: P256.Signing.PrivateKey, label: String, message: Data) throws -> Data {
        try privateKey.signature(for: labeled(label, message)).derRepresentation
    }

    public static func verify(_ publicKey: EcPublicKey, label: String, message: Data, signature: Data) -> Bool {
        guard let key = try? publicKey.signingKey(),
              let parsed = try? P256.Signing.ECDSASignature(derRepresentation: signature)
        else { return false }
        return key.isValidSignature(parsed, for: labeled(label, message))
    }

    public static func hmacSha256(_ key: Data, _ parts: Data...) -> Data { hmacSha256(key, parts) }

    public static func hmacSha256(_ key: Data, _ parts: [Data]) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key))
        for part in parts { mac.update(data: part) }
        return Data(mac.finalize())
    }

    /// AES-256-GCM で封じる。nonce は毎回 96 ビットの乱数。暗号文は JCA と同じく末尾に 16 バイトのタグを付ける。
    public static func seal(key: Data, aad: Data, plaintext: Data) throws -> (nonce: Data, ciphertext: Data) {
        if key.count != 32 { throw ConnectError.invalidArgument("key は 32 バイト") }
        let nonce = randomBytes(12)
        return (nonce, try aesGcmSeal(key: key, nonce: nonce, aad: aad, plaintext: plaintext))
    }

    /// 認証に失敗したら nil。
    public static func open(key: Data, nonce: Data, aad: Data, ciphertext: Data) -> Data? {
        if key.count != 32 || nonce.count != 12 { return nil }
        return aesGcmOpen(key: key, nonce: nonce, aad: aad, ciphertext: ciphertext)
    }

    static func aesGcmSeal(key: Data, nonce: Data, aad: Data, plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        return box.ciphertext + box.tag
    }

    static func aesGcmOpen(key: Data, nonce: Data, aad: Data, ciphertext: Data) -> Data? {
        if ciphertext.count < 16 { return nil }
        let bytes = [UInt8](ciphertext)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce), ciphertext: bytes[0..<(bytes.count - 16)], tag: bytes[(bytes.count - 16)...])
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            return nil
        }
    }

    public static func randomBytes(_ size: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        var generator = SystemRandomNumberGenerator()
        for i in 0..<size { bytes[i] = UInt8.random(in: 0...255, using: &generator) }
        return Data(bytes)
    }

    /// 定数時間の比較。
    public static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        if a.count != b.count { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    /// 長さ付きで連結する（ビッグエンディアンの4バイト長 ‖ 中身 を並べる）。
    public static func lengthPrefixed(_ parts: Data...) -> Data { lengthPrefixed(parts) }

    public static func lengthPrefixed(_ parts: [Data]) -> Data {
        var output = Data()
        output.reserveCapacity(parts.reduce(0) { $0 + 4 + $1.count })
        for part in parts {
            let n = UInt32(part.count)
            output.append(contentsOf: [UInt8(n >> 24 & 0xff), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)])
            output.append(part)
        }
        return output
    }

    /// Kotlin の encodeToByteArray と同じ UTF-8。
    static func utf8(_ text: String) -> Data { Data(text.utf8) }

    private static func labeled(_ label: String, _ message: Data) -> Data { lengthPrefixed(utf8(label), message) }

    /// PKCS#8（または SEC1）の P-256 の秘密鍵から、秘密のスカラー（32バイト）を取り出す。CryptoKit が読めない形（公開鍵の無い PKCS#8 など）に使う。
    static func privateScalar(fromDer der: Data) -> Data? {
        // DER を浅くたどり、ECPrivateKey（SEQUENCE { INTEGER 1, OCTET STRING(32) ... }）の OCTET STRING を探す
        let bytes = [UInt8](der)
        func readLength(_ i: inout Int) -> Int? {
            guard i < bytes.count else { return nil }
            let first = Int(bytes[i]); i += 1
            if first < 0x80 { return first }
            let count = first & 0x7f
            guard count > 0, count <= 3, i + count <= bytes.count else { return nil }
            var length = 0
            for _ in 0..<count { length = length << 8 | Int(bytes[i]); i += 1 }
            return length
        }
        func search(_ start: Int, _ end: Int) -> Data? {
            var i = start
            while i < end {
                let tag = bytes[i]; i += 1
                guard let length = readLength(&i), i + length <= end else { return nil }
                let contentStart = i
                // INTEGER 1 の直後の OCTET STRING(32) が秘密のスカラー
                if tag == 0x02, length == 1, bytes[contentStart] == 0x01, contentStart + 1 < end, bytes[contentStart + 1] == 0x04 {
                    var j = contentStart + 2
                    if let n = readLength(&j), n == 32, j + 32 <= end { return Data(bytes[j..<(j + 32)]) }
                }
                if tag == 0x30 || tag == 0x04 {
                    if let found = search(contentStart, contentStart + length) { return found }
                }
                i = contentStart + length
            }
            return nil
        }
        return search(0, bytes.count)
    }
}

/// HKDF（RFC 5869）、HMAC-SHA256。
public enum Hkdf {
    private static let hashLen = 32

    public static func extract(salt: Data, ikm: Data) -> Data {
        WaveCrypto.hmacSha256(salt.isEmpty ? Data(count: hashLen) : salt, ikm)
    }

    public static func expand(prk: Data, info: Data, length: Int) -> Data {
        precondition(length >= 1 && length <= 255 * hashLen, "length")
        var output = Data()
        var previous = Data()
        var counter: UInt8 = 1
        while output.count < length {
            previous = WaveCrypto.hmacSha256(prk, previous, info, Data([counter]))
            output.append(previous)
            counter &+= 1
        }
        return Data(output.prefix(length))
    }
}
