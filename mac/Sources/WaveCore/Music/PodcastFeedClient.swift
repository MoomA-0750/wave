import Foundation

public enum FeedFailure: Error, Hashable, Sendable {
    /// https でない・形のおかしい URL。
    case notAllowed
    case network
    case http(Int)
    case tooLarge
    /// RSS として読めなかった。
    case notAFeed
}

public enum FeedResult: Sendable {
    case ok(FeedShow)
    case failed(FeedFailure)
}

/// RSS のポッドキャスト（ADR-0012。Android の PodcastFeedClient と同じ）。番組を Apple の目録で探し、フィードを読む。
/// 通信は認証情報を持たない（ADR-0003）FeedHttp で行い、リダイレクトは https の間だけ追う。
public final class PodcastFeedClient: @unchecked Sendable {
    public static let directoryUrl = "https://itunes.apple.com/search"
    /// 長く続く番組のフィードは数 MB になる。これを超えたら読まない。
    public static let maxFeedBytes = 40 * 1024 * 1024
    /// 目録の応答の上限。
    static let maxDirectoryBytes = 4 * 1024 * 1024

    /// 目録の国（`JP` など）。
    private let country: String
    private let userAgent: String
    private let fetch: @Sendable (URL, String, Int) async throws -> (status: Int, body: Data?)
    /// 取る・読むのにかかった時間と大きさを知らせる（記録用。URL・中身は渡さない）。
    nonisolated(unsafe) public static var diagnostics: ((String) -> Void)?

    /// fetch は状態コードと本文（上限を超えたら nil）を返す。無ければ FeedHttp で取る。
    public init(country: String, userAgent: String, fetch: (@Sendable (URL, String, Int) async throws -> (status: Int, body: Data?))? = nil) {
        self.country = country
        self.userAgent = userAgent
        if let fetch {
            self.fetch = fetch
        } else {
            let http = FeedHttp()
            self.fetch = { url, agent, max in try await http.fetch(url, userAgent: agent, maxBytes: max) }
        }
    }

    /// 番組を探す。読めなければ nil。
    public func search(_ term: String) async -> [PodcastSummary]? {
        let trimmed = term.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return [] }
        // URL を打ち込まれたら、そのフィードを読んで1件にする
        if trimmed.contains("://") || trimmed.hasPrefix("feed:") {
            guard let url = Feed.normalizeFeedUrl(trimmed) else { return [] }
            if case .ok(let show) = await show(url) { return [show.subscription.summary] }
            return []
        }
        guard var components = URLComponents(string: PodcastFeedClient.directoryUrl) else { return nil }
        components.queryItems = [
            URLQueryItem(name: "term", value: trimmed),
            URLQueryItem(name: "media", value: "podcast"),
            URLQueryItem(name: "entity", value: "podcast"),
            URLQueryItem(name: "limit", value: "25"),
            URLQueryItem(name: "country", value: country),
        ]
        guard let url = components.url else { return nil }
        guard let (status, body) = try? await fetch(url, userAgent, PodcastFeedClient.maxDirectoryBytes), (200...299).contains(status), let body else { return nil }
        return PodcastFeedClient.directoryResults(body)
    }

    /// フィードを読む。読むのは呼んだ側のスレッドではなく、別の流れで行う（数 MB のフィードで画面を止めない）。
    public func show(_ feedUrl: String) async -> FeedResult {
        guard Feed.isAllowedFeedUrl(feedUrl), let url = URL(string: feedUrl) else { return .failed(.notAllowed) }
        let started = Date()
        let response: (status: Int, body: Data?)
        do {
            response = try await fetch(url, userAgent, PodcastFeedClient.maxFeedBytes)
        } catch {
            return .failed(.network)
        }
        guard (200...299).contains(response.status) else { return .failed(.http(response.status)) }
        guard let body = response.body else { return .failed(.tooLarge) }
        let fetched = Date()
        let parsed = await Task.detached(priority: .userInitiated) { FeedParser.parse(feedUrl: feedUrl, data: body) }.value
        PodcastFeedClient.diagnostics?("feed fetch ms=\(Int(fetched.timeIntervalSince(started) * 1000)) bytes=\(body.count) " +
            "parse ms=\(Int(Date().timeIntervalSince(fetched) * 1000)) items=\(parsed?.itemCount ?? -1)")
        guard let show = parsed else { return .failed(.notAFeed) }
        return .ok(show)
    }

    /// 目録の応答（`results[]`）。フィードの URL の無い番組は落とす。
    public static func directoryResults(_ body: Data) -> [PodcastSummary] {
        guard let root = try? JSONSerialization.jsonObject(with: body) as? [String: Any], let results = root["results"] as? [Any] else { return [] }
        var seen = Set<String>()
        return results.compactMap { element -> PodcastSummary? in
            guard let item = element as? [String: Any] else { return nil }
            func str(_ key: String) -> String? {
                guard let v = item[key] as? String, !v.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
                return v
            }
            guard let feed = str("feedUrl").flatMap(Feed.normalizeFeedUrl), let title = str("collectionName") ?? str("trackName") else { return nil }
            let summary = PodcastSummary(browseId: Feed.showBrowseId(feed), title: title, author: str("artistName"),
                                         thumbnailUrl: (str("artworkUrl600") ?? str("artworkUrl100")).flatMap { Feed.isAllowedFeedUrl($0) ? $0 : nil })
            return seen.insert(summary.browseId).inserted ? summary : nil
        }
    }

    public static func directoryResults(_ body: String) -> [PodcastSummary] { directoryResults(Data(body.utf8)) }
}

/// RSS のフィード・目録の取得（ADR-0012）。cookie・キャッシュ・認証情報を持たない ephemeral の URLSession で、
/// リダイレクトは https の間だけ追う（計測用の転送 podtrac などを通るため）。本文が上限を超えたら途中でやめる。
public final class FeedHttp: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private final class Pending {
        let maxBytes: Int
        let continuation: CheckedContinuation<(status: Int, body: Data?), Error>
        var status = 0
        var data = Data()
        var tooLarge = false
        var rejected = false

        init(maxBytes: Int, continuation: CheckedContinuation<(status: Int, body: Data?), Error>) {
            self.maxBytes = maxBytes
            self.continuation = continuation
        }
    }

    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]
    private var session: URLSession!

    override public init() {
        super.init()
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 180
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
    }

    /// 状態コードと本文。本文が maxBytes を超えたら nil。2xx でなければ本文は読まない。
    public func fetch(_ url: URL, userAgent: String, maxBytes: Int) async throws -> (status: Int, body: Data?) {
        guard url.scheme?.lowercased() == "https" else { throw URLError(.unsupportedURL) }
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let task = session.dataTask(with: request)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.withLock { pending[task.taskIdentifier] = Pending(maxBytes: maxBytes, continuation: continuation) }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    private func entry(_ task: URLSessionTask) -> Pending? { lock.withLock { pending[task.taskIdentifier] } }

    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void)
    {
        // https から http へは追わない（追わなければ、この転送の応答がそのまま返る）
        completionHandler(request.url?.scheme?.lowercased() == "https" ? request : nil)
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                           completionHandler: @escaping (URLSession.ResponseDisposition) -> Void)
    {
        guard let state = entry(dataTask) else {
            completionHandler(.cancel)
            return
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        state.status = status
        if !(200...299).contains(status) {
            state.rejected = true
            completionHandler(.cancel)
        } else if response.expectedContentLength > Int64(state.maxBytes) {
            state.tooLarge = true
            completionHandler(.cancel)
        } else {
            if response.expectedContentLength > 0 { state.data.reserveCapacity(Int(response.expectedContentLength)) }
            completionHandler(.allow)
        }
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let state = entry(dataTask), !state.tooLarge else { return }
        state.data.append(data)
        if state.data.count > state.maxBytes {
            state.tooLarge = true
            state.data = Data()
            dataTask.cancel()
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let state = lock.withLock({ pending.removeValue(forKey: task.taskIdentifier) }) else { return }
        if state.tooLarge || state.rejected {
            state.continuation.resume(returning: (state.status, nil))
        } else if let error {
            state.continuation.resume(throwing: error)
        } else {
            state.continuation.resume(returning: (state.status, state.data))
        }
    }
}
