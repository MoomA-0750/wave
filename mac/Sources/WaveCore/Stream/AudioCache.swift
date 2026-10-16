import Foundation

/// キャッシュに残した曲の形式（URL は持たない）。これがあれば、URL を調べ直さずに残した分から鳴らし始められる。
public struct CachedFormat: Codable, Equatable, Sendable {
    public var videoId: String
    public var itag: Int
    public var mimeType: String
    public var length: Int64
    public var dash: DashInfo?
    public var lastUsedMs: Int64
}

/// 曲の音のバイトを端末に残す（Android の SimpleCache と同じ役目）。曲ごとに、頭から途切れずに取れた分だけを1つのファイルに持つ。
/// 曲送り・戻しでは残した分から鳴らし、続きは鳴らしながら取る。上限を超えたら、最後に使ったのが古い曲から消す。
/// ファイルには音のバイトと形式だけを書き、署名付き URL は書かない。
public final class AudioCache: @unchecked Sendable {
    public static let defaultMaxBytes: Int64 = 300 * 1024 * 1024

    private let directory: URL
    private let maxBytes: Int64
    private let nowMs: @Sendable () -> Int64
    private let lock = NSLock()
    /// 形式の写し（ファイルを毎回読まない）。
    private var formats: [String: CachedFormat] = [:]
    private var loaded = false

    public init(directory: URL, maxBytes: Int64 = AudioCache.defaultMaxBytes, nowMs: @escaping @Sendable () -> Int64) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.nowMs = nowMs
    }

    // MARK: 形式

    /// 残してある曲の形式と、頭から残っているバイト数。無ければ nil。
    public func entry(_ videoId: String) -> (format: CachedFormat, cached: Int64)? {
        lock.withLock {
            loadIfNeeded()
            guard let format = formats[videoId] else { return nil }
            return (format, size(videoId))
        }
    }

    /// これから取る曲の形式を書く。前に残した分が別の形式（itag・長さが違う）なら捨てる。
    public func begin(_ stream: ResolvedStream) {
        lock.withLock {
            loadIfNeeded()
            if let old = formats[stream.videoId], old.itag == stream.itag, old.length == stream.contentLength {
                formats[stream.videoId]?.lastUsedMs = nowMs()
                return
            }
            guard let length = stream.contentLength, length > 0, Self.isSafeId(stream.videoId) else { return }
            removeFiles(stream.videoId)
            let format = CachedFormat(videoId: stream.videoId, itag: stream.itag, mimeType: stream.mimeType, length: length, dash: stream.dash, lastUsedMs: nowMs())
            formats[stream.videoId] = format
            writeFormat(format)
            FileManager.default.createFile(atPath: bytesUrl(stream.videoId).path, contents: nil)
            trimLocked(keep: stream.videoId)
        }
    }

    // MARK: バイト

    /// start から end（含む）までが残っていれば返す。
    public func read(_ videoId: String, start: Int64, end: Int64) -> Data? {
        lock.withLock {
            guard formats[videoId] != nil, start >= 0, end >= start, end < size(videoId),
                  let handle = try? FileHandle(forReadingFrom: bytesUrl(videoId)) else { return nil }
            defer { try? handle.close() }
            try? handle.seek(toOffset: UInt64(start))
            guard let data = try? handle.read(upToCount: Int(end - start + 1)), data.count == Int(end - start + 1) else { return nil }
            formats[videoId]?.lastUsedMs = nowMs()
            return data
        }
    }

    /// offset から取ったバイトを足す。残している分の続き（か、それに重なる分）だけを足し、間が空くものは足さない。
    public func append(_ videoId: String, offset: Int64, data: Data) {
        lock.withLock {
            guard let format = formats[videoId] else { return }
            let have = size(videoId)
            guard offset <= have, offset + Int64(data.count) > have, have < format.length else { return }
            let tail = data.subdata(in: Int(have - offset)..<data.count)
            guard let handle = try? FileHandle(forWritingTo: bytesUrl(videoId)) else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: tail.prefix(Int(format.length - have)))
            formats[videoId]?.lastUsedMs = nowMs()
        }
    }

    /// ログアウトのとき（聴いた曲を残さない）。
    public func clear() {
        lock.withLock {
            try? FileManager.default.removeItem(at: directory)
            formats = [:]
            loaded = true
        }
    }

    /// 残しているバイトの合計。
    public var totalBytes: Int64 {
        lock.withLock {
            loadIfNeeded()
            return formats.keys.reduce(0) { $0 + size($1) }
        }
    }

    // MARK: 中身

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let files = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in files where name.hasSuffix(".json") {
            guard let data = FileManager.default.contents(atPath: directory.appendingPathComponent(name).path),
                  let format = try? JSONDecoder().decode(CachedFormat.self, from: data), Self.isSafeId(format.videoId) else { continue }
            formats[format.videoId] = format
        }
    }

    private func trimLocked(keep: String) {
        var total = formats.keys.reduce(0) { $0 + size($1) }
        // 今から取る曲の分も見込む
        total += formats[keep].map { $0.length - size(keep) } ?? 0
        for format in formats.values.sorted(by: { $0.lastUsedMs < $1.lastUsedMs }) where total > maxBytes && format.videoId != keep {
            total -= size(format.videoId)
            removeFiles(format.videoId)
            formats.removeValue(forKey: format.videoId)
        }
    }

    private func writeFormat(_ format: CachedFormat) {
        guard let data = try? JSONEncoder().encode(format) else { return }
        try? data.write(to: directory.appendingPathComponent("\(format.videoId).json"), options: .atomic)
    }

    private func removeFiles(_ videoId: String) {
        try? FileManager.default.removeItem(at: bytesUrl(videoId))
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("\(videoId).json"))
    }

    private func bytesUrl(_ videoId: String) -> URL { directory.appendingPathComponent("\(videoId).bin") }

    private func size(_ videoId: String) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: bytesUrl(videoId).path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /// ファイル名に使ってよい videoId（YouTube の 11 文字）。
    static func isSafeId(_ videoId: String) -> Bool { videoId.wholeMatch(of: #/[A-Za-z0-9_-]{11}/#) != nil }
}
