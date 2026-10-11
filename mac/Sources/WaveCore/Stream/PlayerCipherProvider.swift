import Foundation

/// 今の YouTube のプレイヤー（ハッシュ）と、それに合う faraday の表の行、解読器を用意する。
/// どれも公開データで、cookie は送らない。1時間ごとに取り直す。
public final class PlayerCipherProvider: @unchecked Sendable {
    public static let faradayConfigUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"
    private static let ttlMs: Int64 = 60 * 60 * 1000
    private static let maxPlayerBytes = 8 * 1024 * 1024
    private static let browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /// 解読の準備ができた状態（プレイヤーのハッシュ・STS・解読器）。
    public struct Ready: Sendable {
        public var playerHash: String
        public var signatureTimestamp: Int
        public var solver: CipherSolver
        public var fetchedAtMs: Int64
    }

    private let session: URLSession
    private let nowMs: @Sendable () -> Int64
    private let gate = AsyncGate()
    private let lock = NSLock()
    private var readyValue: Ready?

    private var ready: Ready? {
        get { lock.withLock { readyValue } }
        set { lock.withLock { readyValue = newValue } }
    }

    /// session は cookie を持たないもの（InnerTubeClient.session など）。
    public init(session: URLSession, nowMs: @escaping @Sendable () -> Int64) {
        self.session = session
        self.nowMs = nowMs
    }

    /// 解読器を返す。取れなければ nil。
    public func get() async throws -> Ready? {
        try await gate.withGate {
            do {
                if let r = ready, nowMs() - r.fetchedAtMs < PlayerCipherProvider.ttlMs { return r }
                guard let hash = try await currentPlayerHash() else { return nil }
                if var same = ready, same.playerHash == hash {
                    same.fetchedAtMs = nowMs()
                    ready = same
                    return same
                }
                guard let config = try await faradayConfig(hash) else { return nil }
                guard let code = try await getText("https://www.youtube.com/s/player/\(hash)/player_ias.vflset/en_GB/base.js", maxBytes: PlayerCipherProvider.maxPlayerBytes) else { return nil }
                // プレイヤー JS の読み込みは重いので、協調の糸を塞がないよう別の作業で動かす
                let solver = try await Task.detached(priority: .userInitiated) {
                    try CipherSolver.create(playerCode: code, config: config)
                }.value
                try Task.checkCancellation()
                let made = Ready(playerHash: hash, signatureTimestamp: config.signatureTimestamp, solver: solver, fetchedAtMs: nowMs())
                ready = made
                return made
            } catch {
                try rethrowIfCancelled(error)
                return nil
            }
        }
    }

    /// 解いた URL が拒否された。次は取り直す。
    public func invalidate() { ready = nil }

    private func currentPlayerHash() async throws -> String? {
        guard let api = try await getText("https://www.youtube.com/iframe_api", maxBytes: 256 * 1024) else { return nil }
        let match = api.replacingOccurrences(of: "\\/", with: "/").firstMatch(of: #/\/s\/player\/([a-f0-9]{8})\//#)
        return match.map { String($0.output.1) }
    }

    private func faradayConfig(_ hash: String) async throws -> PlayerCipherConfig? {
        guard let text = try await getText(PlayerCipherProvider.faradayConfigUrl, maxBytes: 4 * 1024 * 1024) else { return nil }
        return PlayerCipherProvider.parseFaraday(text, hash: hash)
    }

    /// faraday の表から、ハッシュ（または別名）に合う行を読む。形が合わない行は使わない。
    public static func parseFaraday(_ text: String, hash: String) -> PlayerCipherConfig? {
        guard let root = try? JSON.parse(text), root.obj != nil, root.path("schemaVersion").long == 1 else { return nil }
        guard let players = root.path("players").obj else { return nil }
        var entry = present(players[hash])
        if entry == nil {
            entry = players.pairs.map(\.1).first { v in
                guard let aliases = v.path("aliases")?.arr else { return false }
                return aliases.contains { $0.str == hash }
            }
        }
        guard let sig = entry.path("sig").str, let nClass = entry.path("nClass").str, let sts = entry.path("sts").long, sts > 0 else { return nil }
        guard sig.wholeMatch(of: #/[A-Za-z0-9$_]{1,8}\(\d+,\d+,INPUT\)/#) != nil, nClass.wholeMatch(of: #/[A-Za-z0-9$_]{1,8}/#) != nil else { return nil }
        return PlayerCipherConfig(sigExpression: sig, nClass: nClass, signatureTimestamp: Int(sts))
    }

    private func getText(_ url: String, maxBytes: Int) async throws -> String? {
        var request = URLRequest(url: URL(string: url)!)
        request.setValue(PlayerCipherProvider.browserUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com/", forHTTPHeaderField: "Referer")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { return nil }
        if http.expectedContentLength > Int64(maxBytes) { return nil }
        let text = String(decoding: data, as: UTF8.self)
        return text.utf16.count > maxBytes ? nil : text
    }
}

/// JSON の null を「無い」とみなす（C# の JsonNode では null の値は null になる）。
func present(_ node: JSON?) -> JSON? {
    guard let node, !node.isNull else { return nil }
    return node
}
