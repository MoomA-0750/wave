import Foundation

/// 状態の種類（サーバーが版を持つもの）。
public enum StateKind: String, Sendable {
    case cursor, queue

    /// 状態の種類に対応する doc の種類。
    public var docKind: DocKind { self == .cursor ? .cursor : .queue }
}

/// サーバーが持つ最新の状態（配るときの形）。
public struct StoredState: Equatable, Sendable, ConnectCodable {
    public var kind: StateKind
    public var version: Int64
    public var doc: SealedDoc
    public var storeId: String
    public var receivedAtServerMs: Int64
    public var serverNowMs: Int64
    public var activeDeviceId: String?

    public init(
        kind: StateKind, version: Int64, doc: SealedDoc, storeId: String, receivedAtServerMs: Int64, serverNowMs: Int64, activeDeviceId: String? = nil
    ) {
        self.kind = kind
        self.version = version
        self.doc = doc
        self.storeId = storeId
        self.receivedAtServerMs = receivedAtServerMs
        self.serverNowMs = serverNowMs
        self.activeDeviceId = activeDeviceId
    }

    public func toJSON() -> JSON {
        .obj([
            "kind": .string(kind.rawValue), "version": JSON(version), "doc": doc.toJSON(), "storeId": .string(storeId),
            "receivedAtServerMs": JSON(receivedAtServerMs), "serverNowMs": JSON(serverNowMs), "activeDeviceId": JSON(activeDeviceId),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> StoredState {
        let r = try FieldReader(json)
        return StoredState(
            kind: try r.enumValue("kind", StateKind.self), version: try r.long("version"), doc: try r.value("doc", SealedDoc.self),
            storeId: try r.string("storeId"), receivedAtServerMs: try r.long("receivedAtServerMs"), serverNowMs: try r.long("serverNowMs"),
            activeDeviceId: try r.optString("activeDeviceId"))
    }
}

/// 中継 v2 のメッセージ（`/v2/connect`、`/v2/pair`。判別子は `op`）。Android 版の RelayV2 と同じ JSON。
/// サーバーは SealedDoc の中身を読まない。
public enum RelayV2: Equatable, Sendable {
    public static let authLabel = "wave-relay-v2-auth"
    public static let maxFrameChars = 1_200_000
    public static let maxPairFrameChars = 8 * 1024

    // --- 接続 ---
    case challenge(Challenge)
    case auth(Auth)
    case welcome(Welcome)

    // --- 端末一覧の鎖 ---
    case householdCreate(HouseholdCreate)
    case chainGet(ChainGet)
    case chain(Chain)
    case chainAppend(ChainAppend)
    case chainOk(ChainHead)
    case chainConflict(ChainHead)
    case chainChanged(ChainHead)

    // --- 状態 ---
    case stateGet
    case statePut(StatePut)
    case stateOk(StateOk)
    case stateConflict(StateConflict)
    case state(State)

    // --- 再生を始める・引き取る ---
    case claim(Claim)
    case transfer(Transfer)
    case yieldDelivery(YieldDelivery)
    case yielded(Yielded)

    /// 遠隔操作（命令の doc）。中継は書き手が送り主であることだけを確かめ、家のほかの端末に配る。保存しない。
    case control(Control)

    case release(Release)
    case activeChanged(ActiveChanged)
    case pendingExpired(PendingExpired)
    case presence(Presence)

    // --- 加入（Hello・Confirm は不透明な JSON） ---
    case pairingOpen(PairingOpen)
    case pairHelloIn(PairHelloIn)
    case pairHello(PairHello)
    case pairConfirm(PairConfirm)
    case pairConfirmed(PairConfirmed)
    case error(ErrorReply)

    public static func encode(_ message: RelayV2) -> String { ConnectJSON.serialize(message.toJSON()) }

    /// 読めない・知らない op なら nil。
    public static func decode(_ text: String) -> RelayV2? {
        guard let json = try? JSON.parse(text) else { return nil }
        return try? fromJSON(json)
    }

    public struct Challenge: Equatable, Sendable {
        public var host: String
        public var nonce: String
        public init(host: String, nonce: String) {
            self.host = host
            self.nonce = nonce
        }
    }

    public struct Auth: Equatable, Sendable {
        public var publicKey: String
        public var signature: String
        public init(publicKey: String, signature: String) {
            self.publicKey = publicKey
            self.signature = signature
        }
    }

    public struct Welcome: Equatable, Sendable {
        public var deviceId: String
        public var storeId: String
        public var householdId: String?
        public var headSeq: Int64?
        public var headHash: String?
        public var activeDeviceId: String?
        public var online: [String]

        /// 最新の状態（welcome と一緒に渡し、端末が「最新を見た上で」つなぎ直しの判断をできるようにする）。
        public var states: [StoredState]

        /// 端末ごとの最新の claim の順番（同時に始めたときの判断、D-8）。届いた順を保つ。
        public var claimOrders: [(String, Int64)]

        public init(
            deviceId: String, storeId: String, householdId: String? = nil, headSeq: Int64? = nil, headHash: String? = nil, activeDeviceId: String? = nil,
            online: [String] = [], states: [StoredState] = [], claimOrders: [(String, Int64)] = []
        ) {
            self.deviceId = deviceId
            self.storeId = storeId
            self.householdId = householdId
            self.headSeq = headSeq
            self.headHash = headHash
            self.activeDeviceId = activeDeviceId
            self.online = online
            self.states = states
            self.claimOrders = claimOrders
        }

        public static func == (a: Welcome, b: Welcome) -> Bool {
            a.deviceId == b.deviceId && a.storeId == b.storeId && a.householdId == b.householdId && a.headSeq == b.headSeq && a.headHash == b.headHash
                && a.activeDeviceId == b.activeDeviceId && a.online == b.online && a.states == b.states && a.claimOrders.elementsEqual(b.claimOrders, by: ==)
        }
    }

    public struct HouseholdCreate: Equatable, Sendable {
        public var genesis: ChainRecord
        public init(genesis: ChainRecord) { self.genesis = genesis }
    }

    public struct ChainGet: Equatable, Sendable {
        public var fromSeq: Int64
        public init(fromSeq: Int64 = 0) { self.fromSeq = fromSeq }
    }

    public struct Chain: Equatable, Sendable {
        public var records: [ChainRecord]
        public init(records: [ChainRecord]) { self.records = records }
    }

    public struct ChainAppend: Equatable, Sendable {
        public var record: ChainRecord
        public init(record: ChainRecord) { self.record = record }
    }

    /// chain_ok・chain_conflict・chain_changed の中身（同じ形）。
    public struct ChainHead: Equatable, Sendable {
        public var headSeq: Int64
        public var headHash: String
        public init(headSeq: Int64, headHash: String) {
            self.headSeq = headSeq
            self.headHash = headHash
        }
    }

    public struct StatePut: Equatable, Sendable {
        public var requestId: String
        public var kind: StateKind
        public var expectVersion: Int64
        public var doc: SealedDoc
        public init(requestId: String, kind: StateKind, expectVersion: Int64, doc: SealedDoc) {
            self.requestId = requestId
            self.kind = kind
            self.expectVersion = expectVersion
            self.doc = doc
        }
    }

    public struct StateOk: Equatable, Sendable {
        public var requestId: String
        public var kind: StateKind
        public var version: Int64
        public init(requestId: String, kind: StateKind, version: Int64) {
            self.requestId = requestId
            self.kind = kind
            self.version = version
        }
    }

    public struct StateConflict: Equatable, Sendable {
        public var requestId: String
        public var kind: StateKind
        public var latest: StoredState?
        public init(requestId: String, kind: StateKind, latest: StoredState? = nil) {
            self.requestId = requestId
            self.kind = kind
            self.latest = latest
        }
    }

    /// JSON の名前は "state"。
    public struct State: Equatable, Sendable {
        public var stored: StoredState
        public init(stored: StoredState) { self.stored = stored }
    }

    public struct Claim: Equatable, Sendable {
        public var claimId: String
        public var yieldDoc: SealedDoc
        public init(claimId: String, yieldDoc: SealedDoc) {
            self.claimId = claimId
            self.yieldDoc = yieldDoc
        }
    }

    public struct Transfer: Equatable, Sendable {
        public var claimId: String
        public var basedOnVersion: Int64
        public var yieldDoc: SealedDoc
        public init(claimId: String, basedOnVersion: Int64, yieldDoc: SealedDoc) {
            self.claimId = claimId
            self.basedOnVersion = basedOnVersion
            self.yieldDoc = yieldDoc
        }
    }

    /// op は "yield"。
    public struct YieldDelivery: Equatable, Sendable {
        public var claimId: String
        public var yieldDoc: SealedDoc
        public init(claimId: String, yieldDoc: SealedDoc) {
            self.claimId = claimId
            self.yieldDoc = yieldDoc
        }
    }

    public struct Yielded: Equatable, Sendable {
        public var claimId: String
        public var expectVersion: Int64
        public var doc: SealedDoc
        public init(claimId: String, expectVersion: Int64, doc: SealedDoc) {
            self.claimId = claimId
            self.expectVersion = expectVersion
            self.doc = doc
        }
    }

    public struct Control: Equatable, Sendable {
        public var doc: SealedDoc
        public init(doc: SealedDoc) { self.doc = doc }
    }

    public struct Release: Equatable, Sendable {
        public var claimId: String
        public init(claimId: String) { self.claimId = claimId }
    }

    public struct ActiveChanged: Equatable, Sendable {
        /// Kotlin では既定値の無い null 可の項目。欠けていても nil として読む。
        public var activeDeviceId: String?

        public var claimId: String?
        public var claimOrder: Int64

        /// transfer の途中（新しい端末の最初の書き込みを待っている）。
        public var pendingOwner: String?

        public init(activeDeviceId: String? = nil, claimId: String? = nil, claimOrder: Int64, pendingOwner: String? = nil) {
            self.activeDeviceId = activeDeviceId
            self.claimId = claimId
            self.claimOrder = claimOrder
            self.pendingOwner = pendingOwner
        }
    }

    public struct PendingExpired: Equatable, Sendable {
        public var claimId: String
        public init(claimId: String) { self.claimId = claimId }
    }

    public struct Presence: Equatable, Sendable {
        public var online: [String]
        public init(online: [String]) { self.online = online }
    }

    public struct PairingOpen: Equatable, Sendable {
        public var tokenHash: String
        public init(tokenHash: String) { self.tokenHash = tokenHash }
    }

    public struct PairHelloIn: Equatable, Sendable {
        public var token: String
        public var hello: JSON
        public init(token: String, hello: JSON) {
            self.token = token
            self.hello = hello
        }
    }

    public struct PairHello: Equatable, Sendable {
        public var tokenHash: String
        public var hello: JSON
        public init(tokenHash: String, hello: JSON) {
            self.tokenHash = tokenHash
            self.hello = hello
        }
    }

    public struct PairConfirm: Equatable, Sendable {
        public var tokenHash: String
        public var confirm: JSON
        public init(tokenHash: String, confirm: JSON) {
            self.tokenHash = tokenHash
            self.confirm = confirm
        }
    }

    public struct PairConfirmed: Equatable, Sendable {
        public var confirm: JSON
        public init(confirm: JSON) { self.confirm = confirm }
    }

    /// op は "error"（Swift の Error と名前が重ならないよう ErrorReply）。
    public struct ErrorReply: Equatable, Sendable {
        public var code: String
        public var requestId: String?
        public init(code: String, requestId: String? = nil) {
            self.code = code
            self.requestId = requestId
        }
    }

    // MARK: JSON（判別子はオブジェクトの先頭に出し、読むときは位置を問わない）

    public func toJSON() -> JSON {
        func with(_ op: String, _ pairs: [(String, JSON)]) -> JSON { .object(JSONObject([("op", .string(op))] + pairs)) }
        switch self {
        case .challenge(let m): return with("challenge", [("host", .string(m.host)), ("nonce", .string(m.nonce))])
        case .auth(let m): return with("auth", [("publicKey", .string(m.publicKey)), ("signature", .string(m.signature))])
        case .welcome(let m):
            return with(
                "welcome",
                [
                    ("deviceId", .string(m.deviceId)), ("storeId", .string(m.storeId)), ("householdId", JSON(m.householdId)), ("headSeq", JSON(m.headSeq)),
                    ("headHash", JSON(m.headHash)), ("activeDeviceId", JSON(m.activeDeviceId)), ("online", JSON(strings: m.online)),
                    ("states", JSON(items: m.states)), ("claimOrders", .object(JSONObject(m.claimOrders.map { ($0.0, JSON($0.1)) }))),
                ])
        case .householdCreate(let m): return with("household_create", [("genesis", m.genesis.toJSON())])
        case .chainGet(let m): return with("chain_get", [("fromSeq", JSON(m.fromSeq))])
        case .chain(let m): return with("chain", [("records", JSON(items: m.records))])
        case .chainAppend(let m): return with("chain_append", [("record", m.record.toJSON())])
        case .chainOk(let m): return with("chain_ok", [("headSeq", JSON(m.headSeq)), ("headHash", .string(m.headHash))])
        case .chainConflict(let m): return with("chain_conflict", [("headSeq", JSON(m.headSeq)), ("headHash", .string(m.headHash))])
        case .chainChanged(let m): return with("chain_changed", [("headSeq", JSON(m.headSeq)), ("headHash", .string(m.headHash))])
        case .stateGet: return with("state_get", [])
        case .statePut(let m):
            return with(
                "state_put",
                [("requestId", .string(m.requestId)), ("kind", .string(m.kind.rawValue)), ("expectVersion", JSON(m.expectVersion)), ("doc", m.doc.toJSON())])
        case .stateOk(let m):
            return with("state_ok", [("requestId", .string(m.requestId)), ("kind", .string(m.kind.rawValue)), ("version", JSON(m.version))])
        case .stateConflict(let m):
            return with("state_conflict", [("requestId", .string(m.requestId)), ("kind", .string(m.kind.rawValue)), ("latest", JSON(m.latest))])
        case .state(let m): return with("state", [("state", m.stored.toJSON())])
        case .claim(let m): return with("claim", [("claimId", .string(m.claimId)), ("yieldDoc", m.yieldDoc.toJSON())])
        case .transfer(let m):
            return with("transfer", [("claimId", .string(m.claimId)), ("basedOnVersion", JSON(m.basedOnVersion)), ("yieldDoc", m.yieldDoc.toJSON())])
        case .yieldDelivery(let m): return with("yield", [("claimId", .string(m.claimId)), ("yieldDoc", m.yieldDoc.toJSON())])
        case .yielded(let m):
            return with("yielded", [("claimId", .string(m.claimId)), ("expectVersion", JSON(m.expectVersion)), ("doc", m.doc.toJSON())])
        case .control(let m): return with("control", [("doc", m.doc.toJSON())])
        case .release(let m): return with("release", [("claimId", .string(m.claimId))])
        case .activeChanged(let m):
            return with(
                "active_changed",
                [
                    ("activeDeviceId", JSON(m.activeDeviceId)), ("claimId", JSON(m.claimId)), ("claimOrder", JSON(m.claimOrder)),
                    ("pendingOwner", JSON(m.pendingOwner)),
                ])
        case .pendingExpired(let m): return with("pending_expired", [("claimId", .string(m.claimId))])
        case .presence(let m): return with("presence", [("online", JSON(strings: m.online))])
        case .pairingOpen(let m): return with("pairing_open", [("tokenHash", .string(m.tokenHash))])
        case .pairHelloIn(let m): return with("pair_hello_in", [("token", .string(m.token)), ("hello", m.hello)])
        case .pairHello(let m): return with("pair_hello", [("tokenHash", .string(m.tokenHash)), ("hello", m.hello)])
        case .pairConfirm(let m): return with("pair_confirm", [("tokenHash", .string(m.tokenHash)), ("confirm", m.confirm)])
        case .pairConfirmed(let m): return with("pair_confirmed", [("confirm", m.confirm)])
        case .error(let m): return with("error", [("code", .string(m.code)), ("requestId", JSON(m.requestId))])
        }
    }

    static func fromJSON(_ json: JSON) throws -> RelayV2 {
        let r = try FieldReader(json)
        func head() throws -> ChainHead { ChainHead(headSeq: try r.long("headSeq"), headHash: try r.string("headHash")) }
        switch try r.string("op") {
        case "challenge": return .challenge(Challenge(host: try r.string("host"), nonce: try r.string("nonce")))
        case "auth": return .auth(Auth(publicKey: try r.string("publicKey"), signature: try r.string("signature")))
        case "welcome":
            return .welcome(
                Welcome(
                    deviceId: try r.string("deviceId"), storeId: try r.string("storeId"), householdId: try r.optString("householdId"),
                    headSeq: try r.optLong("headSeq"), headHash: try r.optString("headHash"), activeDeviceId: try r.optString("activeDeviceId"),
                    online: try r.strings("online", default: []), states: try r.array("states", StoredState.self, default: []),
                    claimOrders: try r.longMap("claimOrders", default: [])))
        case "household_create": return .householdCreate(HouseholdCreate(genesis: try r.value("genesis", ChainRecord.self)))
        case "chain_get": return .chainGet(ChainGet(fromSeq: try r.long("fromSeq", default: 0)))
        case "chain": return .chain(Chain(records: try r.array("records", ChainRecord.self)))
        case "chain_append": return .chainAppend(ChainAppend(record: try r.value("record", ChainRecord.self)))
        case "chain_ok": return .chainOk(try head())
        case "chain_conflict": return .chainConflict(try head())
        case "chain_changed": return .chainChanged(try head())
        case "state_get": return .stateGet
        case "state_put":
            return .statePut(
                StatePut(
                    requestId: try r.string("requestId"), kind: try r.enumValue("kind", StateKind.self), expectVersion: try r.long("expectVersion"),
                    doc: try r.value("doc", SealedDoc.self)))
        case "state_ok":
            return .stateOk(StateOk(requestId: try r.string("requestId"), kind: try r.enumValue("kind", StateKind.self), version: try r.long("version")))
        case "state_conflict":
            return .stateConflict(
                StateConflict(
                    requestId: try r.string("requestId"), kind: try r.enumValue("kind", StateKind.self), latest: try r.optValue("latest", StoredState.self)))
        case "state": return .state(State(stored: try r.value("state", StoredState.self)))
        case "claim": return .claim(Claim(claimId: try r.string("claimId"), yieldDoc: try r.value("yieldDoc", SealedDoc.self)))
        case "transfer":
            return .transfer(
                Transfer(claimId: try r.string("claimId"), basedOnVersion: try r.long("basedOnVersion"), yieldDoc: try r.value("yieldDoc", SealedDoc.self)))
        case "yield": return .yieldDelivery(YieldDelivery(claimId: try r.string("claimId"), yieldDoc: try r.value("yieldDoc", SealedDoc.self)))
        case "yielded":
            return .yielded(
                Yielded(claimId: try r.string("claimId"), expectVersion: try r.long("expectVersion"), doc: try r.value("doc", SealedDoc.self)))
        case "control": return .control(Control(doc: try r.value("doc", SealedDoc.self)))
        case "release": return .release(Release(claimId: try r.string("claimId")))
        case "active_changed":
            return .activeChanged(
                ActiveChanged(
                    activeDeviceId: try r.optString("activeDeviceId"), claimId: try r.optString("claimId"), claimOrder: try r.long("claimOrder"),
                    pendingOwner: try r.optString("pendingOwner")))
        case "pending_expired": return .pendingExpired(PendingExpired(claimId: try r.string("claimId")))
        case "presence": return .presence(Presence(online: try r.strings("online")))
        case "pairing_open": return .pairingOpen(PairingOpen(tokenHash: try r.string("tokenHash")))
        case "pair_hello_in": return .pairHelloIn(PairHelloIn(token: try r.string("token"), hello: try r.raw("hello")))
        case "pair_hello": return .pairHello(PairHello(tokenHash: try r.string("tokenHash"), hello: try r.raw("hello")))
        case "pair_confirm": return .pairConfirm(PairConfirm(tokenHash: try r.string("tokenHash"), confirm: try r.raw("confirm")))
        case "pair_confirmed": return .pairConfirmed(PairConfirmed(confirm: try r.raw("confirm")))
        case "error": return .error(ErrorReply(code: try r.string("code"), requestId: try r.optString("requestId")))
        default: throw ConnectDecodeError(field: "op")
        }
    }
}
