import CryptoKit
import Foundation

/// 取った曲の形式（AudioCache の形式に、取り終えた印と曲の情報を足したもの。URL は持たない）。
public struct DownloadedFile: Codable, Equatable, Sendable {
    /// videoId（RSS のエピソードは `ext:` で始まる）。
    public var id: String
    /// YouTube の形式（RSS のエピソードは 0）。
    public var itag: Int
    public var mimeType: String
    public var length: Int64
    public var dash: DashInfo?
    public var complete: Bool
    public var song: DownloadSong
    public var downloadedAtMs: Int64

    public var format: CachedFormat {
        CachedFormat(videoId: id, itag: itag, mimeType: mimeType, length: length, dash: dash, lastUsedMs: downloadedAtMs)
    }
}

/// 取った曲を置く、消えない置き場（ADR-0016 の 4）。曲ごとに `<名前>.bin`（音）と `<名前>.json`（形式・曲の情報）を置く。
/// 名前は YouTube の曲なら videoId、RSS のエピソードは音声の URL の SHA-256。上限では消さない（目録の持ち主がいなくなったら消す）。
/// RangeStream の置き場として読み書きでき、取り終えた曲は通信せずに読める。
public final class DownloadStore: ByteStore, @unchecked Sendable {
    private let directory: URL
    private let nowMs: @Sendable () -> Int64
    private let lock = NSLock()
    private var files: [String: DownloadedFile] = [:]
    private var loaded = false
    /// これから取る曲の情報（begin で書く json に入れる）。
    private var pendingSongs: [String: DownloadSong] = [:]

    public init(directory: URL, nowMs: @escaping @Sendable () -> Int64) {
        self.directory = directory
        self.nowMs = nowMs
    }

    // MARK: 名前

    /// ファイルの名前（拡張子なし）。
    public static func fileName(_ id: String) -> String {
        if AudioCache.isSafeId(id) { return id }
        let digest = SHA256.hash(data: Data((Feed.audioUrlOf(id) ?? id).utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public func bytesUrl(_ id: String) -> URL { directory.appendingPathComponent(Self.fileName(id) + ".bin") }
    private func jsonUrl(_ id: String) -> URL { directory.appendingPathComponent(Self.fileName(id) + ".json") }

    // MARK: 読む

    /// 取り終えた曲（ファイルの大きさが形式の長さに届いているもの）。
    public func completed(_ id: String) -> DownloadedFile? {
        lock.withLock {
            loadIfNeeded()
            guard let file = files[id], file.complete, size(id) >= file.length else { return nil }
            return file
        }
    }

    /// 取り終えた曲の id。
    public var completedIds: Set<String> {
        lock.withLock {
            loadIfNeeded()
            return Set(files.values.filter { $0.complete }.map(\.id))
        }
    }

    /// 置いてある曲（途中のものも）の id。
    public var allIds: Set<String> {
        lock.withLock {
            loadIfNeeded()
            return Set(files.keys)
        }
    }

    /// 取っている途中の曲の、取れたバイト数と全体。
    public func progress(_ id: String) -> (have: Int64, total: Int64)? {
        lock.withLock {
            loadIfNeeded()
            guard let file = files[id] else { return nil }
            return (size(id), file.length)
        }
    }

    public var totalBytes: Int64 {
        lock.withLock {
            loadIfNeeded()
            return files.keys.reduce(0) { $0 + size($1) }
        }
    }

    // MARK: ByteStore（YouTube の曲を RangeStream で取る・読む）

    public func entry(_ videoId: String) -> (format: CachedFormat, cached: Int64)? {
        lock.withLock {
            loadIfNeeded()
            guard let file = files[videoId] else { return nil }
            return (file.format, size(videoId))
        }
    }

    /// これから取る曲の情報を渡す（begin で書く）。
    public func prepare(_ song: DownloadSong) {
        lock.withLock { pendingSongs[song.videoId] = song }
    }

    /// 取り始める。前に取りかけた分が同じ形式なら続きから、違えば捨てて頭から。
    public func begin(_ stream: ResolvedStream) {
        lock.withLock {
            loadIfNeeded()
            if let old = files[stream.videoId], old.itag == stream.itag, old.length == stream.contentLength { return }
            guard let length = stream.contentLength, length > 0, AudioCache.isSafeId(stream.videoId) else { return }
            let song = pendingSongs[stream.videoId] ?? files[stream.videoId]?.song
                ?? DownloadSong(Song(videoId: stream.videoId, title: "", artists: [], album: nil, durationSeconds: nil, thumbnailUrl: nil))
            removeFiles(stream.videoId)
            let file = DownloadedFile(id: stream.videoId, itag: stream.itag, mimeType: stream.mimeType, length: length, dash: stream.dash, complete: false,
                                      song: song, downloadedAtMs: nowMs())
            createDirectory()
            files[stream.videoId] = file
            writeJson(file)
            FileManager.default.createFile(atPath: bytesUrl(stream.videoId).path, contents: nil)
        }
    }

    public func read(_ videoId: String, start: Int64, end: Int64) -> Data? {
        lock.withLock {
            loadIfNeeded()
            guard files[videoId] != nil, start >= 0, end >= start, end < size(videoId),
                  let handle = try? FileHandle(forReadingFrom: bytesUrl(videoId)) else { return nil }
            defer { try? handle.close() }
            try? handle.seek(toOffset: UInt64(start))
            guard let data = try? handle.read(upToCount: Int(end - start + 1)), data.count == Int(end - start + 1) else { return nil }
            return data
        }
    }

    public func append(_ videoId: String, offset: Int64, data: Data) {
        lock.withLock {
            guard let file = files[videoId] else { return }
            let have = size(videoId)
            guard offset <= have, offset + Int64(data.count) > have, have < file.length else { return }
            let tail = data.subdata(in: Int(have - offset)..<data.count)
            guard let handle = try? FileHandle(forWritingTo: bytesUrl(videoId)) else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: tail.prefix(Int(file.length - have)))
        }
    }

    /// 頭から最後まで取れていれば、取り終えた印を付ける。付けたら true。
    @discardableResult
    public func finish(_ id: String) -> Bool {
        lock.withLock {
            guard var file = files[id], size(id) >= file.length else { return false }
            file.complete = true
            file.downloadedAtMs = nowMs()
            if let song = pendingSongs.removeValue(forKey: id) { file.song = song }
            files[id] = file
            writeJson(file)
            return true
        }
    }

    // MARK: RSS のエピソード

    /// 丸ごと取ったファイル（一時ファイル）を置き場へ移し、取り終えた印を付ける。
    public func storeWhole(_ song: DownloadSong, mimeType: String, from temp: URL) throws {
        try lock.withLock {
            loadIfNeeded()
            createDirectory()
            removeFiles(song.videoId)
            try FileManager.default.moveItem(at: temp, to: bytesUrl(song.videoId))
            let file = DownloadedFile(id: song.videoId, itag: 0, mimeType: mimeType, length: size(song.videoId), dash: nil, complete: true, song: song,
                                      downloadedAtMs: nowMs())
            files[song.videoId] = file
            writeJson(file)
        }
    }

    // MARK: 消す

    public func remove(_ id: String) {
        lock.withLock {
            loadIfNeeded()
            removeFiles(id)
            files.removeValue(forKey: id)
            pendingSongs.removeValue(forKey: id)
        }
    }

    /// ログアウト・「すべて削除」。
    public func clear() {
        lock.withLock {
            try? FileManager.default.removeItem(at: directory)
            files = [:]
            pendingSongs = [:]
            loaded = true
        }
    }

    // MARK: 中身

    private func createDirectory() {
        guard !FileManager.default.fileExists(atPath: directory.path) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #if os(iOS)
            // iPhone のバックアップに入れない（取り直せるもの）
            var url = directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        #endif
    }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        createDirectory()
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasSuffix(".json") {
            guard let data = FileManager.default.contents(atPath: directory.appendingPathComponent(name).path),
                  let file = try? JSONDecoder().decode(DownloadedFile.self, from: data), Self.fileName(file.id) + ".json" == name else { continue }
            files[file.id] = file
        }
    }

    private func writeJson(_ file: DownloadedFile) {
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: jsonUrl(file.id), options: .atomic)
    }

    private func removeFiles(_ id: String) {
        try? FileManager.default.removeItem(at: bytesUrl(id))
        try? FileManager.default.removeItem(at: jsonUrl(id))
    }

    private func size(_ id: String) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: bytesUrl(id).path))?[.size] as? NSNumber)?.int64Value ?? 0
    }
}

/// ダウンロードに使ってよい通信か（ADR-0016 の 8）。
public enum DownloadNetworkPolicy: String, Codable, CaseIterable, Sendable {
    /// Wi-Fi など従量制でない通信だけ（既定）
    case unmetered
    /// どの通信でも
    case any

    /// つながっていて、従量制（高い・節約の通信）でなければ、または「どの通信でも」なら取ってよい。
    public func allows(connected: Bool, metered: Bool) -> Bool { connected && (self == .any || !metered) }
}
