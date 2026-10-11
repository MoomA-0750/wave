import CryptoKit
import Foundation

public enum FailureKind: Sendable, Equatable {
    /// ログインが要る（ログインしていない、または切れた）。
    case notLoggedIn
    case network
    case http
    case parse
}

/// InnerTube の呼び出しの結果。
public struct ApiResult<T> {
    public let value: T?
    public let failure: FailureKind?
    public let status: Int?

    public var isOk: Bool { failure == nil }

    public static func ok(_ value: T) -> ApiResult<T> { ApiResult(value: value, failure: nil, status: nil) }

    public static func failed(_ kind: FailureKind, status: Int? = nil) -> ApiResult<T> {
        ApiResult(value: nil, failure: kind, status: status)
    }

    public func map<U>(_ transform: (T) throws -> U) rethrows -> ApiResult<U> {
        if let value, failure == nil { return .ok(try transform(value)) }
        return .failed(failure ?? .parse, status: status)
    }
}

extension ApiResult: @unchecked Sendable {}

/// リダイレクトを追わない（Windows の AllowAutoRedirect = false と同じ）。
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void)
    {
        completionHandler(nil)
    }
}

/// YouTube Music の InnerTube（WEB_REMIX）への通信。認証のヘッダ（cookie・SAPISIDHASH）は music.youtube.com にだけ付ける。
/// 送った値・受けた値はログに出さない（呼び出し側が出すのは件数と状態だけ）。
public final class InnerTubeClient: @unchecked Sendable {
    public static let clientName = "WEB_REMIX"
    public static let clientId = "67"
    public static let clientVersion = "1.20260707.12.00"
    // Windows 版と同じ値を名乗る（YouTube の応答の形を2つの版でそろえる）
    public static let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    public static let origin = "https://music.youtube.com"
    private static let apiBase = origin + "/youtubei/v1/"
    private static let maxResponseBytes = 8 * 1024 * 1024
    private static let youTubeWebVersion = "2.20261001.00.00"

    public let session: URLSession
    private let lock = NSLock()
    private var credentials: Credentials?
    /// ログインしていないときの訪問者 ID（最初の応答の responseContext から取る。続きのトークンがこれに結び付く）。
    private var anonymousVisitor: String?
    private var generationValue: Int64 = 0

    /// 表示の言語（YouTube から届く文字の言語）。
    public let hl: String
    /// 地域。端末の設定のまま。
    public let gl: String

    public init(session: URLSession = InnerTubeClient.createSession(), hl: String = "ja", gl: String? = nil) {
        self.session = session
        self.hl = hl
        self.gl = gl ?? InnerTubeClient.safeRegion()
    }

    private static func safeRegion() -> String {
        let region = Locale.current.region?.identifier ?? ""
        return region.count == 2 ? region.uppercased() : "JP"
    }

    public var isLoggedIn: Bool { lock.withLock { credentials != nil } }

    /// ログインの世代。ログイン・ログアウトのたびに変わる（古い世代で始めた操作の結果を捨てるのに使う）。
    public var generation: Int64 { lock.withLock { generationValue } }

    public func setCredentials(_ value: Credentials?) {
        lock.withLock {
            credentials = value
            anonymousVisitor = nil
            generationValue += 1
        }
    }

    var currentCredentials: Credentials? { lock.withLock { credentials } }

    private var visitor: String? { lock.withLock { credentials?.visitorData ?? anonymousVisitor } }

    /// youtubei/v1/{endpoint} に POST する。setLogin なら（ログイン中のとき）認証のヘッダを付ける。
    public func post(_ endpoint: String, body: JSON, setLogin: Bool = true, query: [(String, String)] = []) async throws -> ApiResult<JSON> {
        let creds = setLogin ? currentCredentials : nil
        let loginGeneration = generation
        var body = body.obj ?? JSONObject()
        body["context"] = context(creds)
        var url = InnerTubeClient.apiBase + endpoint + "?prettyPrint=false"
        for (name, value) in query { url += "&" + name + "=" + InnerTubeClient.escape(value) }
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.httpBody = JSON.object(body).data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        try addHeaders(&request, creds)
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (status == 401 || status == 403) && creds != nil { return .failed(.notLoggedIn, status: status) }
            if status < 200 || status > 299 { return .failed(.http, status: status) }
            // 送ったあとでログインが変わっていたら、別のアカウントの結果として使わない
            if creds != nil && loginGeneration != generation { return .failed(.notLoggedIn) }
            if data.count > InnerTubeClient.maxResponseBytes { return .failed(.network) }
            guard let root = try? JSON.parse(data) else { return .failed(.parse) }
            lock.withLock {
                if credentials == nil, anonymousVisitor == nil, let v = root.path("responseContext", "visitorData").str {
                    anonymousVisitor = v
                }
            }
            return .ok(root)
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return .failed(.network)
        }
    }

    /// YouTube（Music ではない、www.youtube.com の WEB）の youtubei/v1/{endpoint} に、ログインの情報を付けずに POST する
    /// （アーティストのチャンネルの投稿を読むのに使う）。
    public func postYouTube(_ endpoint: String, body: JSON) async throws -> ApiResult<JSON> {
        var body = body.obj ?? JSONObject()
        body["context"] = .obj([
            "client": .obj(["clientName": "WEB", "clientVersion": .string(InnerTubeClient.youTubeWebVersion), "gl": .string(gl), "hl": .string(hl)]),
        ])
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = JSON.object(body).data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(InnerTubeClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status < 200 || status > 299 { return .failed(.http, status: status) }
            if data.count > InnerTubeClient.maxResponseBytes { return .failed(.network) }
            guard let root = try? JSON.parse(data) else { return .failed(.parse) }
            return .ok(root)
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return .failed(.network)
        }
    }

    /// 再生の統計（再生履歴）の GET。URL は music.youtube.com の /api/stats/playback に限る（呼ぶ側で検査済み）。
    func getStats(_ url: URL, expectedGeneration: Int64) async -> Int? {
        guard let creds = currentCredentials, expectedGeneration == generation else { return nil }
        var request = URLRequest(url: url)
        do {
            try addHeaders(&request, creds)
        } catch { return nil }
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        guard let (_, response) = try? await session.data(for: request) else { return nil }
        return (response as? HTTPURLResponse)?.statusCode
    }

    private func context(_ creds: Credentials?) -> JSON {
        var client = JSONObject([
            ("clientName", .string(InnerTubeClient.clientName)),
            ("clientVersion", .string(InnerTubeClient.clientVersion)),
            ("gl", .string(gl)),
            ("hl", .string(hl)),
        ])
        if let v = creds?.visitorData ?? visitor { client["visitorData"] = .string(v) }
        var user = JSONObject([("lockedSafetyMode", .bool(false))])
        if let dsid = creds?.revealDataSyncId() { user["onBehalfOfUser"] = .string(dsid) }
        return .obj([
            "client": .object(client),
            "request": .obj(["internalExperimentFlags": .array([]), "useSsl": true]),
            "user": .object(user),
        ])
    }

    public struct AuthRouteViolation: Error {}

    private func addHeaders(_ request: inout URLRequest, _ creds: Credentials?) throws {
        // 認証のヘッダは music.youtube.com への https にだけ付ける
        guard let url = request.url, url.scheme == "https", url.host == "music.youtube.com", url.port == nil else {
            throw AuthRouteViolation()
        }
        request.setValue(InnerTubeClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("1", forHTTPHeaderField: "X-Goog-Api-Format-Version")
        request.setValue(InnerTubeClient.clientId, forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(InnerTubeClient.clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        request.setValue(InnerTubeClient.origin, forHTTPHeaderField: "Origin")
        request.setValue(InnerTubeClient.origin, forHTTPHeaderField: "X-Origin")
        request.setValue(InnerTubeClient.origin + "/", forHTTPHeaderField: "Referer")
        request.setValue(hl, forHTTPHeaderField: "Accept-Language")
        if let v = creds?.visitorData ?? visitor { request.setValue(v, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        request.setValue(withPref(creds?.revealCookie()), forHTTPHeaderField: "Cookie")
        if let sapisid = creds?.revealSapisid() {
            request.setValue("0", forHTTPHeaderField: "X-Goog-AuthUser")
            request.setValue(InnerTubeClient.sapisidHash(sapisid, unixSeconds: Int64(Date().timeIntervalSince1970)), forHTTPHeaderField: "Authorization")
        }
    }

    static func sapisidHash(_ sapisid: String, unixSeconds: Int64) -> String {
        let digest = Insecure.SHA1.hash(data: Data("\(unixSeconds) \(sapisid) \(origin)".utf8))
        return "SAPISIDHASH \(unixSeconds)_" + digest.map { String(format: "%02x", $0) }.joined()
    }

    /// cookie の PREF に表示の言語と地域を入れる（無ければ PREF だけ）。
    func withPref(_ cookie: String?) -> String {
        guard let cookie, !cookie.trimmingCharacters(in: .whitespaces).isEmpty else { return "PREF=hl=\(hl)&gl=\(gl)" }
        var prefParts: [String] = []
        var others: [String] = []
        for raw in cookie.split(separator: ";") {
            let part = raw.trimmingCharacters(in: .whitespaces)
            if part.isEmpty { continue }
            if part.lowercased().hasPrefix("pref=") {
                prefParts += part.dropFirst(5).split(separator: "&").map(String.init).filter { !$0.hasPrefix("hl=") && !$0.hasPrefix("gl=") }
            } else {
                others.append(part)
            }
        }
        prefParts.insert("hl=\(hl)", at: 0)
        prefParts.insert("gl=\(gl)", at: 1)
        others.append("PREF=" + prefParts.joined(separator: "&"))
        return others.joined(separator: "; ")
    }

    static func escape(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    /// cookie を持たない URLSession（自動のリダイレクト・cookie の保存をしない）。
    public static func createSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }
}
