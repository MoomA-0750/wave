import Foundation
import Network
import WaveCore

/// 曲の音を AVPlayer に HLS で渡すための、この端末の中だけの小さな HTTP の受け口（127.0.0.1 でだけ待つ）。
/// AVPlayer は HLS の区切りを独自の URL（wave-media://）では読まない（http(s) を求める）ので、ここから渡す。
/// 署名付き URL は渡さず、曲ごとに作る推測できない名前（token）の下で、RangeStream で取ったバイトを返す。
final class LocalMediaServer: @unchecked Sendable {
    static let shared = LocalMediaServer()

    static let playlistName = "index.m3u8"
    static let mediaName = "media.mp4"

    private struct Entry {
        let bytes: RangeStream
        let playlist: Data
        let contentType: String
    }

    private let queue = DispatchQueue(label: "wave.media.server")
    private let lock = NSLock()
    private var listener: NWListener?
    private var port: UInt16?
    private var entries: [String: Entry] = [:]

    /// 曲を置き、その再生の一覧の URL を返す。受け口を開けなければ nil。
    func register(bytes: RangeStream, playlist: String, contentType: String) async -> (token: String, url: URL)? {
        guard let port = await start() else { return nil }
        let token = UUID().uuidString.lowercased()
        lock.withLock { entries[token] = Entry(bytes: bytes, playlist: Data(playlist.utf8), contentType: contentType) }
        return (token, URL(string: "http://127.0.0.1:\(port)/\(token)/\(LocalMediaServer.playlistName)")!)
    }

    func unregister(_ token: String) {
        _ = lock.withLock { entries.removeValue(forKey: token) }
    }

    private func entry(_ token: String) -> Entry? { lock.withLock { entries[token] } }

    private func start() async -> UInt16? {
        if let port = lock.withLock({ port }) { return port }
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        parameters.acceptLocalOnly = true
        guard let listener = try? NWListener(using: parameters) else { return nil }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        let ready: UInt16? = await withCheckedContinuation { continuation in
            // 知らせは同じ待ち行列（queue）に来るので、1回だけ返す印は鍵なしでよい
            final class Once: @unchecked Sendable { var done = false }
            let once = Once()
            listener.stateUpdateHandler = { state in
                guard !once.done else { return }
                switch state {
                case .ready:
                    once.done = true
                    continuation.resume(returning: listener.port?.rawValue)
                case .failed, .cancelled:
                    once.done = true
                    continuation.resume(returning: nil)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        guard let ready else {
            listener.cancel()
            AppPaths.log("media server failed to start")
            return nil
        }
        // 裏で止められたら、次の曲で開き直す
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.reset() }
        }
        lock.withLock {
            self.listener = listener
            port = ready
        }
        return ready
    }

    private func reset() {
        lock.withLock {
            listener?.cancel()
            listener = nil
            port = nil
        }
    }

    // MARK: 1つのつながり（AVPlayer は同じつながりで続けて求めてくる）

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
                let rest = Data(buffer[end.upperBound...])
                Task { await self.respond(connection, head: head, rest: rest) }
                return
            }
            if complete || error != nil || buffer.count > 64 * 1024 {
                connection.cancel()
                return
            }
            self.receive(connection, buffer: buffer)
        }
    }

    private func respond(_ connection: NWConnection, head: String, rest: Data) async {
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2, parts[0] == "GET" || parts[0] == "HEAD" else {
            await send(connection, status: "405 Method Not Allowed", headers: [:], body: nil)
            connection.cancel()
            return
        }
        let headOnly = parts[0] == "HEAD"
        let path = parts[1].split(separator: "/").map(String.init)
        var range: String?
        for line in lines.dropFirst() {
            let pair = line.split(separator: ":", maxSplits: 1)
            if pair.count == 2, pair[0].trimmingCharacters(in: .whitespaces).lowercased() == "range" {
                range = pair[1].trimmingCharacters(in: .whitespaces)
            }
        }
        guard path.count == 2, let entry = entry(path[0]) else {
            await send(connection, status: "404 Not Found", headers: [:], body: nil)
            return receive(connection, buffer: rest)
        }
        if path[1] == LocalMediaServer.playlistName {
            await send(connection, status: "200 OK", headers: ["Content-Type": "application/vnd.apple.mpegurl"], body: headOnly ? nil : entry.playlist,
                       length: entry.playlist.count)
            return receive(connection, buffer: rest)
        }
        guard path[1] == LocalMediaServer.mediaName else {
            await send(connection, status: "404 Not Found", headers: [:], body: nil)
            return receive(connection, buffer: rest)
        }
        let total = entry.bytes.length
        var start: Int64 = 0
        var end = total - 1
        var partial = false
        if let range, range.hasPrefix("bytes=") {
            let spec = range.dropFirst("bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
            if spec.count == 2, let from = Int64(spec[0]) {
                start = from
                if let to = Int64(spec[1]) { end = min(to, total - 1) }
                partial = true
            }
        }
        guard start <= end, start < total else {
            await send(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(total)"], body: nil)
            return receive(connection, buffer: rest)
        }
        var headers = ["Content-Type": entry.contentType, "Accept-Ranges": "bytes"]
        if partial { headers["Content-Range"] = "bytes \(start)-\(end)/\(total)" }
        let length = end - start + 1
        guard await send(connection, status: partial ? "206 Partial Content" : "200 OK", headers: headers, body: nil, length: Int(length), finish: false) else { return }
        if headOnly { return receive(connection, buffer: rest) }
        // 区切りごとに返す（全部そろうのを待たない）
        var offset = start
        while offset <= end {
            let chunkEnd = min(end, offset + Int64(RangeStream.chunkSize) - 1)
            guard let chunk = try? await entry.bytes.readRange(start: offset, end: chunkEnd), !chunk.isEmpty else {
                connection.cancel()
                return
            }
            guard await sendRaw(connection, chunk) else { return }
            offset += Int64(chunk.count)
        }
        receive(connection, buffer: rest)
    }

    @discardableResult
    private func send(_ connection: NWConnection, status: String, headers: [String: String], body: Data?, length: Int? = nil, finish: Bool = true) async -> Bool {
        var text = "HTTP/1.1 \(status)\r\nConnection: keep-alive\r\nContent-Length: \(length ?? body?.count ?? 0)\r\n"
        for (name, value) in headers { text += "\(name): \(value)\r\n" }
        text += "\r\n"
        var data = Data(text.utf8)
        if let body { data.append(body) }
        return await sendRaw(connection, data)
    }

    private func sendRaw(_ connection: NWConnection, _ data: Data) async -> Bool {
        await withCheckedContinuation { continuation in
            connection.send(content: data, completion: .contentProcessed { error in
                if error != nil { connection.cancel() }
                continuation.resume(returning: error == nil)
            })
        }
    }
}
