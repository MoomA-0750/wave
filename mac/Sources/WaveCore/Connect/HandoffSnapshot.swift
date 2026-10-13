import Foundation

/// 引き取りのときに送るキューのスナップショット（Android 版の HandoffSnapshot と同じ JSON）。
/// 端末の中の状態と認証情報は入れない。端末の外に出るので、プラットフォームに依らない形に凍結する。
public struct HandoffSnapshot: Equatable, Sendable, ConnectCodable {
    public var schemaVersion: Int

    /// 引き取りの ID（UUID）。
    public var handoffId: String

    public var items: [HandoffItem]
    public var currentItemId: String
    public var positionMs: Int64
    public var playing: Bool
    public var `repeat`: HandoffRepeat
    public var source: HandoffSource?
    public var continuation: String?
    public var radioPlaylistId: String?
    public var exhausted: Bool

    /// 今の曲を聴いた時間（送る側の単調時計で数えた値）。
    public var listenedMs: Int64

    /// 今の曲の再生履歴を送り済みか。
    public var historySent: Bool

    /// 表示とログのためだけ（端末間の時計はずれるので判定に使わない）。
    public var capturedAtEpochMs: Int64

    public init(
        schemaVersion: Int, handoffId: String, items: [HandoffItem], currentItemId: String, positionMs: Int64, playing: Bool, repeat: HandoffRepeat,
        source: HandoffSource? = nil, continuation: String? = nil, radioPlaylistId: String? = nil, exhausted: Bool, listenedMs: Int64,
        historySent: Bool, capturedAtEpochMs: Int64
    ) {
        self.schemaVersion = schemaVersion
        self.handoffId = handoffId
        self.items = items
        self.currentItemId = currentItemId
        self.positionMs = positionMs
        self.playing = playing
        self.repeat = `repeat`
        self.source = source
        self.continuation = continuation
        self.radioPlaylistId = radioPlaylistId
        self.exhausted = exhausted
        self.listenedMs = listenedMs
        self.historySent = historySent
        self.capturedAtEpochMs = capturedAtEpochMs
    }

    public func toJSON() -> JSON {
        .obj([
            "schemaVersion": JSON(schemaVersion), "handoffId": .string(handoffId), "items": JSON(items: items), "currentItemId": .string(currentItemId),
            "positionMs": JSON(positionMs), "playing": .bool(playing), "repeat": .string(`repeat`.rawValue), "source": JSON(source),
            "continuation": JSON(continuation), "radioPlaylistId": JSON(radioPlaylistId), "exhausted": .bool(exhausted),
            "listenedMs": JSON(listenedMs), "historySent": .bool(historySent), "capturedAtEpochMs": JSON(capturedAtEpochMs),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> HandoffSnapshot {
        let r = try FieldReader(json)
        return HandoffSnapshot(
            schemaVersion: try r.int("schemaVersion"), handoffId: try r.string("handoffId"), items: try r.array("items", HandoffItem.self),
            currentItemId: try r.string("currentItemId"), positionMs: try r.long("positionMs"), playing: try r.bool("playing"),
            repeat: try r.enumValue("repeat", HandoffRepeat.self), source: try r.optValue("source", HandoffSource.self),
            continuation: try r.optString("continuation"), radioPlaylistId: try r.optString("radioPlaylistId"), exhausted: try r.bool("exhausted"),
            listenedMs: try r.long("listenedMs"), historySent: try r.bool("historySent"), capturedAtEpochMs: try r.long("capturedAtEpochMs"))
    }
}

public struct HandoffItem: Equatable, Sendable, ConnectCodable {
    /// 項目ごとの ID（UUID）。
    public var itemId: String

    public var videoId: String
    public var title: String
    public var artists: [HandoffRef]
    public var album: HandoffRef?
    public var durationSeconds: Int?
    public var thumbnailUrl: String?
    public var origin: HandoffOrigin

    public init(
        itemId: String, videoId: String, title: String, artists: [HandoffRef], album: HandoffRef? = nil, durationSeconds: Int? = nil,
        thumbnailUrl: String? = nil, origin: HandoffOrigin
    ) {
        self.itemId = itemId
        self.videoId = videoId
        self.title = title
        self.artists = artists
        self.album = album
        self.durationSeconds = durationSeconds
        self.thumbnailUrl = thumbnailUrl
        self.origin = origin
    }

    public func toJSON() -> JSON {
        .obj([
            "itemId": .string(itemId), "videoId": .string(videoId), "title": .string(title), "artists": JSON(items: artists), "album": JSON(album),
            "durationSeconds": JSON(durationSeconds), "thumbnailUrl": JSON(thumbnailUrl), "origin": .string(origin.rawValue),
        ])
    }

    public static func fromJSON(_ json: JSON) throws -> HandoffItem {
        let r = try FieldReader(json)
        return HandoffItem(
            itemId: try r.string("itemId"), videoId: try r.string("videoId"), title: try r.string("title"), artists: try r.array("artists", HandoffRef.self),
            album: try r.optValue("album", HandoffRef.self), durationSeconds: try r.optInt("durationSeconds"), thumbnailUrl: try r.optString("thumbnailUrl"),
            origin: try r.enumValue("origin", HandoffOrigin.self))
    }
}

public struct HandoffRef: Equatable, Sendable, ConnectCodable {
    public var name: String
    public var browseId: String?

    public init(name: String, browseId: String? = nil) {
        self.name = name
        self.browseId = browseId
    }

    public func toJSON() -> JSON { .obj(["name": .string(name), "browseId": JSON(browseId)]) }

    public static func fromJSON(_ json: JSON) throws -> HandoffRef {
        let r = try FieldReader(json)
        return HandoffRef(name: try r.string("name"), browseId: try r.optString("browseId"))
    }
}

public enum HandoffOrigin: String, Sendable {
    case source
    case suggestion
    case userAdded = "user"
}

public enum HandoffRepeat: String, Sendable {
    case off, one, all
}

/// 判別子は "type"。
public enum HandoffSource: Equatable, Sendable, ConnectCodable {
    case radio(seedVideoId: String)
    case playlist(playlistId: String)

    public func toJSON() -> JSON {
        switch self {
        case .radio(let seed): return .obj(["type": "radio", "seedVideoId": .string(seed)])
        case .playlist(let id): return .obj(["type": "playlist", "playlistId": .string(id)])
        }
    }

    public static func fromJSON(_ json: JSON) throws -> HandoffSource {
        let r = try FieldReader(json)
        switch try r.string("type") {
        case "radio": return .radio(seedVideoId: try r.string("seedVideoId"))
        case "playlist": return .playlist(playlistId: try r.string("playlistId"))
        default: throw ConnectDecodeError(field: "type")
        }
    }
}

/// 受け取ったスナップショットの検証の結果。
public enum HandoffDecodeResult: Equatable, Sendable {
    case ok(HandoffSnapshot)

    /// 形式のバージョンが違う（相手を更新する必要がある）。
    case unsupportedVersion(Int?)

    /// 読めない・上限を超える・矛盾している。理由は種類だけ（値を含めない）。
    case rejected(String)
}

/// スナップショットの文字列の形（Android 版の HandoffCodec と同じ）。
public enum HandoffCodec {
    public static let schemaVersion = 1

    /// 暗号化する前のバイト数の上限。
    public static let maxBytes = 512 * 1024

    public static let maxPlayedItems = 20
    public static let maxUpcomingItems = 300
    private static let maxText = 500
    private static let maxContinuation = 4096
    private static let maxArtists = 20
    private static let maxTimeMs: Int64 = 24 * 3600 * 1000
    private static let thumbnailHosts = [".googleusercontent.com", ".ggpht.com", ".ytimg.com"]

    public static func encode(_ snapshot: HandoffSnapshot) -> String { ConnectJSON.encode(snapshot) }

    /// 受け取った文字列を読み、上限と形を検証する。サムネイルのホストが合わないものは、スナップショットを拒否せずに nil にする。
    /// - Parameter expectedHandoffId: 発行して待っている ID。違えば拒否
    public static func decode(_ text: String, expectedHandoffId: String) -> HandoffDecodeResult {
        if text.utf8.count > maxBytes { return .rejected("too_large") }
        // 読めない JSON は「相手を更新して」ではなく拒否
        guard let root = try? JSON.parse(text), case .object(let object) = root else { return .rejected("unreadable") }
        var version: Int?
        if let v = object["schemaVersion"], !v.isNull {
            guard let n = try? FieldReader.integer(v, "schemaVersion"), let n32 = try? FieldReader.int32(n, "schemaVersion") else {
                return .rejected("unreadable")
            }
            version = n32
        }
        if version != schemaVersion { return .unsupportedVersion(version) }
        guard let snapshot = ConnectJSON.decode(HandoffSnapshot.self, json: root) else { return .rejected("unreadable") }
        return validate(snapshot, expectedHandoffId: expectedHandoffId)
    }

    public static func validate(_ snapshot: HandoffSnapshot, expectedHandoffId: String) -> HandoffDecodeResult {
        func reject(_ reason: String) -> HandoffDecodeResult { .rejected(reason) }
        if snapshot.handoffId != expectedHandoffId || !isUuid(snapshot.handoffId) { return reject("handoff_id") }
        if snapshot.items.isEmpty || snapshot.items.count > maxPlayedItems + 1 + maxUpcomingItems { return reject("item_count") }
        let ids = snapshot.items.map(\.itemId)
        if Set(ids).count != ids.count || ids.contains(where: { !isUuid($0) }) { return reject("item_id") }
        guard let currentIndex = ids.firstIndex(of: snapshot.currentItemId) else { return reject("current_item") }
        if currentIndex > maxPlayedItems || snapshot.items.count - 1 - currentIndex > maxUpcomingItems { return reject("item_window") }
        if snapshot.listenedMs < 0 || snapshot.positionMs < 0 { return reject("negative_time") }
        for item in snapshot.items {
            if !isItemVideoId(item.videoId) { return reject("video_id") }
            if item.title.utf16.isEmpty || item.title.utf16.count > maxText { return reject("title") }
            if item.artists.count > maxArtists || item.artists.contains(where: { !validRef($0) }) { return reject("artist") }
            if let album = item.album, !validRef(album) { return reject("album") }
            if let d = item.durationSeconds, d < 0 || d > 24 * 3600 { return reject("duration") }
        }
        switch snapshot.source {
        case .radio(let seed) where !isVideoId(seed): return reject("source")
        case .playlist(let id) where !isPlaylistId(id): return reject("source")
        default: break
        }
        if let radio = snapshot.radioPlaylistId, !isPlaylistId(radio) { return reject("radio_playlist") }
        if let continuation = snapshot.continuation,
            continuation.utf16.count > maxContinuation || continuation.unicodeScalars.contains(where: isIsoControl)
        {
            return reject("continuation")
        }
        let current = snapshot.items[currentIndex]
        let limit = current.durationSeconds.map { Int64($0) * 1000 } ?? maxTimeMs
        var result = snapshot
        result.items = snapshot.items.map { item in
            var copy = item
            copy.thumbnailUrl = item.thumbnailUrl.flatMap { allowedThumbnail($0) ? $0 : nil }
            return copy
        }
        // 位置と聴いた時間を曲の長さ以下（長さが無ければ24時間以下）に切り詰める（拒否はしない）
        result.positionMs = min(snapshot.positionMs, limit)
        result.listenedMs = min(snapshot.listenedMs, limit)
        return .ok(result)
    }

    /// 送る側の切り詰め: 今の曲より前は maxPlayedItems 件、後ろは maxUpcomingItems 件まで。
    /// - Returns: （切り詰めた項目, 今の項目の位置, continuation を捨てたか）
    public static func window<T>(_ items: [T], currentIndex: Int) -> (items: [T], currentIndex: Int, droppedTail: Bool) {
        let start = max(currentIndex - maxPlayedItems, 0)
        let end = min(currentIndex + 1 + maxUpcomingItems, items.count)
        return (Array(items[start..<end]), currentIndex - start, end < items.count)
    }

    private static func validRef(_ reference: HandoffRef) -> Bool {
        !reference.name.utf16.isEmpty && reference.name.utf16.count <= maxText && (reference.browseId.map(isBrowseId) ?? true)
    }

    /// https・既知の画像ホストだけ（ポート・userinfo の無いもの）。
    private static func allowedThumbnail(_ url: String) -> Bool {
        if !url.hasPrefix("https://") || url.utf16.count > 2048 { return false }
        // バックスラッシュ・空白・制御文字は、URL パーサによって区切りや別のホストとして解釈されるので、どこにあっても拒否する
        if url.unicodeScalars.contains(where: { $0 == "\\" || $0.properties.isWhitespace || isIsoControl($0) }) { return false }
        var authority = Substring(url.dropFirst("https://".count))
        for separator: Character in ["/", "?", "#"] {
            if let index = authority.firstIndex(of: separator) { authority = authority[..<index] }
        }
        if !isHost(authority) { return false }
        return thumbnailHosts.contains { authority.hasSuffix($0) }
    }

    /// Java の Character.isISOControl と同じ（U+0000〜U+001F・U+007F〜U+009F）。
    static func isIsoControl(_ c: Unicode.Scalar) -> Bool { c.value <= 0x1f || (c.value >= 0x7f && c.value <= 0x9f) }

    // 正規表現の代わりの形の確かめ（Windows 版の Regex と同じ範囲・長さ）

    private static func isIdChar(_ c: Unicode.Scalar) -> Bool {
        switch c {
        case "A"..."Z", "a"..."z", "0"..."9", "_", "-": return true
        default: return false
        }
    }

    /// `\A[A-Za-z0-9_-]{11}\z`
    static func isVideoId(_ s: String) -> Bool { s.unicodeScalars.count == 11 && s.unicodeScalars.allSatisfy(isIdChar) }

    /// キューの曲の ID。YouTube の曲か、RSS のエピソード（`ext:` + https の音声の URL。ADR-0012）。
    public static func isItemVideoId(_ s: String) -> Bool {
        isVideoId(s) || (Feed.audioUrlOf(s) != nil && !s.unicodeScalars.contains(where: isIsoControl))
    }

    /// `\A[A-Za-z0-9_-]{2,64}\z`
    static func isBrowseId(_ s: String) -> Bool { (2...64).contains(s.unicodeScalars.count) && s.unicodeScalars.allSatisfy(isIdChar) }

    /// `\A[A-Za-z0-9_-]{2,128}\z`
    static func isPlaylistId(_ s: String) -> Bool { (2...128).contains(s.unicodeScalars.count) && s.unicodeScalars.allSatisfy(isIdChar) }

    /// `\A[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\z`
    static func isUuid(_ s: String) -> Bool {
        let scalars = Array(s.unicodeScalars)
        guard scalars.count == 36 else { return false }
        for (i, c) in scalars.enumerated() {
            if i == 8 || i == 13 || i == 18 || i == 23 {
                if c != "-" { return false }
            } else {
                switch c {
                case "0"..."9", "a"..."f", "A"..."F": continue
                default: return false
                }
            }
        }
        return true
    }

    /// `\A[a-z0-9.-]{1,253}\z`
    private static func isHost<S: StringProtocol>(_ s: S) -> Bool {
        (1...253).contains(s.unicodeScalars.count)
            && s.unicodeScalars.allSatisfy {
                switch $0 {
                case "a"..."z", "0"..."9", ".", "-": return true
                default: return false
                }
            }
    }
}
