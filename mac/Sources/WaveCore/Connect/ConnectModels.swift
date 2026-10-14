import Foundation

// Connect v2 の形式（Android 版の ConnectModels.kt と同じ JSON）。端末一覧は署名付きの記録の鎖で、状態・命令は書いた端末の署名つきの SealedDoc。
// バイナリは base64url（パディングなし）の文字列で運ぶ。
// JSON のプロパティは Kotlin の宣言の順に並べる（kotlinx.serialization と同じ順で出す）。
// enum の JSON の名前は SerialName（"player" など、rawValue）、署名・ハッシュの対象には enum の名前（"Player" など、kotlinName）を使う。

public enum Role: String, Sendable, CaseIterable {
    /// 再生する端末（アカウントの照合あり）。
    case player

    /// 表示と操作だけ（PC）。再生しない、アカウントを持たない。
    case controller

    /// Kotlin の enum の名前（署名・ハッシュの対象）。
    public var kotlinName: String { self == .player ? "Player" : "Controller" }
}

public enum ChainType: String, Sendable {
    case genesis, add, remove

    public var kotlinName: String {
        switch self {
        case .genesis: return "Genesis"
        case .add: return "Add"
        case .remove: return "Remove"
        }
    }
}

/// 家に加わる端末の鍵。kxSignature は、その端末の身元鍵での `wave-v2-kx`（deviceId・kxPub・role）の署名。
public struct MemberKeys: Equatable, Sendable, ConnectCodable {
    public var deviceId: String

    /// 身元の署名公開鍵（SPKI）。deviceId はこの SHA-256。
    public var identityPub: String

    /// 受け取りの鍵（P-256、非圧縮 SEC1）。
    public var kxPub: String

    public var role: Role
    public var kxSignature: String

    public init(deviceId: String, identityPub: String, kxPub: String, role: Role, kxSignature: String) {
        self.deviceId = deviceId
        self.identityPub = identityPub
        self.kxPub = kxPub
        self.role = role
        self.kxSignature = kxSignature
    }

    public func toJSON() -> JSON {
        .obj([
            "deviceId": .string(deviceId), "identityPub": .string(identityPub), "kxPub": .string(kxPub), "role": .string(role.rawValue),
            "kxSignature": .string(kxSignature),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> MemberKeys {
        let r = try FieldReader(json)
        return MemberKeys(
            deviceId: try r.string("deviceId"), identityPub: try r.string("identityPub"), kxPub: try r.string("kxPub"),
            role: try r.enumValue("role", Role.self), kxSignature: try r.string("kxSignature"))
    }
}

/// 端末一覧の記録。genesis は householdId を持たない（householdId = genesis のハッシュ）。
/// add・genesis は target、remove は removedDeviceId を持つ。
public struct ChainRecord: Equatable, Sendable, ConnectCodable {
    public static let formatVersion = 1

    public var v: Int
    public var householdId: String?
    public var seq: Int64
    public var prevHash: String?
    public var type: ChainType
    public var target: MemberKeys?
    public var removedDeviceId: String?
    public var signerId: String
    public var signature: String

    public init(
        v: Int = ChainRecord.formatVersion, householdId: String? = nil, seq: Int64, prevHash: String? = nil, type: ChainType,
        target: MemberKeys? = nil, removedDeviceId: String? = nil, signerId: String, signature: String
    ) {
        self.v = v
        self.householdId = householdId
        self.seq = seq
        self.prevHash = prevHash
        self.type = type
        self.target = target
        self.removedDeviceId = removedDeviceId
        self.signerId = signerId
        self.signature = signature
    }

    public func toJSON() -> JSON {
        .obj([
            "v": JSON(v), "householdId": JSON(householdId), "seq": JSON(seq), "prevHash": JSON(prevHash), "type": .string(type.rawValue),
            "target": JSON(target), "removedDeviceId": JSON(removedDeviceId), "signerId": .string(signerId), "signature": .string(signature),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> ChainRecord {
        let r = try FieldReader(json)
        return ChainRecord(
            v: try r.int("v", default: formatVersion), householdId: try r.optString("householdId"), seq: try r.long("seq"),
            prevHash: try r.optString("prevHash"), type: try r.enumValue("type", ChainType.self), target: try r.optValue("target", MemberKeys.self),
            removedDeviceId: try r.optString("removedDeviceId"), signerId: try r.string("signerId"), signature: try r.string("signature"))
    }
}

public enum DocKind: String, Sendable {
    case cursor, queue, command, profile
    /// ライブラリ（RSS で登録した番組・聴いたところ、ADR-0013）。家のどの端末も書ける。
    case library

    public var kotlinName: String {
        switch self {
        case .cursor: return "Cursor"
        case .queue: return "Queue"
        case .command: return "Command"
        case .profile: return "Profile"
        case .library: return "Library"
        }
    }
}

/// SealedDoc の見出し（平文。署名と、内容・包みの AAD に入る）。
public struct DocHeader: Equatable, Sendable, ConnectCodable {
    public static let formatVersion = 1

    public var v: Int
    public var kind: DocKind
    public var householdId: String
    public var writerId: String
    public var version: Int64

    /// 同じ書き手の、同じ kind の直前の doc のハッシュ（差し替えの検出だけに使う）。
    public var prevHash: String?

    public var headSeq: Int64
    public var headHash: String
    public var issuedAtEpochMs: Int64

    public init(
        v: Int = DocHeader.formatVersion, kind: DocKind, householdId: String, writerId: String, version: Int64, prevHash: String?, headSeq: Int64,
        headHash: String, issuedAtEpochMs: Int64
    ) {
        self.v = v
        self.kind = kind
        self.householdId = householdId
        self.writerId = writerId
        self.version = version
        self.prevHash = prevHash
        self.headSeq = headSeq
        self.headHash = headHash
        self.issuedAtEpochMs = issuedAtEpochMs
    }

    public func toJSON() -> JSON {
        .obj([
            "v": JSON(v), "kind": .string(kind.rawValue), "householdId": .string(householdId), "writerId": .string(writerId), "version": JSON(version),
            "prevHash": JSON(prevHash), "headSeq": JSON(headSeq), "headHash": .string(headHash), "issuedAtEpochMs": JSON(issuedAtEpochMs),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> DocHeader {
        let r = try FieldReader(json)
        return DocHeader(
            v: try r.int("v", default: formatVersion), kind: try r.enumValue("kind", DocKind.self), householdId: try r.string("householdId"),
            writerId: try r.string("writerId"), version: try r.long("version"), prevHash: try r.optString("prevHash"), headSeq: try r.long("headSeq"),
            headHash: try r.string("headHash"), issuedAtEpochMs: try r.long("issuedAtEpochMs"))
    }
}

/// 宛先ごとの包み: 内容鍵を、その端末の kx へ HPKE で包んだもの。
public struct DocRecipient: Equatable, Sendable, ConnectCodable {
    public var deviceId: String
    public var enc: String
    public var wrappedKey: String

    public init(deviceId: String, enc: String, wrappedKey: String) {
        self.deviceId = deviceId
        self.enc = enc
        self.wrappedKey = wrappedKey
    }

    public func toJSON() -> JSON { .obj(["deviceId": .string(deviceId), "enc": .string(enc), "wrappedKey": .string(wrappedKey)]) }

    public static func fromJSON(_ json: JSON) throws -> DocRecipient {
        let r = try FieldReader(json)
        return DocRecipient(deviceId: try r.string("deviceId"), enc: try r.string("enc"), wrappedKey: try r.string("wrappedKey"))
    }
}

public struct SealedDoc: Equatable, Sendable, ConnectCodable {
    public var header: DocHeader
    public var nonce: String
    public var ciphertext: String
    public var recipients: [DocRecipient]
    public var signature: String

    public init(header: DocHeader, nonce: String, ciphertext: String, recipients: [DocRecipient], signature: String) {
        self.header = header
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.recipients = recipients
        self.signature = signature
    }

    public func toJSON() -> JSON {
        .obj([
            "header": header.toJSON(), "nonce": .string(nonce), "ciphertext": .string(ciphertext), "recipients": JSON(items: recipients),
            "signature": .string(signature),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> SealedDoc {
        let r = try FieldReader(json)
        return SealedDoc(
            header: try r.value("header", DocHeader.self), nonce: try r.string("nonce"), ciphertext: try r.string("ciphertext"),
            recipients: try r.array("recipients", DocRecipient.self), signature: try r.string("signature"))
    }
}

/// カーソル（D-5）。小さく、再生・一時停止・シーク・遷移のたびに書く。
public struct CursorBody: Equatable, Sendable, ConnectCodable {
    /// このカーソルの曲が載っているキューの doc のハッシュ（DocSealer.hash）。
    public var queueRef: String

    public var currentItemId: String
    public var positionMs: Int64
    public var playing: Bool
    public var `repeat`: HandoffRepeat

    /// このセッションを始めたときに見ていた最新のカーソルの版（L-10）。
    public var sessionBase: Int64

    /// このセッションで自分が最初に書けたカーソルの版。書けるまでは nil。
    public var sessionStartVersion: Int64?

    public var deviceSeq: Int64
    public var listenedMs: Int64
    public var historySent: Bool
    public var accountTag: String

    /// 表示だけ（順序の判断に使わない）。
    public var startedAtEpochMs: Int64

    /// 鳴らしている端末のアプリの中の音量（0〜100）。ほかの端末の音量の表示に使う。前の版の書き手は付けない（nil）。
    public var volumePercent: Int?

    public init(
        queueRef: String, currentItemId: String, positionMs: Int64, playing: Bool, repeat: HandoffRepeat, sessionBase: Int64,
        sessionStartVersion: Int64?, deviceSeq: Int64, listenedMs: Int64, historySent: Bool, accountTag: String, startedAtEpochMs: Int64,
        volumePercent: Int? = nil
    ) {
        self.volumePercent = volumePercent
        self.queueRef = queueRef
        self.currentItemId = currentItemId
        self.positionMs = positionMs
        self.playing = playing
        self.repeat = `repeat`
        self.sessionBase = sessionBase
        self.sessionStartVersion = sessionStartVersion
        self.deviceSeq = deviceSeq
        self.listenedMs = listenedMs
        self.historySent = historySent
        self.accountTag = accountTag
        self.startedAtEpochMs = startedAtEpochMs
    }

    public func toJSON() -> JSON {
        .obj([
            "queueRef": .string(queueRef), "currentItemId": .string(currentItemId), "positionMs": JSON(positionMs), "playing": .bool(playing),
            "repeat": .string(`repeat`.rawValue), "sessionBase": JSON(sessionBase), "sessionStartVersion": JSON(sessionStartVersion),
            "deviceSeq": JSON(deviceSeq), "listenedMs": JSON(listenedMs), "historySent": .bool(historySent), "accountTag": .string(accountTag),
            "startedAtEpochMs": JSON(startedAtEpochMs), "volumePercent": JSON(volumePercent.map(Int64.init)),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> CursorBody {
        let r = try FieldReader(json)
        return CursorBody(
            queueRef: try r.string("queueRef"), currentItemId: try r.string("currentItemId"), positionMs: try r.long("positionMs"),
            playing: try r.bool("playing"), repeat: try r.enumValue("repeat", HandoffRepeat.self), sessionBase: try r.long("sessionBase"),
            sessionStartVersion: try r.optLong("sessionStartVersion"), deviceSeq: try r.long("deviceSeq"), listenedMs: try r.long("listenedMs"),
            historySent: try r.bool("historySent"), accountTag: try r.string("accountTag"), startedAtEpochMs: try r.long("startedAtEpochMs"),
            volumePercent: try r.optLong("volumePercent").map { Int(clamping: $0) })
    }
}

/// キュー（D-5）。snapshot は HandoffCodec.encode の文字列（取り込む側が HandoffCodec.decode で検証する）。
public struct QueueBody: Equatable, Sendable, ConnectCodable {
    public var accountTag: String
    public var snapshot: String

    public init(accountTag: String, snapshot: String) {
        self.accountTag = accountTag
        self.snapshot = snapshot
    }

    public func toJSON() -> JSON { .obj(["accountTag": .string(accountTag), "snapshot": .string(snapshot)]) }

    public static func fromJSON(_ json: JSON) throws -> QueueBody {
        let r = try FieldReader(json)
        return QueueBody(accountTag: try r.string("accountTag"), snapshot: try r.string("snapshot"))
    }
}

/// 命令（判別子は "type"）。
public enum CommandBody: Equatable, Sendable, ConnectCodable {
    case yield(Yield)
    case control(Control)
    case queueAdd(QueueAdd)
    case profile(Profile)

    /// 遠隔のキューへの追加: 鳴っていない端末から、今の再生中の端末へ「この曲をキューに入れて」（Android 版の CommandBody.QueueAdd と同じ）。
    /// 従う条件は Control と同じ。受け手は afterItemId の曲があればその直後へ、無ければ next なら今の曲の直後、
    /// そうでなければ自分で足した曲のかたまりの最後へ、送り手が振った項目の ID のまま入れる。
    public struct QueueAdd: Equatable, Sendable {
        public var commandId: String
        public var from: String
        public var to: String
        public var items: [HandoffItem]
        /// 「次に再生」なら true、「キューに追加」なら false。
        public var next: Bool
        public var afterItemId: String?
        /// 送り手が見ていた、受け手のカーソルの版。
        public var basedOnVersion: Int64
        public var accountTag: String

        public init(
            commandId: String, from: String, to: String, items: [HandoffItem], next: Bool, afterItemId: String?, basedOnVersion: Int64, accountTag: String
        ) {
            self.commandId = commandId
            self.from = from
            self.to = to
            self.items = items
            self.next = next
            self.afterItemId = afterItemId
            self.basedOnVersion = basedOnVersion
            self.accountTag = accountTag
        }
    }

    /// 再生を始めた端末から、今の再生中の端末へ「止まって」（D-7）。
    public struct Yield: Equatable, Sendable {
        public var claimId: String
        public var from: String
        public var to: String
        public var sessionBase: Int64
        public var accountTag: String

        public init(claimId: String, from: String, to: String, sessionBase: Int64, accountTag: String) {
            self.claimId = claimId
            self.from = from
            self.to = to
            self.sessionBase = sessionBase
            self.accountTag = accountTag
        }
    }

    /// 遠隔操作: 鳴っていない端末から、今の再生中の端末へ「再生・一時停止・次へ・前へ・シーク」。
    /// 受け手は、自分宛て・自分が書き手・同じアカウント・処理していない ID・見ていた版が近いときだけ従う。
    public struct Control: Equatable, Sendable {
        public var commandId: String
        public var from: String
        public var to: String
        public var action: ControlAction

        /// シークの位置（seek のときだけ）。
        public var positionMs: Int64?

        /// 送り手が見ていた、受け手のカーソルの版。
        public var basedOnVersion: Int64

        public var accountTag: String

        /// 送り手が見ていた、受け手の今の曲（次へ・前へ・シーク）。受け手の今の曲と違えば従わない（Android と同じ）。前の版の送り手は付けない（nil）。
        public var onItemId: String?

        /// 音量（volume のときだけ、0〜100）。鳴らしている端末のアプリの中の音量にする（端末そのものの音量は変えない）。
        public var volumePercent: Int?

        public init(
            commandId: String, from: String, to: String, action: ControlAction, positionMs: Int64? = nil, basedOnVersion: Int64, accountTag: String,
            onItemId: String? = nil, volumePercent: Int? = nil
        ) {
            self.volumePercent = volumePercent
            self.commandId = commandId
            self.from = from
            self.to = to
            self.action = action
            self.positionMs = positionMs
            self.basedOnVersion = basedOnVersion
            self.accountTag = accountTag
            self.onItemId = onItemId
        }
    }

    /// 端末の表示名を家のほかの端末に知らせる（名前は鎖に入れないので、加入した2台以外にも伝える）。
    public struct Profile: Equatable, Sendable {
        public var from: String
        public var name: String

        /// 端末の種類（"phone"・"pc" など。表示の絵だけに使う）。
        public var kind: String?

        public init(from: String, name: String, kind: String? = nil) {
            self.from = from
            self.name = name
            self.kind = kind
        }
    }

    public func toJSON() -> JSON {
        switch self {
        case .yield(let y):
            return .obj([
                "type": "yield", "claimId": .string(y.claimId), "from": .string(y.from), "to": .string(y.to), "sessionBase": JSON(y.sessionBase),
                "accountTag": .string(y.accountTag),
            ])
        case .control(let c):
            return .obj([
                "type": "control", "commandId": .string(c.commandId), "from": .string(c.from), "to": .string(c.to), "action": .string(c.action.rawValue),
                "positionMs": JSON(c.positionMs), "basedOnVersion": JSON(c.basedOnVersion), "accountTag": .string(c.accountTag),
                "onItemId": JSON(c.onItemId), "volumePercent": JSON(c.volumePercent.map(Int64.init)),
            ])
        case .queueAdd(let q):
            return .obj([
                "type": "queue_add", "commandId": .string(q.commandId), "from": .string(q.from), "to": .string(q.to), "items": JSON(items: q.items),
                "next": .bool(q.next), "afterItemId": JSON(q.afterItemId), "basedOnVersion": JSON(q.basedOnVersion), "accountTag": .string(q.accountTag),
            ])
        case .profile(let p):
            return .obj(["type": "profile", "from": .string(p.from), "name": .string(p.name), "kind": JSON(p.kind)])
        }
    }

    public static func fromJSON(_ json: JSON) throws -> CommandBody {
        let r = try FieldReader(json)
        switch try r.string("type") {
        case "yield":
            return .yield(
                Yield(
                    claimId: try r.string("claimId"), from: try r.string("from"), to: try r.string("to"), sessionBase: try r.long("sessionBase"),
                    accountTag: try r.string("accountTag")))
        case "control":
            return .control(
                Control(
                    commandId: try r.string("commandId"), from: try r.string("from"), to: try r.string("to"),
                    action: try r.enumValue("action", ControlAction.self), positionMs: try r.optLong("positionMs"),
                    basedOnVersion: try r.long("basedOnVersion"), accountTag: try r.string("accountTag"), onItemId: try r.optString("onItemId"),
                    volumePercent: try r.optLong("volumePercent").map { Int(clamping: $0) }))
        case "queue_add":
            return .queueAdd(
                QueueAdd(
                    commandId: try r.string("commandId"), from: try r.string("from"), to: try r.string("to"), items: try r.array("items", HandoffItem.self),
                    next: try r.bool("next"), afterItemId: try r.optString("afterItemId"), basedOnVersion: try r.long("basedOnVersion"),
                    accountTag: try r.string("accountTag")))
        case "profile":
            return .profile(Profile(from: try r.string("from"), name: try r.string("name"), kind: try r.optString("kind")))
        default:
            throw ConnectDecodeError(field: "type")
        }
    }
}

public enum ControlAction: String, Sendable {
    case play, pause, next, previous, seek

    /// 宛先の端末に「ここで再生（引き取り）」を頼む。宛先は書き手でない端末。
    case transfer

    /// 鳴らしている端末のアプリの中の音量を変える（volumePercent）。
    case volume
}

public struct ProfileBody: Equatable, Sendable, ConnectCodable {
    public var name: String

    public init(name: String) { self.name = name }

    public func toJSON() -> JSON { .obj(["name": .string(name)]) }

    public static func fromJSON(_ json: JSON) throws -> ProfileBody { ProfileBody(name: try FieldReader(json).string("name")) }
}

/// 家ごとのアカウントタグ（D-3、Android 版の AccountTag と同じ）: `SHA-256(ラベル ‖ householdId ‖ DATASYNC_ID)` を base64url で。値はログに出さない。
public enum AccountTag {
    public static let label = "wave-v2-account-tag"

    public static func of(householdId: String, accountId: String) -> String {
        B64.encode(WaveCrypto.sha256(WaveCrypto.lengthPrefixed(WaveCrypto.utf8(label), WaveCrypto.utf8(householdId), WaveCrypto.utf8(accountId))))
    }
}
