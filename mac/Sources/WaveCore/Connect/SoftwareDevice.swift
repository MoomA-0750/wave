import CryptoKit
import Foundation

/// 身元鍵（ECDSA）と受け取りの鍵（ECDH）をソフトウェアで持つ端末。鍵は PKCS#8 で書き出せる（保存するときは呼ぶ側が保護する。例: キーチェーン）。
public final class SoftwareDevice: DeviceSigner, KxOpener, @unchecked Sendable {
    private let identity: P256.Signing.PrivateKey
    private let kx: P256.KeyAgreement.PrivateKey

    public let deviceId: String
    public let identityPub: Data
    public let kxPub: EcPublicKey

    public init(identity: P256.Signing.PrivateKey, kx: P256.KeyAgreement.PrivateKey) {
        self.identity = identity
        self.kx = kx
        identityPub = WaveCrypto.encodePublicKey(identity)
        deviceId = WaveCrypto.deviceId(identityPub)
        kxPub = WaveCrypto.publicKeyOf(kx)
    }

    /// 新しい鍵で作る。
    public static func generate() -> SoftwareDevice { SoftwareDevice(identity: WaveCrypto.generateSigningKey(), kx: WaveCrypto.generateEphemeralKeyPair()) }

    /// PKCS#8 の秘密鍵から作る。P-256 でない・読めないときは投げる。
    public static func fromPkcs8(identity identityPkcs8: Data, kx kxPkcs8: Data) throws -> SoftwareDevice {
        SoftwareDevice(identity: try signingKey(pkcs8: identityPkcs8), kx: try agreementKey(pkcs8: kxPkcs8))
    }

    public func exportIdentityPkcs8() -> Data { identity.derRepresentation }

    public func exportKxPkcs8() -> Data { kx.derRepresentation }

    public func sign(label: String, message: Data) throws -> Data { try WaveCrypto.sign(identity, label: label, message: message) }

    public func dh(_ peer: EcPublicKey) throws -> Data { try WaveCrypto.ecdh(kx, peer) }

    /// 家に加わるときの鍵の記述。
    public func memberKeys(role: Role) throws -> MemberKeys { try MemberChain.memberKeys(signer: self, kxPub: kxPub, role: role) }

    /// PKCS#8（公開鍵の入っていない形も）の署名鍵。
    static func signingKey(pkcs8: Data) throws -> P256.Signing.PrivateKey {
        if let key = try? P256.Signing.PrivateKey(derRepresentation: pkcs8) { return key }
        guard let scalar = WaveCrypto.privateScalar(fromDer: pkcs8) else { throw ConnectError.invalidArgument("PKCS#8 の P-256 の鍵ではありません") }
        return try P256.Signing.PrivateKey(rawRepresentation: scalar)
    }

    /// PKCS#8（公開鍵の入っていない形も）の ECDH の鍵。
    static func agreementKey(pkcs8: Data) throws -> P256.KeyAgreement.PrivateKey {
        if let key = try? P256.KeyAgreement.PrivateKey(derRepresentation: pkcs8) { return key }
        guard let scalar = WaveCrypto.privateScalar(fromDer: pkcs8) else { throw ConnectError.invalidArgument("PKCS#8 の P-256 の鍵ではありません") }
        return try P256.KeyAgreement.PrivateKey(rawRepresentation: scalar)
    }
}
