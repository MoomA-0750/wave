import Foundation

/// 音声の URL が拒否された（期限切れ・403 など）。URL は持たない。
public struct StreamRejectedError: Error, CustomStringConvertible, Sendable {
    public let status: Int
    public init(status: Int) { self.status = status }
    public var description: String { "stream rejected status=\(status)" }
}

/// 音声のバイトを HTTP の Range で少しずつ読む、位置を動かせる読み手（Android 版の DataSource と同じく、cookie を持たない
/// URLSession で googlevideo から取る）。再生の部品（AVAssetResourceLoaderDelegate など）にはこの読み手を渡し、署名付き URL は渡さない。
/// 拒否（403 など）は StreamRejectedError で知らせる（呼び出し側が解決し直し、replaceSource で URL を差し替えて続けるか、開き直す）。
/// AV に依らない。
public final class RangeStream: @unchecked Sendable {
    public static let chunkSize = 512 * 1024

    public enum SeekOrigin: Sendable {
        case begin
        case current
        case end
    }

    /// 動作の記録（位置・状態・バイト数だけ。URL は渡さない）。
    nonisolated(unsafe) public static var diagnostics: (@Sendable (String) -> Void)?

    private let session: URLSession
    /// 全体の長さ（バイト）。
    public let length: Int64
    private let lock = NSLock()
    /// 署名付き URL。キャッシュから鳴らし始めたときは、要るまで調べない（source で調べる）。
    private var url: String?
    /// URL を調べる（キャッシュから鳴らし始めたとき）。同じ形式（itag・長さ）のものだけを受け取る。
    private let source: (@Sendable () async throws -> ResolvedStream)?
    private var sourceTask: Task<String, Error>?
    private let itag: Int
    /// 取ったバイトを残し、残っている分はネットから取らない。
    private let cache: AudioCache?
    private let videoId: String
    private var positionValue: Int64 = 0
    private var chunk: [UInt8]?
    private var chunkStart: Int64 = 0
    /// read(count:) を1つずつ通す（位置と手元の区切りを共有するため）。
    private let gate = AsyncGate()

    private init(session: URLSession, url: String?, length: Int64, videoId: String = "", itag: Int = 0, cache: AudioCache? = nil,
                 source: (@Sendable () async throws -> ResolvedStream)? = nil)
    {
        self.session = session
        self.url = url
        self.length = length
        self.videoId = videoId
        self.itag = itag
        self.cache = cache
        self.source = source
    }

    /// キャッシュに残した曲を開く。残した分はすぐ読め、続きが要ったときに source で URL を調べる。
    public static func openCached(session: URLSession, format: CachedFormat, cache: AudioCache,
                                  source: @escaping @Sendable () async throws -> ResolvedStream) -> RangeStream
    {
        RangeStream(session: session, url: nil, length: format.length, videoId: format.videoId, itag: format.itag, cache: cache, source: source)
    }

    /// URL をまだ調べていなければ、今調べる（鳴らし始めてすぐ、続きを取る前に用意しておく）。
    public func prepareSource() async throws { _ = try await currentUrl() }

    /// 頭から upTo バイトまでを取ってキャッシュに残す（次の曲を先に用意する）。キャッシュが無ければ何もしない。
    public func warm(upTo: Int64) async throws {
        guard let cache else { return }
        let target = min(upTo, length)
        while true {
            let have = cache.entry(videoId)?.cached ?? 0
            guard have < target else { return }
            _ = try await fetch(start: have, end: min(target - 1, have + Int64(RangeStream.chunkSize) - 1))
        }
    }

    /// 開く。長さが分からなければ、先頭の1バイトを取って Content-Range から知る。
    public static func open(session: URLSession, stream: ResolvedStream, cache: AudioCache? = nil) async throws -> RangeStream {
        let url = stream.revealUrl()
        if !StreamResolver.isAllowedMediaUrl(url) { throw StreamRejectedError(status: 0) }
        let length = stream.contentLength ?? 0
        // 長さの分かる形式だけを残す
        if let cache, length > 0 {
            cache.begin(stream)
            return RangeStream(session: session, url: url, length: length, videoId: stream.videoId, itag: stream.itag, cache: cache)
        }
        let opened = RangeStream(session: session, url: url, length: length)
        if length <= 0 {
            let (_, response) = try await opened.send(start: 0, end: 0)
            guard let total = contentRangeLength(response.value(forHTTPHeaderField: "Content-Range")) else {
                throw StreamRejectedError(status: response.statusCode)
            }
            return RangeStream(session: session, url: url, length: total)
        }
        return opened
    }

    /// 「bytes 0-0/12345」の全体の長さ。
    static func contentRangeLength(_ header: String?) -> Int64? {
        guard let header, let slash = header.lastIndex(of: "/") else { return nil }
        return Int64(header[header.index(after: slash)...].trimmingCharacters(in: .whitespaces))
    }

    /// 解決し直したストリームの URL に差し替える（拒否のあと、同じ位置から読み続けるため）。
    /// 別の形式（長さが違う）なら StreamRejectedError(0) を投げ、差し替えない。
    /// （Windows 版には無い。macOS では再生の部品を作り直さずに続けられるので足した）
    public func replaceSource(_ stream: ResolvedStream) throws {
        let next = stream.revealUrl()
        if !StreamResolver.isAllowedMediaUrl(next) { throw StreamRejectedError(status: 0) }
        if let known = stream.contentLength, known > 0, known != length { throw StreamRejectedError(status: 0) }
        lock.withLock { url = next }
    }

    public var position: Int64 {
        get { lock.withLock { positionValue } }
        set { lock.withLock { positionValue = min(max(newValue, 0), length) } }
    }

    @discardableResult
    public func seek(_ offset: Int64, origin: SeekOrigin) -> Int64 {
        lock.withLock {
            let target: Int64
            switch origin {
            case .begin: target = offset
            case .current: target = positionValue + offset
            case .end: target = length + offset
            }
            positionValue = min(max(target, 0), length)
            return positionValue
        }
    }

    /// 今の位置から count バイトを、終わりに着くまで埋めて返す（区切りの途中で短く返すと、再生の部品が終わりと受け取るため）。
    /// 終わりに着いたら短く（終わりなら空で）返す。
    public func read(count: Int) async throws -> Data {
        try await gate.withGate { try await fill(count) }
    }

    /// offset から length バイトを読む（位置を offset へ動かしてから read(count:) と同じく読む）。
    public func read(offset: Int64, length count: Int) async throws -> Data {
        try await gate.withGate {
            seek(offset, origin: .begin)
            return try await fill(count)
        }
    }

    private func fill(_ count: Int) async throws -> Data {
        var out = Data()
        out.reserveCapacity(max(0, min(count, RangeStream.chunkSize * 8)))
        while out.count < count {
            let (pos, current, start) = lock.withLock { (positionValue, chunk, chunkStart) }
            if pos >= length { break }
            var block = current
            var blockStart = start
            if block == nil || pos < blockStart || pos >= blockStart + Int64(block!.count) {
                blockStart = pos
                let end = min(length - 1, pos + Int64(RangeStream.chunkSize) - 1)
                let fetched = try await fetch(start: pos, end: end)
                lock.withLock {
                    chunk = fetched
                    chunkStart = blockStart
                }
                block = fetched
                if fetched.isEmpty { break }
            }
            let from = Int(pos - blockStart)
            let n = min(count - out.count, block!.count - from)
            out.append(contentsOf: block![from..<(from + n)])
            lock.withLock { positionValue = pos + Int64(n) }
        }
        return out
    }

    /// start から end（含む）までのバイトを取る（再生の部品の求めに応じて使う）。位置は動かさない。
    public func readRange(start: Int64, end: Int64) async throws -> Data {
        let from = min(max(start, 0), length - 1)
        let to = min(max(end, start), length - 1)
        return Data(try await fetch(start: from, end: to))
    }

    /// キャッシュに残っている分はそこから、足りない分はネットから取る（取った分はキャッシュに足す）。
    private func fetch(start: Int64, end: Int64) async throws -> [UInt8] {
        guard let cache else { return try await fetchNetwork(start: start, end: end) }
        if let data = cache.read(videoId, start: start, end: end) { return [UInt8](data) }
        let have = cache.entry(videoId)?.cached ?? 0
        // 残した分の少し先から求められた（目次を頭より先に読むなど）: 間も一緒に取り、頭から途切れずに残す
        if start > have, start - have <= RangeStream.gapFillBytes {
            let all = try await fetchNetwork(start: have, end: end)
            cache.append(videoId, offset: have, data: Data(all))
            return Array(all.dropFirst(Int(start - have)))
        }
        var head: [UInt8] = []
        var from = start
        if have > start, let data = cache.read(videoId, start: start, end: have - 1) {
            head = [UInt8](data)
            from = have
        }
        let rest = try await fetchNetwork(start: from, end: end)
        cache.append(videoId, offset: from, data: Data(rest))
        return head + rest
    }

    /// 残した分との間がこれ以下なら、間も取って続けて残す。
    static let gapFillBytes: Int64 = 256 * 1024

    private func fetchNetwork(start: Int64, end: Int64) async throws -> [UInt8] {
        // 一時的な失敗は2回まで送り直す（Windows 版と同じく、拒否も2回まで送り直してから知らせる）
        var attempt = 0
        while true {
            do {
                let (data, response) = try await send(start: start, end: end)
                RangeStream.diagnostics?("range \(start)-\(end) status=\(response.statusCode) bytes=\(data.count)")
                return [UInt8](data)
            } catch {
                try rethrowIfCancelled(error)
                guard attempt < 2, error is URLError || error is StreamRejectedError else { throw error }
                try await Task.sleep(nanoseconds: UInt64(300 * (attempt + 1)) * 1_000_000)
                attempt += 1
            }
        }
    }

    /// 今の URL。まだ無ければ source で調べる（同時に何度も調べない）。調べた形式が残した形式と違えば拒否として知らせる。
    private func currentUrl() async throws -> String {
        let (known, running) = lock.withLock { (url, sourceTask) }
        if let known { return known }
        let task: Task<String, Error>
        if let running {
            task = running
        } else {
            guard let source else { throw StreamRejectedError(status: 0) }
            let (length, itag) = (self.length, self.itag)
            task = Task {
                let stream = try await source()
                let next = stream.revealUrl()
                guard stream.itag == itag, stream.contentLength == length, StreamResolver.isAllowedMediaUrl(next) else { throw StreamRejectedError(status: 0) }
                return next
            }
            lock.withLock { sourceTask = task }
        }
        do {
            let resolved = try await task.value
            lock.withLock { url = resolved }
            return resolved
        } catch {
            // 次に要ったときに調べ直す
            lock.withLock { if sourceTask == task { sourceTask = nil } }
            throw error
        }
    }

    private func send(start: Int64, end: Int64) async throws -> (Data, HTTPURLResponse) {
        guard var target = URL(string: try await currentUrl()) else { throw StreamRejectedError(status: 0) }
        // googlevideo は混んでいると別のサーバーへ 302 で回す。行き先が googlevideo の /videoplayback なら1回だけ追い、
        // 以後はその行き先から読む（Android の WaveStreamDataSource と同じ。追わないと、その曲がそこで止まる）
        var followed = false
        while true {
            var request = URLRequest(url: target)
            request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
            request.setValue(InnerTubeClient.userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if let http = response as? HTTPURLResponse, status == 206 || status == 200 { return (data, http) }
            if !followed, (300..<400).contains(status), let location = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Location"),
               let next = URL(string: location, relativeTo: target)?.absoluteURL, StreamResolver.isAllowedMediaUrl(next.absoluteString)
            {
                RangeStream.diagnostics?("range \(start)-\(end) redirected status=\(status)")
                followed = true
                target = next
                lock.withLock { url = next.absoluteString }
                continue
            }
            RangeStream.diagnostics?("range \(start)-\(end) rejected status=\(status)")
            throw StreamRejectedError(status: status)
        }
    }
}
