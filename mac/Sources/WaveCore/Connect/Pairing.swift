import CryptoKit
import Foundation

/// ペアリングの手順（Android 版の Pairing と同じ、ADR-0008）。通信に依らない純粋なロジック。A = QR を出す端末、B = QR を読む端末。
/// 1. A は使い捨ての鍵 eA を作り、QR `{v, pkA, eA, token}` を出す
/// 2. B は使い捨ての鍵 eB を作り、ECDH(eB, eA) から導いた鍵で `{pkB, sigB(transcript), 端末名, アカウントの HMAC}` を封じて、eB と一緒に A へ送る
/// 3. A は開いて sigB を確かめ、`{sigA(transcript), 端末名, アカウントの HMAC}` を封じて返す
/// 4. B は sigA を確かめ、確認用の6桁を表示する。A は本人がその6桁を入力し、一致したときだけペアを確定する
/// JSON は Kotlin の Pairing の Json（ignoreUnknownKeys のみ。既定値の項目は出さない）と同じ形。
public enum Pairing {
    public static let version = 1
    public static let tokenBytes = 16
    public static let lifetimeMs: Int64 = 2 * 60 * 1000
    public static let maxAttempts = 3
    private static let maxExtensionChars = 2048
    private static let label = "wave-pair-v1"
    private static let offerPrefix = "wave-pair:"

    public struct Offer: Equatable, Sendable {
        public var v: Int
        public var pkA: String
        public var eA: String
        public var token: String

        public init(v: Int, pkA: String, eA: String, token: String) {
            self.v = v
            self.pkA = pkA
            self.eA = eA
            self.token = token
        }

        func toJSON() -> JSON { .obj(["v": JSON(v), "pkA": .string(pkA), "eA": .string(eA), "token": .string(token)]) }

        static func fromJSON(_ json: JSON) throws -> Offer {
            let r = try FieldReader(json)
            return Offer(v: try r.int("v"), pkA: try r.string("pkA"), eA: try r.string("eA"), token: try r.string("token"))
        }
    }

    /// B → A（中継の /pair 経由）。eB は平文（A が鍵を導くのに要る）、残りは封じる。
    public struct Hello: Equatable, Sendable {
        public var v: Int
        public var eB: String
        public var nonce: String
        public var ciphertext: String

        public init(v: Int, eB: String, nonce: String, ciphertext: String) {
            self.v = v
            self.eB = eB
            self.nonce = nonce
            self.ciphertext = ciphertext
        }

        func toJSON() -> JSON { .obj(["v": JSON(v), "eB": .string(eB), "nonce": .string(nonce), "ciphertext": .string(ciphertext)]) }

        static func fromJSON(_ json: JSON) throws -> Hello {
            let r = try FieldReader(json)
            return Hello(v: try r.int("v"), eB: try r.string("eB"), nonce: try r.string("nonce"), ciphertext: try r.string("ciphertext"))
        }
    }

    /// A → B。
    public struct Confirm: Equatable, Sendable {
        public var nonce: String
        public var ciphertext: String

        public init(nonce: String, ciphertext: String) {
            self.nonce = nonce
            self.ciphertext = ciphertext
        }

        func toJSON() -> JSON { .obj(["nonce": .string(nonce), "ciphertext": .string(ciphertext)]) }

        static func fromJSON(_ json: JSON) throws -> Confirm {
            let r = try FieldReader(json)
            return Confirm(nonce: try r.string("nonce"), ciphertext: try r.string("ciphertext"))
        }
    }

    private struct HelloBody {
        var pkB: String
        var sigB: String
        var deviceName: String
        var accountHmac: String

        /// Connect v2: B の家の鍵の記述（MemberKeys の JSON）。sigB の対象に入る。
        var `extension`: String?

        func toJSON() -> JSON {
            var o = JSONObject([("pkB", .string(pkB)), ("sigB", .string(sigB)), ("deviceName", .string(deviceName)), ("accountHmac", .string(accountHmac))])
            if let ext = `extension` { o["extension"] = .string(ext) }
            return .object(o)
        }

        static func fromJSON(_ json: JSON) throws -> HelloBody {
            let r = try FieldReader(json)
            return HelloBody(
                pkB: try r.string("pkB"), sigB: try r.string("sigB"), deviceName: try r.string("deviceName"), accountHmac: try r.string("accountHmac"),
                extension: try r.optString("extension"))
        }
    }

    private struct ConfirmBody {
        var sigA: String
        var deviceName: String
        var accountHmac: String

        /// Connect v2: 家の ID・一覧の頭・storeId・版の床（秘密を入れない）。sigA の対象に入る。
        var `extension`: String?

        func toJSON() -> JSON {
            var o = JSONObject([("sigA", .string(sigA)), ("deviceName", .string(deviceName)), ("accountHmac", .string(accountHmac))])
            if let ext = `extension` { o["extension"] = .string(ext) }
            return .object(o)
        }

        static func fromJSON(_ json: JSON) throws -> ConfirmBody {
            let r = try FieldReader(json)
            return ConfirmBody(
                sigA: try r.string("sigA"), deviceName: try r.string("deviceName"), accountHmac: try r.string("accountHmac"),
                extension: try r.optString("extension"))
        }
    }

    /// 確定したペア。
    public struct Established: Equatable, Sendable {
        public let peerPublicKey: Data
        public let peerDeviceName: String
        public let sendKey: Data
        public let receiveKey: Data
        public let accountKey: Data

        /// 相手の拡張（A から見た B の Hello、B から見た A の Confirm）。署名で確かめた値。
        public let peerExtension: String?
    }

    /// B 側の結果: 表示する6桁と、確定したときに保存するペア。
    public struct Confirmed: Equatable, Sendable {
        public let code: String
        public let pair: Established
    }

    public enum Failure: Error, Equatable, Sendable {
        case malformed
        case badSignature
        case expired
        case wrongCode
        case tooManyAttempts

        /// 別のアカウントでログインした端末。
        case accountMismatch

        case alreadyUsed
    }

    public static func encodeOffer(_ offer: Offer) -> String { offerPrefix + B64.encode(Data(ConnectJSON.serialize(offer.toJSON()).utf8)) }

    public static func decodeOffer(_ text: String) -> Offer? {
        if !text.hasPrefix(offerPrefix) || text.utf16.count > 1024 { return nil }
        guard let bytes = B64.decode(String(text.dropFirst(offerPrefix.count))) else { return nil }
        guard let offer = parse(bytes, Offer.fromJSON), offer.v == version else { return nil }
        guard let pkA = B64.decode(offer.pkA), WaveCrypto.decodePublicKey(pkA) != nil else { return nil }
        guard let eA = B64.decode(offer.eA), WaveCrypto.decodePublicKey(eA) != nil else { return nil }
        if B64.decode(offer.token)?.count != tokenBytes { return nil }
        return offer
    }

    public static func decodeHello(_ json: JSON) -> Hello? { try? Hello.fromJSON(json) }

    public static func decodeConfirm(_ json: JSON) -> Confirm? { try? Confirm.fromJSON(json) }

    public static func toJSON(_ hello: Hello) -> JSON { hello.toJSON() }

    public static func toJSON(_ confirm: Confirm) -> JSON { confirm.toJSON() }

    /// A 側。QR を出してから、1回の B の Hello と、6桁の入力を扱う。スレッドを跨いで呼んでよい。
    public final class Responder: @unchecked Sendable {
        private let identity: DeviceSigner
        private let deviceName: String
        private let accountId: String
        private let nowMs: () -> Int64
        private let confirmAccountId: String
        private let confirmExtension: String?
        private let pkA: Data
        private let ephemeral: P256.KeyAgreement.PrivateKey
        private let eA: Data
        private let createdAtMs: Int64
        private let gate = NSLock()
        private var used = false
        private var attempts = 0
        private var pending: (sas: String, established: Established)?

        public let token: Data
        public let offer: Offer

        /// 中継に預ける値（token そのものは渡さない）。
        public let tokenHash: Data

        /// - Parameters:
        ///   - identity: 身元の署名鍵
        ///   - accountId: アカウントの識別子（正規化した DATASYNC_ID）。HMAC にだけ使う
        ///   - confirmExtension: Connect v2: Confirm に入れる拡張（秘密を入れない）
        public convenience init(identity: DeviceSigner, deviceName: String, accountId: String, nowMs: @escaping () -> Int64, confirmExtension: String? = nil) {
            self.init(
                identity: identity, deviceName: deviceName, accountId: accountId, nowMs: nowMs, confirmAccountId: accountId,
                confirmExtension: confirmExtension, ephemeral: WaveCrypto.generateEphemeralKeyPair(), token: WaveCrypto.randomBytes(tokenBytes))
        }

        /// テスト用: 使い捨ての鍵と token を外から与える・Confirm に入れるアカウントを変える。
        init(
            identity: DeviceSigner, deviceName: String, accountId: String, nowMs: @escaping () -> Int64, confirmAccountId: String,
            confirmExtension: String?, ephemeral: P256.KeyAgreement.PrivateKey, token: Data
        ) {
            self.identity = identity
            self.deviceName = deviceName
            self.accountId = accountId
            self.nowMs = nowMs
            self.confirmAccountId = confirmAccountId
            self.confirmExtension = confirmExtension
            self.ephemeral = ephemeral
            pkA = identity.identityPub
            eA = WaveCrypto.encodePublicKey(ephemeral)
            self.token = token
            createdAtMs = nowMs()
            offer = Offer(v: version, pkA: B64.encode(pkA), eA: B64.encode(eA), token: B64.encode(token))
            tokenHash = WaveCrypto.sha256(token)
        }

        /// B の Hello を受けて Confirm を返す。token は最初の正しい Hello で消費する。署名に失敗したときだけ投げる。
        public func onHello(_ hello: Hello) throws -> Result<Confirm, Failure> {
            gate.lock()
            defer { gate.unlock() }
            if expired() { return .failure(.expired) }
            if used { return .failure(.alreadyUsed) }
            if hello.v != version { return .failure(.malformed) }
            guard let eBBytes = B64.decode(hello.eB), let eB = WaveCrypto.decodePublicKey(eBBytes) else { return .failure(.malformed) }
            guard let shared = try? WaveCrypto.ecdh(ephemeral, eB) else { return .failure(.malformed) }
            let transcript0 = WaveCrypto.lengthPrefixed(WaveCrypto.utf8(label), pkA, eA, token, eBBytes)
            let prk = Hkdf.extract(salt: token, ikm: shared)
            guard let nonce = B64.decode(hello.nonce), let ciphertext = B64.decode(hello.ciphertext) else { return .failure(.malformed) }
            let plain = WaveCrypto.open(key: handshakeKey(prk, "b->a", transcript0), nonce: nonce, aad: transcript0, ciphertext: ciphertext)
            guard let body = plain.flatMap({ parse($0, HelloBody.fromJSON) }) else { return .failure(.malformed) }
            guard let pkBBytes = B64.decode(body.pkB), let pkB = WaveCrypto.decodePublicKey(pkBBytes) else { return .failure(.malformed) }
            // 自分自身とのペアは受けない（方向の鍵が崩れる）
            if pkBBytes == pkA { return .failure(.malformed) }
            let transcript = WaveCrypto.lengthPrefixed(transcript0, pkBBytes)
            if let ext = body.extension, ext.utf16.count > maxExtensionChars { return .failure(.malformed) }
            guard let sigB = B64.decode(body.sigB) else { return .failure(.malformed) }
            if !WaveCrypto.verify(pkB, label: label + "-b", message: signedTranscript(transcript, body.extension), signature: sigB) {
                return .failure(.badSignature)
            }
            let keys = schedule(prk, transcript, self: pkA, peer: pkBBytes)
            if !WaveCrypto.constantTimeEquals(accountHmac(keys.accountKey, accountId), B64.decode(body.accountHmac) ?? Data()) {
                return .failure(.accountMismatch)
            }
            used = true
            pending = (
                keys.sas,
                Established(
                    peerPublicKey: pkBBytes, peerDeviceName: sanitizeName(body.deviceName), sendKey: keys.sendKey, receiveKey: keys.receiveKey,
                    accountKey: keys.accountKey, peerExtension: body.extension)
            )
            let confirm = ConfirmBody(
                sigA: B64.encode(try identity.sign(label: label + "-a", message: signedTranscript(transcript, confirmExtension))),
                deviceName: deviceName,
                accountHmac: B64.encode(accountHmac(keys.accountKey, confirmAccountId)),
                extension: confirmExtension)
            let (sealNonce, sealed) = try WaveCrypto.seal(
                key: handshakeKey(prk, "a->b", transcript0), aad: transcript0, plaintext: Data(ConnectJSON.serialize(confirm.toJSON()).utf8))
            return .success(Confirm(nonce: B64.encode(sealNonce), ciphertext: B64.encode(sealed)))
        }

        /// 本人が入力した6桁（B の画面の数字）。一致したらペアを確定する。3回まで。
        public func onCodeEntered(_ code: String) -> Result<Established, Failure> {
            gate.lock()
            defer { gate.unlock() }
            if expired() { return .failure(.expired) }
            if attempts >= maxAttempts { return .failure(.tooManyAttempts) }
            guard let current = pending else { return .failure(.malformed) }
            attempts += 1
            if !WaveCrypto.constantTimeEquals(WaveCrypto.utf8(code.trimmingCharacters(in: .whitespacesAndNewlines)), WaveCrypto.utf8(current.sas)) {
                if attempts >= maxAttempts { pending = nil }
                return .failure(attempts >= maxAttempts ? .tooManyAttempts : .wrongCode)
            }
            pending = nil
            return .success(current.established)
        }

        private func expired() -> Bool { nowMs() - createdAtMs > lifetimeMs }
    }

    /// B 側。QR を読んで Hello を作り、A の Confirm で6桁とペアを得る。
    public final class Initiator: @unchecked Sendable {
        private let identity: DeviceSigner
        private let deviceName: String
        private let accountId: String
        private let helloExtension: String?
        private let pkB: Data
        private let eB: Data
        private let pkA: Data
        private let transcript0: Data
        private let transcript: Data
        private let prk: Data
        private let keys: Keys

        /// - Parameters:
        ///   - offer: decodeOffer を通したもの（通していなければ投げる）
        ///   - helloExtension: Connect v2: Hello に入れる拡張（B の家の鍵の記述）
        public convenience init(offer: Offer, identity: DeviceSigner, deviceName: String, accountId: String, helloExtension: String? = nil) throws {
            try self.init(
                offer: offer, identity: identity, deviceName: deviceName, accountId: accountId, helloExtension: helloExtension,
                ephemeral: WaveCrypto.generateEphemeralKeyPair())
        }

        /// テスト用: 使い捨ての鍵を外から与える。
        init(
            offer: Offer, identity: DeviceSigner, deviceName: String, accountId: String, helloExtension: String?, ephemeral: P256.KeyAgreement.PrivateKey
        ) throws {
            self.identity = identity
            self.deviceName = deviceName
            self.accountId = accountId
            self.helloExtension = helloExtension
            pkB = identity.identityPub
            eB = WaveCrypto.encodePublicKey(ephemeral)
            guard let a = B64.decode(offer.pkA), WaveCrypto.decodePublicKey(a) != nil else {
                throw ConnectError.invalidArgument("offer は decodeOffer を通すこと")
            }
            pkA = a
            guard let eABytes = B64.decode(offer.eA), let eAKey = WaveCrypto.decodePublicKey(eABytes) else {
                throw ConnectError.invalidArgument("offer は decodeOffer を通すこと")
            }
            guard let token = B64.decode(offer.token), token.count == tokenBytes else { throw ConnectError.invalidArgument("offer は decodeOffer を通すこと") }
            transcript0 = WaveCrypto.lengthPrefixed(WaveCrypto.utf8(label), pkA, eABytes, token, eB)
            transcript = WaveCrypto.lengthPrefixed(transcript0, pkB)
            prk = Hkdf.extract(salt: token, ikm: try WaveCrypto.ecdh(ephemeral, eAKey))
            keys = schedule(prk, transcript, self: pkB, peer: pkA)
        }

        public func hello() throws -> Hello {
            let body = HelloBody(
                pkB: B64.encode(pkB),
                sigB: B64.encode(try identity.sign(label: label + "-b", message: signedTranscript(transcript, helloExtension))),
                deviceName: deviceName,
                accountHmac: B64.encode(accountHmac(keys.accountKey, accountId)),
                extension: helloExtension)
            let (nonce, ciphertext) = try WaveCrypto.seal(
                key: handshakeKey(prk, "b->a", transcript0), aad: transcript0, plaintext: Data(ConnectJSON.serialize(body.toJSON()).utf8))
            return Hello(v: version, eB: B64.encode(eB), nonce: B64.encode(nonce), ciphertext: B64.encode(ciphertext))
        }

        /// A の Confirm を確かめ、（表示する6桁, 確定したときに保存するペア）を返す。
        public func onConfirm(_ confirm: Confirm) -> Result<Confirmed, Failure> {
            guard let nonce = B64.decode(confirm.nonce), let ciphertext = B64.decode(confirm.ciphertext) else { return .failure(.malformed) }
            let plain = WaveCrypto.open(key: handshakeKey(prk, "a->b", transcript0), nonce: nonce, aad: transcript0, ciphertext: ciphertext)
            guard let body = plain.flatMap({ parse($0, ConfirmBody.fromJSON) }) else { return .failure(.malformed) }
            guard let pkAKey = WaveCrypto.decodePublicKey(pkA) else { return .failure(.malformed) }
            if let ext = body.extension, ext.utf16.count > maxExtensionChars { return .failure(.malformed) }
            guard let sigA = B64.decode(body.sigA) else { return .failure(.malformed) }
            if !WaveCrypto.verify(pkAKey, label: label + "-a", message: signedTranscript(transcript, body.extension), signature: sigA) {
                return .failure(.badSignature)
            }
            if !WaveCrypto.constantTimeEquals(accountHmac(keys.accountKey, accountId), B64.decode(body.accountHmac) ?? Data()) {
                return .failure(.accountMismatch)
            }
            return .success(
                Confirmed(
                    code: keys.sas,
                    pair: Established(
                        peerPublicKey: pkA, peerDeviceName: sanitizeName(body.deviceName), sendKey: keys.sendKey, receiveKey: keys.receiveKey,
                        accountKey: keys.accountKey, peerExtension: body.extension)))
        }
    }

    private struct Keys {
        let sas: String
        let sendKey: Data
        let receiveKey: Data
        let accountKey: Data
    }

    /// 拡張があれば、署名の対象に拡張のハッシュを足す（無ければ v1 と同じ）。
    private static func signedTranscript(_ transcript: Data, _ ext: String?) -> Data {
        guard let ext else { return transcript }
        return WaveCrypto.lengthPrefixed(transcript, WaveCrypto.sha256(WaveCrypto.utf8(ext)))
    }

    private static func handshakeKey(_ prk: Data, _ direction: String, _ transcript0: Data) -> Data {
        Hkdf.expand(prk: prk, info: WaveCrypto.utf8("\(label) handshake \(direction)") + WaveCrypto.sha256(transcript0), length: 32)
    }

    /// 確定したペアの鍵。方向は端末 ID（公開鍵の SHA-256）の大小（符号なし）で決める。
    private static func schedule(_ prk: Data, _ transcript: Data, self selfKey: Data, peer: Data) -> Keys {
        let hash = WaveCrypto.sha256(transcript)
        let sasBytes = [UInt8](Hkdf.expand(prk: prk, info: WaveCrypto.utf8("\(label) sas") + hash, length: 8))
        // Long.remainderUnsigned と同じ（8バイトを符号なしとして読む）
        let sasNumber = sasBytes.reduce(UInt64(0)) { $0 << 8 | UInt64($1) } % 1_000_000
        let lowToHigh = Hkdf.expand(prk: prk, info: WaveCrypto.utf8("\(label) key low->high") + hash, length: 32)
        let highToLow = Hkdf.expand(prk: prk, info: WaveCrypto.utf8("\(label) key high->low") + hash, length: 32)
        let accountKey = Hkdf.expand(prk: prk, info: WaveCrypto.utf8("\(label) account") + hash, length: 32)
        let selfIsLow = WaveCrypto.fingerprint(selfKey).lexicographicallyPrecedes(WaveCrypto.fingerprint(peer))
        return Keys(
            sas: String(format: "%06llu", sasNumber),
            sendKey: selfIsLow ? lowToHigh : highToLow,
            receiveKey: selfIsLow ? highToLow : lowToHigh,
            accountKey: accountKey)
    }

    /// 同じアカウントかを確かめる HMAC（誤操作の防止で、防御ではない）。
    public static func accountHmac(_ accountKey: Data, _ accountId: String) -> Data {
        WaveCrypto.hmacSha256(accountKey, Data("wave-account-v1".utf8), WaveCrypto.utf8(accountId))
    }

    /// 端末名から制御文字・書字方向の上書き文字を落とし、64文字（UTF-16 の符号単位）までにする。
    public static func sanitizeName(_ name: String) -> String {
        var units: [UInt16] = []
        for c in name.utf16 {
            if c <= 0x1f || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0x200e || c == 0x200f {
                continue
            }
            units.append(c)
        }
        var trimmed = String(decoding: units, as: UTF16.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.utf16.count > 64 { trimmed = String(decoding: Array(trimmed.utf16.prefix(64)), as: UTF16.self) }
        return trimmed.isEmpty ? "端末" : trimmed
    }

    private static func parse<T>(_ utf8: Data, _ read: (JSON) throws -> T) -> T? {
        guard let json = try? JSON.parse(utf8) else { return nil }
        return try? read(json)
    }
}

public extension Result where Failure == Pairing.Failure {
    /// 失敗なら、その理由（成功なら nil）。
    var failure: Pairing.Failure? {
        if case .failure(let f) = self { return f }
        return nil
    }

    var isSuccess: Bool { failure == nil }
}
