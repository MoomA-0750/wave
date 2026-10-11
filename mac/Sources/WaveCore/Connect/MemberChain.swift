import Foundation

/// 端末の身元鍵での署名（Android では Keystore の鍵。Mac ではキーチェーン・Secure Enclave の鍵や、保護して保存したソフトウェアの鍵など）。
public protocol DeviceSigner: AnyObject {
    var deviceId: String { get }

    /// 身元の署名公開鍵（SPKI）。
    var identityPub: Data { get }

    /// 用途ラベルを前置した署名（`WaveCrypto.sign` と同じ形、DER）。
    func sign(label: String, message: Data) throws -> Data
}

public struct ChainMember: Equatable, Sendable {
    public let keys: MemberKeys
    public let identity: EcPublicKey
    public let kx: EcPublicKey
    public let addedSeq: Int64
}

/// genesis から検証した端末一覧。
public final class VerifiedChain: @unchecked Sendable {
    public let householdId: String
    public let headSeq: Int64
    public let headHash: String

    /// 端末 ID から端末。
    public let members: [String: ChainMember]

    /// 加えた順の端末 ID（Kotlin の LinkedHashMap と同じ順）。
    public let memberIds: [String]

    public let removed: Set<String>
    private let hashes: [String]

    init(householdId: String, headSeq: Int64, headHash: String, members: [String: ChainMember], memberIds: [String], removed: Set<String>, hashes: [String]) {
        self.householdId = householdId
        self.headSeq = headSeq
        self.headHash = headHash
        self.members = members
        self.memberIds = memberIds
        self.removed = removed
        self.hashes = hashes
    }

    /// 加えた順の端末。
    public var orderedMembers: [ChainMember] { memberIds.compactMap { members[$0] } }

    public func hashAt(_ seq: Int64) -> String? { seq >= 0 && seq < Int64(hashes.count) ? hashes[Int(seq)] : nil }
}

public enum ChainVerification {
    case ok(VerifiedChain)
    case invalid(seq: Int64, reason: String)

    /// 自分が検証した頭より短い（サーバーが巻き戻した）。
    case rollback

    /// 自分が検証した頭と同じ seq で、ハッシュが違う（サーバーが分岐を見せた）。
    case fork
}

/// 端末一覧の鎖（D-2）。記録の作成と、genesis からの検証（Android 版の MemberChain と同じ）。
public enum MemberChain {
    public static let chainLabel = "wave-v2-chain"
    public static let kxLabel = "wave-v2-kx"
    private static let maxRecords = 1024

    public struct Head: Equatable, Sendable {
        public let seq: Int64
        public let hash: String

        public init(seq: Int64, hash: String) {
            self.seq = seq
            self.hash = hash
        }
    }

    /// この端末が家に加わるときの鍵の記述（kx を身元鍵で自己署名する）。
    public static func memberKeys(signer: DeviceSigner, kxPub: EcPublicKey, role: Role) throws -> MemberKeys {
        let kx = Hpke.serializePublicKey(kxPub)
        let signature = try signer.sign(label: kxLabel, message: kxMessage(signer.deviceId, kx, role))
        return MemberKeys(
            deviceId: signer.deviceId, identityPub: B64.encode(signer.identityPub), kxPub: B64.encode(kx), role: role, kxSignature: B64.encode(signature))
    }

    public static func genesis(signer: DeviceSigner, keys: MemberKeys) throws -> ChainRecord {
        if keys.deviceId != signer.deviceId || keys.role != .player { throw ConnectError.invalidArgument("genesis は自分の player の鍵で作る") }
        return try signed(signer, ChainRecord(seq: 0, type: .genesis, target: keys, signerId: signer.deviceId, signature: ""))
    }

    public static func add(signer: DeviceSigner, chain: VerifiedChain, target: MemberKeys) throws -> ChainRecord {
        try signed(
            signer,
            ChainRecord(
                householdId: chain.householdId, seq: chain.headSeq + 1, prevHash: chain.headHash, type: .add, target: target, signerId: signer.deviceId,
                signature: ""))
    }

    public static func remove(signer: DeviceSigner, chain: VerifiedChain, deviceId: String) throws -> ChainRecord {
        try signed(
            signer,
            ChainRecord(
                householdId: chain.householdId, seq: chain.headSeq + 1, prevHash: chain.headHash, type: .remove, removedDeviceId: deviceId,
                signerId: signer.deviceId, signature: ""))
    }

    /// 記録のハッシュ（householdId・prevHash・頭）。署名は含めない。
    public static func hash(_ record: ChainRecord) -> String { B64.encode(WaveCrypto.sha256(canonical(record))) }

    /// genesis から検証する。trusted（この端末が前に検証した頭）があれば、それより短い鎖と、その seq でハッシュが違う鎖を拒む。
    public static func verify(_ records: [ChainRecord], trusted: Head? = nil) -> ChainVerification {
        if records.isEmpty || records.count > maxRecords { return .invalid(seq: 0, reason: "size") }
        var members: [String: ChainMember] = [:]
        var memberIds: [String] = []
        var removed = Set<String>()
        var hashes: [String] = []
        var householdId = ""
        for (index, record) in records.enumerated() {
            let seq = Int64(index)
            func invalid(_ reason: String) -> ChainVerification { .invalid(seq: seq, reason: reason) }
            if record.v != ChainRecord.formatVersion || record.seq != seq { return invalid("seq") }
            if index == 0 {
                if record.type != .genesis || record.householdId != nil || record.prevHash != nil { return invalid("genesis") }
                guard let target = record.target else { return invalid("genesis_target") }
                if target.role != .player || record.signerId != target.deviceId || record.removedDeviceId != nil { return invalid("genesis_signer") }
                guard let member = memberOf(target, seq) else { return invalid("genesis_keys") }
                if !verifySignature(record, member.identity) { return invalid("signature") }
                members[target.deviceId] = member
                memberIds.append(target.deviceId)
                householdId = hash(record)
            } else {
                if record.householdId != householdId || record.prevHash != hashes.last { return invalid("link") }
                guard let signer = members[record.signerId] else { return invalid("signer_not_member") }
                if !verifySignature(record, signer.identity) { return invalid("signature") }
                switch record.type {
                case .genesis:
                    return invalid("second_genesis")
                case .add:
                    guard let target = record.target else { return invalid("add_target") }
                    if record.removedDeviceId != nil { return invalid("add_shape") }
                    // player を加えられるのは player だけ（L-11）。controller も当面は player だけが加える
                    if signer.keys.role != .player { return invalid("add_by_controller") }
                    if members[target.deviceId] != nil || removed.contains(target.deviceId) { return invalid("add_existing") }
                    guard let member = memberOf(target, seq) else { return invalid("add_keys") }
                    members[target.deviceId] = member
                    memberIds.append(target.deviceId)
                case .remove:
                    guard let targetId = record.removedDeviceId else { return invalid("remove_target") }
                    if record.target != nil { return invalid("remove_shape") }
                    guard let target = members[targetId] else { return invalid("remove_not_member") }
                    // controller は自分だけを外せる
                    if signer.keys.role != .player && targetId != signer.keys.deviceId { return invalid("remove_by_controller") }
                    // player がいなくなる remove は拒む
                    if target.keys.role == .player && members.values.filter({ $0.keys.role == .player }).count <= 1 { return invalid("last_player") }
                    members[targetId] = nil
                    memberIds.removeAll { $0 == targetId }
                    removed.insert(targetId)
                }
            }
            hashes.append(hash(record))
        }
        if let trusted {
            if Int64(records.count) <= trusted.seq { return .rollback }
            if trusted.seq < 0 || hashes[Int(trusted.seq)] != trusted.hash { return .fork }
        }
        return .ok(
            VerifiedChain(
                householdId: householdId, headSeq: Int64(records.count) - 1, headHash: hashes[hashes.count - 1], members: members, memberIds: memberIds,
                removed: removed, hashes: hashes))
    }

    private static func memberOf(_ keys: MemberKeys, _ seq: Int64) -> ChainMember? {
        guard let identityBytes = B64.decode(keys.identityPub) else { return nil }
        guard let identity = WaveCrypto.decodePublicKey(identityBytes) else { return nil }
        if WaveCrypto.deviceId(identityBytes) != keys.deviceId { return nil }
        guard let kxBytes = B64.decode(keys.kxPub) else { return nil }
        guard let kx = Hpke.deserializePublicKey(kxBytes) else { return nil }
        guard let kxSignature = B64.decode(keys.kxSignature) else { return nil }
        if !WaveCrypto.verify(identity, label: kxLabel, message: kxMessage(keys.deviceId, kxBytes, keys.role), signature: kxSignature) { return nil }
        return ChainMember(keys: keys, identity: identity, kx: kx, addedSeq: seq)
    }

    private static func signed(_ signer: DeviceSigner, _ record: ChainRecord) throws -> ChainRecord {
        var copy = record
        copy.signature = B64.encode(try signer.sign(label: chainLabel, message: canonical(record)))
        return copy
    }

    private static func verifySignature(_ record: ChainRecord, _ identity: EcPublicKey) -> Bool {
        guard let signature = B64.decode(record.signature) else { return false }
        return WaveCrypto.verify(identity, label: chainLabel, message: canonical(record), signature: signature)
    }

    private static func kxMessage(_ deviceId: String, _ kx: Data, _ role: Role) -> Data {
        WaveCrypto.lengthPrefixed(WaveCrypto.utf8(deviceId), kx, WaveCrypto.utf8(role.kotlinName))
    }

    /// 署名とハッシュの対象（署名そのものを除く全部を長さ付きで連結）。enum は Kotlin の enum の名前（"Genesis"・"Player"）。
    static func canonical(_ record: ChainRecord) -> Data {
        let target = record.target
        let parts = [
            String(record.v),
            record.householdId ?? "",
            String(record.seq),
            record.prevHash ?? "",
            record.type.kotlinName,
            target?.deviceId ?? "",
            target?.identityPub ?? "",
            target?.kxPub ?? "",
            target?.role.kotlinName ?? "",
            target?.kxSignature ?? "",
            record.removedDeviceId ?? "",
            record.signerId,
        ]
        return WaveCrypto.lengthPrefixed(parts.map(WaveCrypto.utf8))
    }
}
