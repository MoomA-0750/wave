import Foundation

public enum RelayStatus: Sendable {
    case disconnected
    case connecting
    case connected

    /// 名前を解決できない（Tailscale につながっていない）。
    case tailnetUnreachable

    /// 中継に届くが、この端末は家に入っていない（認証で断られた・外された）。
    case notMember

    case failed
}

/// 中継 v2 への接続（Android の RelayClientV2・Windows の RelayConnection と同じ規則）。使う側が参照を数え、0 になったら閉じる。切れたら間を空けてつなぎ直す。
/// 受けたメッセージは JSON の文字列のまま deliver に渡す（呼ぶ側が1本の流れへ移す）。ログは状態と種類だけ（鍵・トークンの値は出さない）。
/// Windows では Wave.App の RelayConnection（ClientWebSocket）。Mac では URLSessionWebSocketTask で同じ振る舞いにする。
public final class RelayConnection: @unchecked Sendable {
    private static let reconnectDelayMs: Int64 = 5_000
    private static let maxReconnectDelayMs: Int64 = 60_000
    private static let pairTimeoutSeconds: Double = 120
    private static let keepAliveSeconds: Double = 30

    private let baseUrl: String
    private let relayHost: String
    private let identityPub: Data
    private let sign: (String, Data) throws -> Data
    private let deliver: (String) -> Void
    private let onConnectionLost: () -> Void
    private let log: (String) -> Void
    private let session: URLSession
    /// C# の lock と同じく、同じスレッドから入り直せる鍵（呼び出し先がまた send を呼んでも止まらない）。
    private let gate = NSRecursiveLock()

    /// 今のソケットと、その送り口（1本の送り口で、積んだ順に送る）。
    private final class Socket {
        let task: URLSessionWebSocketTask
        let outbox: AsyncStream<String>.Continuation

        init(task: URLSessionWebSocketTask, outbox: AsyncStream<String>.Continuation) {
            self.task = task
            self.outbox = outbox
        }
    }

    private var socket: Socket?
    private var users = 0
    private var failedAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var statusValue: RelayStatus = .disconnected

    /// 状態が変わった（Windows の StatusChanged イベント）。呼ぶ側のスレッドとは別のところから呼ぶ。
    public var onStatusChanged: ((RelayStatus) -> Void)? {
        get { gate.withLock { statusHandler } }
        set { gate.withLock { statusHandler = newValue } }
    }

    private var statusHandler: ((RelayStatus) -> Void)?

    public var status: RelayStatus { gate.withLock { statusValue } }

    /// - Parameters:
    ///   - baseUrl: 例: wss://<中継のホスト>:<ポート>
    ///   - relayHost: 署名の対象に入れるホスト名とポート（中継の設定と同じ値）
    ///   - sign: 身元鍵での、用途ラベル付きの署名（DER）
    ///   - log: 状態と種類だけのログ（値は渡さない）
    public init(
        baseUrl: String, relayHost: String, identityPub: Data, sign: @escaping (String, Data) throws -> Data, deliver: @escaping (String) -> Void,
        onConnectionLost: @escaping () -> Void, log: @escaping (String) -> Void = { _ in }, session: URLSession = .shared
    ) {
        var trimmed = baseUrl
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        self.baseUrl = trimmed
        self.relayHost = relayHost
        self.identityPub = identityPub
        self.sign = sign
        self.deliver = deliver
        self.onConnectionLost = onConnectionLost
        self.log = log
        self.session = session
    }

    public func acquire() {
        gate.withLock {
            users += 1
            if users == 1 || (socket == nil && statusValue != .connecting) { openLocked() }
        }
    }

    public func release() {
        gate.withLock {
            users = max(0, users - 1)
            if users == 0 { closeLocked() }
        }
    }

    /// 断られた（家に入っていなかった）後に、つなぎ直す（加入の後）。
    public func reconnect() {
        gate.withLock {
            if users > 0 && socket == nil { openLocked() }
        }
    }

    /// つないでいてもつなぎ直す（家の記録を消した後に、新しい家の welcome を受け取る）。
    public func restart() {
        gate.withLock {
            if users == 0 { return }
            closeLocked()
            openLocked()
        }
    }

    /// つながっていれば送る（呼んだ順に送る）。送れなければ false。
    @discardableResult
    public func send(_ json: String) -> Bool {
        gate.withLock {
            guard statusValue == .connected, let socket else { return false }
            if case .terminated = socket.outbox.yield(json) { return false }
            return true
        }
    }

    /// 家に入っていない端末から、加入の第1手を送って返事を待つ（/v2/pair、認証なし）。届かなければ nil。
    public func pair(_ pairHelloInJson: String) async -> String? {
        guard let url = URL(string: baseUrl + "/v2/pair") else { return nil }
        let task = session.webSocketTask(with: url)
        task.maximumMessageSize = 64 * 1024 * 4
        task.resume()
        do {
            let reply = try await RelayConnection.withTimeout(RelayConnection.pairTimeoutSeconds, onTimeout: { task.cancel() }) {
                try await task.send(.string(pairHelloInJson))
                return try await RelayConnection.receiveText(task, maxChars: 64 * 1024)
            }
            task.cancel(with: .normalClosure, reason: nil)
            return reply
        } catch {
            log("relay-v2 pair failed type=\(RelayConnection.errorType(error))")
            task.cancel()
            return nil
        }
    }

    private func setStatusLocked(_ next: RelayStatus) {
        statusValue = next
        if let handler = statusHandler { Task.detached { handler(next) } }
    }

    private func openLocked() {
        reconnectTask?.cancel()
        reconnectTask = nil
        setStatusLocked(.connecting)
        guard let url = URL(string: baseUrl + "/v2/connect") else {
            setStatusLocked(.failed)
            return
        }
        let task = session.webSocketTask(with: url)
        task.maximumMessageSize = RelayV2.maxFrameChars * 4
        let (stream, continuation) = AsyncStream<String>.makeStream()
        let current = Socket(task: task, outbox: continuation)
        socket = current
        Task.detached { [self] in await run(current, stream) }
    }

    private func run(_ current: Socket, _ outbox: AsyncStream<String>) async {
        let task = current.task
        task.resume()
        let sender = Task.detached {
            for await text in outbox {
                do {
                    try await task.send(.string(text))
                } catch {
                    break
                }
            }
        }
        // ClientWebSocket の KeepAliveInterval（30秒）と同じく、間を空けて ping を送る
        let pinger = Task.detached {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(RelayConnection.keepAliveSeconds * 1_000_000_000))
                if Task.isCancelled { break }
                task.sendPing { _ in }
            }
        }
        var next = RelayStatus.disconnected
        do {
            while true {
                guard let text = try await RelayConnection.receiveText(task, maxChars: RelayV2.maxFrameChars) else { break }
                onText(current, text)
            }
        } catch {
            if task.closeCode != .invalid {
                // 中継が閉じた（理由が auth・removed なら家に入っていない）
                let reason = task.closeReason.map { String(decoding: $0, as: UTF8.self) }
                gate.withLock {
                    if socket === current && ((statusValue == .connecting && reason == "auth") || reason == "removed") { next = .notMember }
                }
            } else if RelayConnection.isHostNotFound(error) {
                next = .tailnetUnreachable
            } else if gate.withLock({ socket === current }) {
                log("relay-v2 failure type=\(RelayConnection.errorType(error))")
                next = .failed
            }
        }
        sender.cancel()
        pinger.cancel()
        onDisconnected(current, next)
    }

    private func onText(_ current: Socket, _ text: String) {
        guard let node = try? JSON.parse(text) else { return }
        let op = node["op"].str
        if op == "challenge" {
            guard let nonceText = node["nonce"].str, let nonce = B64.decode(nonceText) else { return }
            // 中継が名乗るホストではなく、設定のホストで署名する
            let signature: Data
            do {
                signature = try sign(RelayV2.authLabel, WaveCrypto.lengthPrefixed(WaveCrypto.utf8(relayHost), nonce))
            } catch {
                log("relay-v2 auth sign failed type=\(RelayConnection.errorType(error))")
                return
            }
            let auth = RelayV2.encode(.auth(.init(publicKey: B64.encode(identityPub), signature: B64.encode(signature))))
            current.outbox.yield(auth)
            return
        }
        if op == "welcome" {
            let accepted: Bool = gate.withLock {
                guard socket === current else { return false }
                failedAttempts = 0
                setStatusLocked(.connected)
                return true
            }
            if !accepted { return }
            log("relay-v2 connected")
        }
        if !gate.withLock({ socket === current }) { return }
        deliver(text)
    }

    private func onDisconnected(_ current: Socket, _ next: RelayStatus) {
        let lost: Bool = gate.withLock {
            guard socket === current else { return false }
            socket = nil
            current.outbox.finish()
            setStatusLocked(next)
            if users > 0 && next != .notMember {
                let shift = min(failedAttempts, 4)
                let waitMs = min(RelayConnection.reconnectDelayMs << Int64(shift), RelayConnection.maxReconnectDelayMs)
                failedAttempts += 1
                reconnectTask = Task.detached { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
                    if Task.isCancelled { return }
                    guard let self else { return }
                    self.gate.withLock {
                        if self.users > 0 && self.socket == nil { self.openLocked() }
                    }
                }
            }
            return true
        }
        if !lost { return }
        current.task.cancel()
        onConnectionLost()
    }

    private func closeLocked() {
        reconnectTask?.cancel()
        reconnectTask = nil
        let closing = socket
        closing?.outbox.finish()
        socket = nil
        setStatusLocked(.disconnected)
        guard let closing else { return }
        // 閉じる挨拶を送ってから切る（URLSession は閉じる手順を自分で時間切れにする）
        closing.task.cancel(with: .normalClosure, reason: nil)
        onConnectionLost()
    }

    /// テキストの1メッセージを受ける。上限を超えたら閉じて nil。閉じられたら投げる（URLSession は閉じたことを例外で知らせる）。
    private static func receiveText(_ task: URLSessionWebSocketTask, maxChars: Int) async throws -> String? {
        let message = try await task.receive()
        let bytes: Data
        switch message {
        case .string(let s): bytes = Data(s.utf8)
        case .data(let d): bytes = d
        @unknown default: return nil
        }
        if bytes.count > maxChars * 4 {
            task.cancel(with: .messageTooBig, reason: nil)
            return nil
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func isHostNotFound(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        return urlError.code == .cannotFindHost || urlError.code == .dnsLookupFailed
    }

    /// 例外の種類だけ（中身は出さない）。
    private static func errorType(_ error: Error) -> String {
        if let urlError = error as? URLError { return "URLError(\(urlError.code.rawValue))" }
        return String(describing: type(of: error))
    }

    private static func withTimeout<T: Sendable>(
        _ seconds: Double, onTimeout: @escaping @Sendable () -> Void, _ body: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await body() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                onTimeout()
                throw URLError(.timedOut)
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }
}
