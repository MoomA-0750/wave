import Foundation

/// この端末の受け取りの鍵（kx）で ECDH する。
public protocol KxOpener: AnyObject {
    var deviceId: String { get }
    var kxPub: EcPublicKey { get }

    /// ECDH の共有秘密（x 座標、32バイト）。
    func dh(_ peer: EcPublicKey) throws -> Data
}

public enum DocOpen: Equatable {
    /// staleRecipients: 自分の一覧で外された端末が宛先にいる（古い一覧で書かれた）。開いてよいが、書き手に書き直しを促す。
    case ok(header: DocHeader, plaintext: Data, staleRecipients: Bool)

    /// 書き手の一覧の頭が自分より新しい。鎖を取り直してから開く。
    case needChain(headSeq: Int64)

    /// 書き手の一覧の頭が、同じ seq で自分の鎖と違う（サーバーが分岐させた）。取り込まず、止まる根拠にもしない。
    case fork

    case rejected(String)
}

/// 文字列を C# の StringComparer.Ordinal と同じく、UTF-16 の符号単位で比べる。
func ordinalLess(_ a: String, _ b: String) -> Bool { a.utf16.lexicographicallyPrecedes(b.utf16) }

/// SealedDoc（D-1）: 本文を書き込みごとの内容鍵（AES-256-GCM）で暗号化し、内容鍵を家の端末ごとに HPKE で包み、全体に書き手が署名する（Android 版と同じ）。
public enum DocSealer {
    public static let docLabel = "wave-v2-doc"
    private static let wrapInfoLabel = "wave-v2-wrap"
    private static let padBlock = 4096
    public static let maxPlaintext = 1024 * 1024

    /// 宛先は、書き手が検証した一覧の今の全端末。
    public static func seal(
        signer: DeviceSigner, chain: VerifiedChain, kind: DocKind, version: Int64, prevHash: String?, issuedAtEpochMs: Int64, plaintext: Data
    ) throws -> SealedDoc {
        if plaintext.count > maxPlaintext { throw ConnectError.invalidArgument("plaintext too large") }
        if chain.members[signer.deviceId] == nil { throw ConnectError.invalidArgument("writer is not a member") }
        let header = DocHeader(
            kind: kind, householdId: chain.householdId, writerId: signer.deviceId, version: version, prevHash: prevHash, headSeq: chain.headSeq,
            headHash: chain.headHash, issuedAtEpochMs: issuedAtEpochMs)
        let headerHash = WaveCrypto.sha256(canonicalHeader(header))
        let contentKey = WaveCrypto.randomBytes(32)
        let (nonce, ciphertext) = try WaveCrypto.seal(key: contentKey, aad: headerHash, plaintext: pad(plaintext))
        let recipients = try chain.members.values
            .sorted { ordinalLess($0.keys.deviceId, $1.keys.deviceId) }
            .map { member in
                let sealedKey = try Hpke.seal(
                    recipient: member.kx, info: wrapInfo(kind, chain.householdId), aad: wrapAad(headerHash, member.keys.deviceId), plaintext: contentKey)
                return DocRecipient(deviceId: member.keys.deviceId, enc: B64.encode(sealedKey.enc), wrappedKey: B64.encode(sealedKey.ciphertext))
            }
        var doc = SealedDoc(header: header, nonce: B64.encode(nonce), ciphertext: B64.encode(ciphertext), recipients: recipients, signature: "")
        doc.signature = B64.encode(try signer.sign(label: docLabel, message: signedBytes(doc)))
        return doc
    }

    /// 書き手・役割・署名・一覧の頭を確かめてから開く。
    public static func open(_ doc: SealedDoc, chain: VerifiedChain, me: KxOpener) -> DocOpen {
        let header = doc.header
        if header.v != DocHeader.formatVersion { return .rejected("version") }
        if header.householdId != chain.householdId { return .rejected("household") }
        if header.headSeq > chain.headSeq { return .needChain(headSeq: header.headSeq) }
        if chain.hashAt(header.headSeq) != header.headHash { return .fork }
        guard let writer = chain.members[header.writerId] else { return .rejected("writer_not_member") }
        if !roleAllowed(header.kind, writer.keys.role) { return .rejected("role") }
        if Set(doc.recipients.map { Data($0.deviceId.utf8) }).count != doc.recipients.count { return .rejected("duplicate_recipient") }
        guard let signature = B64.decode(doc.signature) else { return .rejected("malformed") }
        if !WaveCrypto.verify(writer.identity, label: docLabel, message: signedBytes(doc), signature: signature) { return .rejected("signature") }
        guard let mine = doc.recipients.first(where: { $0.deviceId == me.deviceId }) else { return .rejected("not_for_me") }
        let headerHash = WaveCrypto.sha256(canonicalHeader(header))
        guard let enc = B64.decode(mine.enc) else { return .rejected("malformed") }
        guard let wrapped = B64.decode(mine.wrappedKey) else { return .rejected("malformed") }
        guard
            let contentKey = Hpke.open(
                enc: enc, recipientPublic: me.kxPub, dh: { try me.dh($0) }, info: wrapInfo(header.kind, header.householdId),
                aad: wrapAad(headerHash, me.deviceId), ciphertext: wrapped)
        else { return .rejected("unwrap") }
        guard let nonce = B64.decode(doc.nonce) else { return .rejected("malformed") }
        guard let ciphertext = B64.decode(doc.ciphertext) else { return .rejected("malformed") }
        guard let padded = WaveCrypto.open(key: contentKey, nonce: nonce, aad: headerHash, ciphertext: ciphertext) else { return .rejected("content") }
        guard let plaintext = unpad(padded) else { return .rejected("padding") }
        let stale = doc.recipients.contains { chain.removed.contains($0.deviceId) }
        return .ok(header: header, plaintext: plaintext, staleRecipients: stale)
    }

    /// doc のハッシュ（prevHash・食い違いの検出・キューの参照）。署名は含めない。
    public static func hash(_ doc: SealedDoc) -> String { B64.encode(WaveCrypto.sha256(signedBytes(doc))) }

    public static func roleAllowed(_ kind: DocKind, _ role: Role) -> Bool {
        switch kind {
        case .cursor, .queue: return role == .player
        default: return true
        }
    }

    private static func wrapInfo(_ kind: DocKind, _ householdId: String) -> Data {
        WaveCrypto.lengthPrefixed(WaveCrypto.utf8(wrapInfoLabel), WaveCrypto.utf8(kind.kotlinName), WaveCrypto.utf8(householdId))
    }

    private static func wrapAad(_ headerHash: Data, _ recipientId: String) -> Data { WaveCrypto.lengthPrefixed(headerHash, WaveCrypto.utf8(recipientId)) }

    /// 見出しの canonical バイト列。kind は Kotlin の enum の名前（"Cursor" など）。
    static func canonicalHeader(_ header: DocHeader) -> Data {
        let parts = [
            String(header.v),
            header.kind.kotlinName,
            header.householdId,
            header.writerId,
            String(header.version),
            header.prevHash ?? "",
            String(header.headSeq),
            header.headHash,
            String(header.issuedAtEpochMs),
        ]
        return WaveCrypto.lengthPrefixed(parts.map(WaveCrypto.utf8))
    }

    /// 署名の対象: 見出し・nonce・暗号文・宛先ごとの包み（宛先 ID の順）。
    private static func signedBytes(_ doc: SealedDoc) -> Data {
        var parts = [canonicalHeader(doc.header), WaveCrypto.utf8(doc.nonce), WaveCrypto.utf8(doc.ciphertext)]
        // 同じ ID が並んだときも C# の OrderBy と同じく元の順を保つ（安定な並べ替え）
        let ordered = doc.recipients.enumerated().sorted { a, b in
            if ordinalLess(a.element.deviceId, b.element.deviceId) { return true }
            if ordinalLess(b.element.deviceId, a.element.deviceId) { return false }
            return a.offset < b.offset
        }
        for (_, r) in ordered {
            parts.append(WaveCrypto.utf8(r.deviceId))
            parts.append(WaveCrypto.utf8(r.enc))
            parts.append(WaveCrypto.utf8(r.wrappedKey))
        }
        return WaveCrypto.lengthPrefixed(parts)
    }

    /// 長さ（4バイト）‖ 本文 ‖ 0 埋めで、4KB 単位にそろえる。
    private static func pad(_ plaintext: Data) -> Data {
        let total = (plaintext.count + 4 + padBlock - 1) / padBlock * padBlock
        var output = WaveCrypto.lengthPrefixed(plaintext)
        output.append(Data(count: total - output.count))
        return output
    }

    private static func unpad(_ padded: Data) -> Data? {
        let bytes = [UInt8](padded)
        if bytes.count < 4 || bytes.count % padBlock != 0 { return nil }
        let size = Int32(bitPattern: UInt32(bytes[0]) << 24 | UInt32(bytes[1]) << 16 | UInt32(bytes[2]) << 8 | UInt32(bytes[3]))
        if size < 0 || Int(size) > bytes.count - 4 { return nil }
        return Data(bytes[4..<(4 + Int(size))])
    }
}
