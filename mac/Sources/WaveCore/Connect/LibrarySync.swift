import Foundation

// MARK: ライブラリの文書（ADR-0013。Android 版の handoff-protocol の LibraryBody・LibraryFeed・LibraryPosition と同じ JSON）

/// ライブラリの文書の中身。RSS で登録した番組と、エピソードの聴いたところ。
/// 番組・エピソードごとに、updatedAtMs の新しい方を残して合わせる（merge）。外した番組も印（removed）として残す。
public struct LibraryBody: Equatable, Sendable, ConnectCodable {
    public static let version = 1
    /// 聴いたところは新しい順にこの数まで（文書を数百 KB に収める）。
    public static let maxPositions = 500
    /// 外した番組の印は、この日数を過ぎたら捨ててよい（全端末に行き渡った後）。
    public static let removedKeepDays = 180

    public var v: Int
    /// フィードの URL ごと。
    public var feeds: [String: LibraryFeed]
    /// 音声の URL ごと。
    public var positions: [String: LibraryPosition]

    public init(v: Int = LibraryBody.version, feeds: [String: LibraryFeed] = [:], positions: [String: LibraryPosition] = [:]) {
        self.v = v
        self.feeds = feeds
        self.positions = positions
    }

    /// 2つを合わせる。同じ時刻なら other を残す（自分の書き込みが後から届いた相手の書き込みを消さないよう、呼ぶ側は相手を other に渡す）。
    public func merge(_ other: LibraryBody) -> LibraryBody {
        var feeds: [String: LibraryFeed] = [:]
        for key in Set(self.feeds.keys).union(other.feeds.keys) {
            feeds[key] = LibraryBody.newer(self.feeds[key], other.feeds[key], \.updatedAtMs)
        }
        var positions: [String: LibraryPosition] = [:]
        for key in Set(self.positions.keys).union(other.positions.keys) {
            positions[key] = LibraryBody.newer(self.positions[key], other.positions[key], \.updatedAtMs)
        }
        return LibraryBody(v: LibraryBody.version, feeds: feeds, positions: LibraryBody.trim(positions))
    }

    private static func newer<T>(_ mine: T?, _ other: T?, _ at: KeyPath<T, Int64>) -> T? {
        guard let mine else { return other }
        guard let other else { return mine }
        return mine[keyPath: at] > other[keyPath: at] ? mine : other
    }

    private static func trim(_ positions: [String: LibraryPosition]) -> [String: LibraryPosition] {
        if positions.count <= maxPositions { return positions }
        let kept = positions.sorted { $0.value.updatedAtMs > $1.value.updatedAtMs }.prefix(maxPositions)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
    }

    public func toJSON() -> JSON {
        // 辞書の並びは決まらないので、キーの順に出す（同じ中身なら同じ文字列になる）
        .obj([
            "v": JSON(v),
            "feeds": .object(JSONObject(feeds.keys.sorted().map { ($0, feeds[$0]!.toJSON()) })),
            "positions": .object(JSONObject(positions.keys.sorted().map { ($0, positions[$0]!.toJSON()) })),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> LibraryBody {
        let r = try FieldReader(json)
        func map<T: ConnectCodable>(_ key: String, _ type: T.Type) throws -> [String: T] {
            switch json[key] {
            case nil, .null?: return [:]
            case .object(let o)?:
                var out: [String: T] = [:]
                for (k, v) in o.pairs { out[k] = try T.fromJSON(v) }
                return out
            default: throw ConnectDecodeError(field: key)
            }
        }
        return LibraryBody(v: try r.int("v", default: LibraryBody.version), feeds: try map("feeds", LibraryFeed.self),
                           positions: try map("positions", LibraryPosition.self))
    }
}

public struct LibraryFeed: Equatable, Sendable, ConnectCodable {
    public var title: String
    public var author: String?
    public var thumbnailUrl: String?
    public var addedAtMs: Int64
    /// 外した（この印のほうが新しければ、ほかの端末でも外す）。
    public var removed: Bool
    public var updatedAtMs: Int64

    public init(title: String, author: String? = nil, thumbnailUrl: String? = nil, addedAtMs: Int64 = 0, removed: Bool = false, updatedAtMs: Int64) {
        self.title = title
        self.author = author
        self.thumbnailUrl = thumbnailUrl
        self.addedAtMs = addedAtMs
        self.removed = removed
        self.updatedAtMs = updatedAtMs
    }

    public func toJSON() -> JSON {
        .obj([
            "title": .string(title), "author": author.map(JSON.string) ?? .null, "thumbnailUrl": thumbnailUrl.map(JSON.string) ?? .null,
            "addedAtMs": JSON(addedAtMs), "removed": .bool(removed), "updatedAtMs": JSON(updatedAtMs),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> LibraryFeed {
        let r = try FieldReader(json)
        return LibraryFeed(title: try r.string("title"), author: try r.optString("author"), thumbnailUrl: try r.optString("thumbnailUrl"),
                           addedAtMs: try r.long("addedAtMs", default: 0), removed: try r.bool("removed", default: false),
                           updatedAtMs: try r.long("updatedAtMs"))
    }
}

public struct LibraryPosition: Equatable, Sendable, ConnectCodable {
    public var positionSeconds: Int
    public var durationSeconds: Int?
    public var finished: Bool
    public var updatedAtMs: Int64

    public init(positionSeconds: Int, durationSeconds: Int? = nil, finished: Bool = false, updatedAtMs: Int64) {
        self.positionSeconds = positionSeconds
        self.durationSeconds = durationSeconds
        self.finished = finished
        self.updatedAtMs = updatedAtMs
    }

    public func toJSON() -> JSON {
        .obj([
            "positionSeconds": JSON(positionSeconds), "durationSeconds": JSON(durationSeconds), "finished": .bool(finished),
            "updatedAtMs": JSON(updatedAtMs),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> LibraryPosition {
        let r = try FieldReader(json)
        return LibraryPosition(positionSeconds: try r.int("positionSeconds"), durationSeconds: try r.optInt("durationSeconds"),
                               finished: try r.bool("finished", default: false), updatedAtMs: try r.long("updatedAtMs"))
    }
}

/// 中継が持つ最新のライブラリの文書。
public struct LibraryState: Equatable, Sendable, ConnectCodable {
    public var version: Int64
    public var doc: SealedDoc
    public var storeId: String

    public init(version: Int64, doc: SealedDoc, storeId: String) {
        self.version = version
        self.doc = doc
        self.storeId = storeId
    }

    public func toJSON() -> JSON { .obj(["version": JSON(version), "doc": doc.toJSON(), "storeId": .string(storeId)]) }

    public static func fromJSON(_ json: JSON) throws -> LibraryState {
        let r = try FieldReader(json)
        return LibraryState(version: try r.long("version"), doc: try r.value("doc", SealedDoc.self), storeId: try r.string("storeId"))
    }
}

// MARK: 同期（Android 版の LibrarySync と同じ動き）

/// ライブラリ（RSS で登録した番組・聴いたところ）を、中継の家の端末の間で合わせる（ADR-0013）。
/// 中継の最新の文書を開いて手元と合わせ、手元にしか無い・手元のほうが新しいものがあれば、版の CAS で書く。
/// 書き込みは1本ずつ。食い違ったら（ほかの端末が先に書いた）、届いた最新と合わせて書き直す。
/// 呼ぶ側は ConnectClient と同じ1本の流れから呼ぶ（スレッドをまたがない）。
public final class LibrarySync {
    private let signer: DeviceSigner
    private let opener: KxOpener
    private let send: (RelayV2) -> Bool
    /// 検証した今の一覧（無ければ書かない・開かない）。
    private let chain: () -> VerifiedChain?
    /// 中継の保存の ID（ConnectClient が受け入れたもの）。違う保存の文書は取り込まない。
    private let storeId: () -> String?
    private let epochMs: () -> Int64
    private let newId: () -> String
    /// 中継から合わせた結果が届いた（手元の状態に取り込む）。
    private let onRemote: (LibraryBody) -> Void

    /// 中継の最新の版と中身（開けたもの）。
    private var remoteVersion: Int64 = 0
    private var remoteBody: LibraryBody?
    private var remoteHash: String?
    /// 手元の今の中身（書きたいもの）。
    private var local: LibraryBody?
    /// 返事を待っている書き込み。
    private var inflight: String?
    /// 書いている中身（返事が来たら中継の中身とする）。
    private var pendingBody: LibraryBody?
    /// 接続して library_get の返事を受けたか（受けるまで書かない。中継の版を知らずに 0 で書くと食い違う）。
    private var synced = false
    /// 開けなかった文書（一覧が古い）。一覧が届いたら開き直す。
    private var deferred: LibraryState?

    public init(
        signer: DeviceSigner, opener: KxOpener, send: @escaping (RelayV2) -> Bool, chain: @escaping () -> VerifiedChain?, storeId: @escaping () -> String?,
        epochMs: @escaping () -> Int64, newId: @escaping () -> String, onRemote: @escaping (LibraryBody) -> Void
    ) {
        self.signer = signer
        self.opener = opener
        self.send = send
        self.chain = chain
        self.storeId = storeId
        self.epochMs = epochMs
        self.newId = newId
        self.onRemote = onRemote
    }

    /// つながった（welcome の後）。最新を頼む。古い中継は知らない命令として error を返すだけ（書かないまま）。
    public func onConnected() {
        synced = false
        inflight = nil
        _ = send(.libraryGet)
    }

    public func onDisconnected() {
        synced = false
        inflight = nil
    }

    /// 一覧が新しくなった。開けずにいた文書を開き直す。
    public func onChainUpdated() throws {
        guard let waiting = deferred else { return }
        deferred = nil
        adopt(waiting)
        try flush()
    }

    /// 手元が変わった。中継のものと合わせて、違えば書く。
    public func publish(_ body: LibraryBody) throws {
        local = body
        try flush()
    }

    public func onMessage(_ message: RelayV2) throws {
        switch message {
        case .library(let m):
            synced = true
            if let state = m.state { adopt(state) }
            try flush()
        case .libraryOk(let m):
            guard m.requestId == inflight else { return }
            inflight = nil
            remoteVersion = m.version
            remoteBody = pendingBody
            remoteHash = nil
            pendingBody = nil
            try flush()
        case .libraryConflict(let m):
            guard m.requestId == inflight else { return }
            inflight = nil
            pendingBody = nil
            if let latest = m.latest { adopt(latest) }
            try flush()
        default:
            break
        }
    }

    /// 中継の文書を開いて取り込む。古い版・違う保存・開けないものは捨てる。
    private func adopt(_ state: LibraryState) {
        if state.storeId != storeId() { return }
        if state.version < remoteVersion { return }
        let hash = DocSealer.hash(state.doc)
        if state.version == remoteVersion && hash == remoteHash { return }
        guard let current = chain() else {
            deferred = state
            return
        }
        let header: DocHeader
        let plaintext: Data
        switch DocSealer.open(state.doc, chain: current, me: opener) {
        case .ok(let h, let p, _):
            header = h
            plaintext = p
        case .needChain:
            deferred = state
            return
        default:
            return
        }
        if header.kind != .library || header.version != state.version { return }
        guard let body = ConnectJSON.decode(LibraryBody.self, from: plaintext) else { return }
        remoteVersion = state.version
        remoteHash = hash
        remoteBody = body
        // 手元と合わせたものを取り込んでもらう（手元のほうが新しい分は、次の flush で書く）
        let merged = local?.merge(body) ?? body
        local = merged
        onRemote(merged)
    }

    /// 手元に中継より新しいものがあれば書く。
    private func flush() throws {
        if !synced || inflight != nil { return }
        guard let mine = local else { return }
        let remote = remoteBody
        // 同じ時刻なら中継の側を残す
        let merged = remote.map { mine.merge($0) } ?? mine
        if let remote, merged == remote { return }
        if remote == nil && merged.feeds.isEmpty && merged.positions.isEmpty { return }
        guard let current = chain(), current.members[signer.deviceId] != nil else { return }
        let plaintext = ConnectJSON.encodeToUTF8(merged)
        if plaintext.count > DocSealer.maxPlaintext { return }
        let requestId = newId()
        let doc = try DocSealer.seal(
            signer: signer, chain: current, kind: .library, version: remoteVersion + 1, prevHash: nil, issuedAtEpochMs: epochMs(), plaintext: plaintext)
        if send(.libraryPut(.init(requestId: requestId, expectVersion: remoteVersion, doc: doc))) {
            inflight = requestId
            pendingBody = merged
            local = merged
        }
    }
}
