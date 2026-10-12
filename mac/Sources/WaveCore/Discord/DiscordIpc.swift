import Foundation

#if canImport(Darwin)
    import Darwin
#endif

/// Discord に出す「再生中の曲」（Windows の PresenceTrack と同じ）。largeText は画像に重ねて出す文字（アルバム名・ほかの端末で鳴っていればその名前）。
/// playing が false のときは出さない（Spotify と同じく、一時停止中は消す）。
public struct PresenceTrack: Equatable, Sendable {
    public var videoId: String
    public var title: String
    public var artists: String
    public var largeText: String?
    public var thumbnailUrl: String?
    public var positionSeconds: Double
    public var durationSeconds: Double
    public var playing: Bool
    public var buttonLabel: String

    public init(videoId: String, title: String, artists: String, largeText: String?, thumbnailUrl: String?,
                positionSeconds: Double, durationSeconds: Double, playing: Bool, buttonLabel: String = "YouTube Music で開く")
    {
        self.videoId = videoId
        self.title = title
        self.artists = artists
        self.largeText = largeText
        self.thumbnailUrl = thumbnailUrl
        self.positionSeconds = positionSeconds
        self.durationSeconds = durationSeconds
        self.playing = playing
        self.buttonLabel = buttonLabel
    }
}

/// Discord のデスクトップ版のローカル IPC（Rich Presence）の枠と中身（Windows の DiscordIpc と同じ）。
/// 枠は [op: int32 LE][長さ: int32 LE][JSON（UTF-8）]。最初に op 0 で {v:1, client_id} を送り、READY が返ったら op 1 で SET_ACTIVITY を送る。
public enum DiscordIpc {
    public static let opHandshake: Int32 = 0
    public static let opFrame: Int32 = 1
    public static let opClose: Int32 = 2
    public static let opPing: Int32 = 3
    public static let opPong: Int32 = 4
    /// 1つの枠の中身の上限（壊れた長さで大きな配列を取らないため）。
    public static let maxPayload = 64 * 1024

    public struct BadFrame: Error {
        public let length: Int32
    }

    public static func encode(op: Int32, json: String) -> Data {
        let body = Data(json.utf8)
        var data = Data(capacity: 8 + body.count)
        withUnsafeBytes(of: op.littleEndian) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: Int32(body.count).littleEndian) { data.append(contentsOf: $0) }
        data.append(body)
        return data
    }

    /// 先頭の1枠を読む。まだ足りなければ nil。
    public static func decode(_ data: Data) throws -> (op: Int32, json: String, consumed: Int)? {
        let bytes = [UInt8](data)
        guard bytes.count >= 8 else { return nil }
        let op = int32(bytes, 0)
        let length = int32(bytes, 4)
        guard length >= 0, Int(length) <= maxPayload else { throw BadFrame(length: length) }
        guard bytes.count >= 8 + Int(length) else { return nil }
        let json = String(decoding: bytes[8..<(8 + Int(length))], as: UTF8.self)
        return (op, json, 8 + Int(length))
    }

    static func int32(_ b: [UInt8], _ at: Int) -> Int32 {
        Int32(bitPattern: UInt32(b[at]) | UInt32(b[at + 1]) << 8 | UInt32(b[at + 2]) << 16 | UInt32(b[at + 3]) << 24)
    }

    public static func handshake(clientId: String) -> String {
        json(["v": 1, "client_id": clientId])
    }

    /// SET_ACTIVITY の命令。track が nil（または一時停止中）なら、出している状態を消す。
    public static func setActivity(pid: Int32, track: PresenceTrack?, nowUnixMs: Int64, nonce: String) -> String {
        var args: [String: Any] = ["pid": Int(pid)]
        if let track, track.playing { args["activity"] = activity(track, nowUnixMs: nowUnixMs) }
        return json(["cmd": "SET_ACTIVITY", "args": args, "nonce": nonce])
    }

    /// 種類は 2（Listening）。再生中は始まりと終わりの時刻を付け、Discord が残り時間を数える。
    public static func activity(_ track: PresenceTrack, nowUnixMs: Int64) -> [String: Any] {
        var activity: [String: Any] = [
            "type": 2,
            "details": text(track.title),
            "state": text(track.artists.trimmingCharacters(in: .whitespaces).isEmpty ? "wave" : track.artists),
        ]
        let start = startMs(track, nowUnixMs: nowUnixMs)
        var timestamps: [String: Any] = ["start": start]
        if track.durationSeconds > 0 { timestamps["end"] = start + Int64((track.durationSeconds * 1000).rounded()) }
        activity["timestamps"] = timestamps
        var assets: [String: Any] = [:]
        if let image = imageUrl(track.thumbnailUrl) { assets["large_image"] = image }
        if let large = track.largeText, !large.trimmingCharacters(in: .whitespaces).isEmpty { assets["large_text"] = text(large) }
        if !assets.isEmpty { activity["assets"] = assets }
        if isVideoId(track.videoId) {
            activity["buttons"] = [["label": clip(track.buttonLabel, 32), "url": "https://music.youtube.com/watch?v=" + track.videoId]]
        }
        return activity
    }

    /// 曲の始まりの時刻（今 − 位置。秒に丸めて、少しの揺れで送り直さないようにする）。
    public static func startMs(_ track: PresenceTrack, nowUnixMs: Int64) -> Int64 {
        let position = track.positionSeconds.isFinite ? max(0, track.positionSeconds) : 0
        return (nowUnixMs - Int64((position * 1000).rounded())) / 1000 * 1000
    }

    /// 大きな画像に使う URL。Discord は https の外の画像をそのまま使える（256 文字まで）。googleusercontent の小さな画像は 512 に広げる。
    public static func imageUrl(_ thumbnail: String?) -> String? {
        guard var url = thumbnail, !url.isEmpty else { return nil }
        if url.hasPrefix("//") { url = "https:" + url }
        guard url.hasPrefix("https://") else { return nil }
        if let regex = try? NSRegularExpression(pattern: #"=w\d+-h\d+((?:-[a-z0-9]+)*)$"#) {
            url = regex.stringByReplacingMatches(in: url, range: NSRange(url.startIndex..., in: url), withTemplate: "=w512-h512$1")
        }
        return url.utf16.count <= 256 ? url : nil
    }

    private static func isVideoId(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64 && id.unicodeScalars.allSatisfy { $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_") }
    }

    /// Discord の文字の欄は 2〜128 文字（1文字なら空白を足す）。
    static func text(_ value: String) -> String {
        let t = clip(value.trimmingCharacters(in: .whitespaces), 128)
        switch t.count {
        case 0: return "wave"
        case 1: return t + " "
        default: return t
        }
    }

    static func clip(_ value: String, _ max: Int) -> String {
        value.count <= max ? value : String(value.prefix(max - 1)) + "…"
    }

    static func json(_ object: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func object(_ json: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
    }

    public static func isReady(_ json: String) -> Bool { object(json)?["evt"] as? String == "READY" }

    /// 返事が ERROR なら、その文。
    public static func errorOf(_ json: String) -> String? {
        guard let o = object(json), o["evt"] as? String == "ERROR" else { return nil }
        return ((o["data"] as? [String: Any])?["message"] as? String) ?? "error"
    }

    // MARK: - unix ソケット

    /// ソケットを探す場所（$XDG_RUNTIME_DIR・$TMPDIR・この利用者の一時フォルダ・/tmp）。
    public static func socketDirs() -> [String] {
        var dirs: [String] = []
        for key in ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"] {
            if let v = ProcessInfo.processInfo.environment[key], !v.isEmpty { dirs.append(v) }
        }
        #if canImport(Darwin)
            // サンドボックスの中では TMPDIR が入れ物の中を指すので、利用者の一時フォルダも見る
            var buffer = [CChar](repeating: 0, count: Int(PATH_MAX))
            if confstr(_CS_DARWIN_USER_TEMP_DIR, &buffer, buffer.count) > 0 { dirs.append(String(cString: buffer)) }
        #endif
        dirs.append("/tmp")
        var seen = Set<String>()
        return dirs.filter { seen.insert(($0 as NSString).standardizingPath).inserted }
    }

    /// Discord につなぐ（discord-ipc-0〜9 を順に試す）。つながればファイル記述子、無ければ nil。
    public static func connectSocket(baseName: String = "discord-ipc-", dirs: [String] = socketDirs()) -> Int32? {
        for i in 0..<10 {
            for dir in dirs {
                let path = (dir as NSString).appendingPathComponent(baseName + String(i))
                guard FileManager.default.fileExists(atPath: path) else { continue }
                if let fd = connectUnix(path) { return fd }
            }
        }
        return nil
    }

    static func connectUnix(_ path: String) -> Int32? {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var on: Int32 = 1
        #if canImport(Darwin)
            // 相手が閉じた後に書いてもプロセスを落とさない
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        #endif
        _ = on
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else {
            close(fd)
            return nil
        }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            raw.copyBytes(from: bytes)
            raw[bytes.count] = 0
        }
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        if ok != 0 {
            close(fd)
            return nil
        }
        return fd
    }

    /// すべて書く。書けなければ false。
    public static func writeFrame(_ fd: Int32, op: Int32, json: String) -> Bool {
        let data = encode(op: op, json: json)
        return data.withUnsafeBytes { raw -> Bool in
            var sent = 0
            while sent < raw.count {
                let n = write(fd, raw.baseAddress! + sent, raw.count - sent)
                if n < 0, errno == EINTR { continue }
                if n <= 0 { return false }
                sent += n
            }
            return true
        }
    }

    /// 1枠を読む（読めるまで待つ）。閉じられた・壊れていれば nil。
    public static func readFrame(_ fd: Int32) -> (op: Int32, json: String)? {
        guard let header = readExactly(fd, 8) else { return nil }
        let op = int32(header, 0)
        let length = int32(header, 4)
        guard length >= 0, Int(length) <= maxPayload else { return nil }
        guard let body = readExactly(fd, Int(length)) else { return nil }
        return (op, String(decoding: body, as: UTF8.self))
    }

    private static func readExactly(_ fd: Int32, _ count: Int) -> [UInt8]? {
        var buffer = [UInt8](repeating: 0, count: count)
        var got = 0
        while got < count {
            let n = buffer.withUnsafeMutableBytes { read(fd, $0.baseAddress! + got, count - got) }
            if n < 0, errno == EINTR { continue }
            if n <= 0 { return nil }
            got += n
        }
        return buffer
    }
}

/// いつ SET_ACTIVITY を送るか（Discord は 20 秒に 5 回までなので、送りすぎない。Windows と同じ）。
/// 曲・再生中か・画像の文字が変わったらすぐ（前に送ってから minGapMs は空ける）、シークで始まりの時刻がずれたら seekGapMs おきに。
public struct PresencePlanner {
    public static let minGapMs: Int64 = 2_000
    public static let seekGapMs: Int64 = 15_000
    private static let seekToleranceMs: Int64 = 3_000

    private var sentAny = false
    private var sentKey: String?
    private var sentStart: Int64 = 0
    private var sentAtMs: Int64 = Int64.min / 2

    public init() {}

    /// 送ったことを忘れる（つなぎ直したとき。次は必ず送る）。
    public mutating func reset() {
        sentAny = false
        sentKey = nil
    }

    public static func key(_ track: PresenceTrack?) -> String {
        guard let t = track, t.playing else { return "" }
        return [t.videoId, t.title, t.artists, t.largeText ?? "", t.thumbnailUrl ?? "", String(Int64(t.durationSeconds.isFinite ? t.durationSeconds : 0))].joined(separator: "\n")
    }

    public func shouldSend(_ track: PresenceTrack?, nowMs: Int64, nowUnixMs: Int64) -> Bool {
        let key = Self.key(track)
        if !sentAny || key != sentKey { return nowMs - sentAtMs >= Self.minGapMs }
        guard let track, track.playing else { return false }
        let start = DiscordIpc.startMs(track, nowUnixMs: nowUnixMs)
        return abs(start - sentStart) > Self.seekToleranceMs && nowMs - sentAtMs >= Self.seekGapMs
    }

    public mutating func sent(_ track: PresenceTrack?, nowMs: Int64, nowUnixMs: Int64) {
        sentAny = true
        sentKey = Self.key(track)
        sentStart = track.map { $0.playing ? DiscordIpc.startMs($0, nowUnixMs: nowUnixMs) : 0 } ?? 0
        sentAtMs = nowMs
    }
}

/// Discord の Rich Presence を出し続ける（Windows の DiscordPresence と同じ）。update で今の曲を渡すと、裏のスレッドがつなぎ・送り・つなぎ直しをする。
/// Discord が後から起動しても、15 秒おきにつなぎ直しを試す。clientId が nil なら消してから切る。
public final class DiscordPresence: @unchecked Sendable {
    public static let retryMs: Int64 = 15_000

    public enum Status: String, Sendable { case off, notFound, refused, connected }

    private let connector: () -> Int32?
    private let log: ((String) -> Void)?
    private let pid: Int32
    private let lock = NSLock()
    private let wake = DispatchSemaphore(value: 0)
    private var wantedClientId: String?
    private var wantedTrack: PresenceTrack?
    private var stopped = false
    // ここから下は裏のスレッドだけが触る（closedGeneration と status は lock の中）
    private var fd: Int32 = -1
    private var generation = 0
    private var closedGeneration = -1
    private var connectedClientId: String?
    private var planner = PresencePlanner()
    private var nextAttemptMs = Int64.min / 2
    private var nonce = 0
    private var statusValue = Status.off
    private let started = Date()

    public var status: Status {
        lock.lock()
        defer { lock.unlock() }
        return statusValue
    }

    public init(connect: (() -> Int32?)? = nil, log: ((String) -> Void)? = nil, pid: Int32 = ProcessInfo.processInfo.processIdentifier) {
        connector = connect ?? { DiscordIpc.connectSocket() }
        self.log = log
        self.pid = pid
        let thread = Thread { [weak self] in self?.loop() }
        thread.name = "wave.discord"
        thread.start()
    }

    /// 出したい曲（clientId が nil なら Discord から離れる）。どのスレッドから呼んでもよい。
    public func update(clientId: String?, track: PresenceTrack?) {
        let id = clientId?.trimmingCharacters(in: .whitespaces)
        lock.lock()
        let changed = (id?.isEmpty == false ? id : nil) != wantedClientId || PresencePlanner.key(track) != PresencePlanner.key(wantedTrack)
        wantedClientId = id?.isEmpty == false ? id : nil
        wantedTrack = track
        lock.unlock()
        if changed { wake.signal() }
    }

    public func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        wake.signal()
    }

    private var nowMs: Int64 { Int64(Date().timeIntervalSince(started) * 1000) }
    private var nowUnixMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func setStatus(_ s: Status) {
        lock.lock()
        statusValue = s
        lock.unlock()
    }

    private func loop() {
        while true {
            lock.lock()
            let done = stopped
            lock.unlock()
            if done { break }
            step()
            _ = wake.wait(timeout: .now() + 1)
        }
        disconnect()
    }

    private func step() {
        lock.lock()
        let clientId = wantedClientId
        let track = wantedTrack
        let peerClosed = fd >= 0 && closedGeneration == generation
        lock.unlock()
        if peerClosed {
            // Discord が閉じた（終了した・ID が違った）: 少し待ってからつなぎ直す
            disconnect()
            nextAttemptMs = nowMs + Self.retryMs
        }
        if fd >= 0, clientId == nil || clientId != connectedClientId {
            // 切る前に消す
            _ = DiscordIpc.writeFrame(fd, op: DiscordIpc.opFrame, json: DiscordIpc.setActivity(pid: pid, track: nil, nowUnixMs: nowUnixMs, nonce: nextNonce()))
            disconnect()
            nextAttemptMs = Int64.min / 2
        }
        guard let clientId else {
            setStatus(.off)
            return
        }
        if fd < 0 {
            // 何も出していない（一時停止中・曲が無い）ときは、つなぎに行かない
            guard let track, track.playing, nowMs >= nextAttemptMs else { return }
            nextAttemptMs = nowMs + Self.retryMs
            guard open(clientId) else { return }
        }
        let now = nowMs, unix = nowUnixMs
        guard planner.shouldSend(track, nowMs: now, nowUnixMs: unix) else { return }
        if DiscordIpc.writeFrame(fd, op: DiscordIpc.opFrame, json: DiscordIpc.setActivity(pid: pid, track: track, nowUnixMs: unix, nonce: nextNonce())) {
            planner.sent(track, nowMs: now, nowUnixMs: unix)
        } else {
            log?("discord: write failed")
            disconnect()
        }
    }

    private func open(_ clientId: String) -> Bool {
        guard let socket = connector() else {
            setStatus(.notFound)
            return false
        }
        // 手合わせの返事は 5 秒まで待つ
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        guard DiscordIpc.writeFrame(socket, op: DiscordIpc.opHandshake, json: DiscordIpc.handshake(clientId: clientId)) else {
            close(socket)
            setStatus(.notFound)
            return false
        }
        guard let reply = DiscordIpc.readFrame(socket), reply.op == DiscordIpc.opFrame, DiscordIpc.isReady(reply.json) else {
            log?("discord: handshake refused")
            close(socket)
            setStatus(.refused)
            return false
        }
        var forever = timeval(tv_sec: 0, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &forever, socklen_t(MemoryLayout<timeval>.size))
        lock.lock()
        generation += 1
        let gen = generation
        fd = socket
        lock.unlock()
        connectedClientId = clientId
        planner.reset()
        setStatus(.connected)
        log?("discord: connected")
        let reader = Thread { [weak self] in self?.read(socket, gen) }
        reader.name = "wave.discord.read"
        reader.start()
        return true
    }

    /// 返事を読み捨てる（読まないと詰まる）。PING には PONG を返し、閉じられたらつなぎ直す。
    private func read(_ socket: Int32, _ gen: Int) {
        while let frame = DiscordIpc.readFrame(socket) {
            if frame.op == DiscordIpc.opClose { break }
            if frame.op == DiscordIpc.opPing {
                _ = DiscordIpc.writeFrame(socket, op: DiscordIpc.opPong, json: frame.json)
            } else if let error = DiscordIpc.errorOf(frame.json) {
                log?("discord: " + error)
            }
        }
        lock.lock()
        let current = generation == gen && fd == socket
        if current { closedGeneration = gen }
        lock.unlock()
        if current {
            log?("discord: disconnected")
            wake.signal()
        }
    }

    private func disconnect() {
        lock.lock()
        let old = fd
        fd = -1
        generation += 1
        if statusValue == .connected { statusValue = .notFound }
        lock.unlock()
        if old >= 0 {
            shutdown(old, Int32(SHUT_RDWR))
            close(old)
        }
        connectedClientId = nil
        planner.reset()
    }

    private func nextNonce() -> String {
        nonce += 1
        return String(nonce)
    }
}
