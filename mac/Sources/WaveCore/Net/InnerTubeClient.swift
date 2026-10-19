import CryptoKit
import Foundation

public enum FailureKind: Sendable, Equatable {
    /// ログインしていない（呼ぶ前にログインが外れた・変わったときも）。
    case notLoggedIn
    /// ログインが切れたと確かめられた（もう一度ログインが要る。ADR-0004）。
    case expired
    /// ログインが切れた疑いがある（account_menu で確かめている。一時的な拒否のこともある）。
    case suspect
    case network
    case http
    case parse
}

/// ログインの状態（Android の WaveSession の SessionState と同じ。ADR-0004）。
/// 切れたと確かめられたときだけ expired にし、一時的な拒否（403・リダイレクト・ログアウト相当の応答）は suspect にして account_menu で確かめる。
/// 通信の失敗では変えない。
public enum SessionState: Sendable, Equatable {
    case loggedOut
    case active
    /// 失効を疑う応答が続いている（consecutive 回）。
    case suspect(consecutive: Int)
    case expired
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
    private var sessionValue: SessionState = .loggedOut
    /// 確かめ（account_menu）を走らせている世代と、最後に始めた時刻（同時に1本、30秒に1回まで）。
    private var confirming = false
    private var lastConfirmAt: Date?
    private static let confirmInterval: TimeInterval = 30
    static let signInHost = "accounts.google.com"

    /// ログインの状態が変わったとき（どのスレッドからも呼ばれる）。
    public var sessionStateChanged: (@Sendable (SessionState) -> Void)?

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
        let state: SessionState = value == nil ? .loggedOut : .active
        lock.withLock {
            credentials = value
            anonymousVisitor = nil
            generationValue += 1
            sessionValue = state
            confirming = false
            lastConfirmAt = nil
        }
        sessionStateChanged?(state)
    }

    /// 今のログインの状態。
    public var sessionState: SessionState { lock.withLock { sessionValue } }

    /// generation の世代のままなら、状態を変える（古い世代の結果で新しい世代を書き換えない）。
    private func updateSession(_ generation: Int64, _ next: (SessionState) -> SessionState) {
        let changed: SessionState? = lock.withLock {
            guard generation == generationValue, credentials != nil else { return nil }
            let value = next(sessionValue)
            guard value != sessionValue else { return nil }
            sessionValue = value
            return value
        }
        if let changed { sessionStateChanged?(changed) }
    }

    /// ログインが有効だと示す応答を受けた（account_menu のアカウントのヘッダ）。疑い・失効を解く。
    func markAuthenticated(_ generation: Int64) {
        updateSession(generation) { _ in .active }
    }

    /// ログイン中なのに、ログアウト相当の応答（ログインの案内だけ・アカウントのヘッダが無い）が返った。疑いにして確かめる。
    /// ログインしていなければ notLoggedIn。
    func suspectLoggedOut(_ generation: Int64) -> FailureKind {
        guard isLoggedIn else { return .notLoggedIn }
        suspect(generation)
        return generation == self.generation ? .suspect : .notLoggedIn
    }

    private func suspect(_ generation: Int64) {
        updateSession(generation) { previous in
            switch previous {
            case .expired: .expired
            case .suspect(let n): .suspect(consecutive: n + 1)
            default: .suspect(consecutive: 1)
            }
        }
        if sessionState != .expired { confirmSession() }
    }

    /// 本当にログインが外れているかを account_menu で確かめる（曲ごとの LOGIN_REQUIRED のあとなど）。状態は先に変えない。
    /// 同時に1本、前に始めてから30秒以上あける（レート制限のときに負荷を上乗せしない）。
    public func confirmSession() {
        let generation: Int64? = lock.withLock {
            guard credentials != nil, !confirming else { return nil }
            if let last = lastConfirmAt, Date().timeIntervalSince(last) < InnerTubeClient.confirmInterval { return nil }
            confirming = true
            lastConfirmAt = Date()
            return generationValue
        }
        guard let generation else { return }
        Task {
            await confirm(generation)
            lock.withLock { if generation == generationValue { confirming = false } }
        }
    }

    /// テスト用: 確かめを待つ。
    func confirmNow() async {
        await confirm(generation)
    }

    private func confirm(_ generation: Int64) async {
        guard let creds = currentCredentials, generation == self.generation,
            let request = try? makeRequest("account/account_menu", body: .obj([:]), creds: creds, query: []),
            let (data, response) = try? await session.data(for: request), let http = response as? HTTPURLResponse
        else { return }
        let status = http.statusCode
        switch status {
        case 300...399:
            let host = http.value(forHTTPHeaderField: "Location").flatMap { URL(string: $0)?.host }
            if host == InnerTubeClient.signInHost { updateSession(generation) { _ in .expired } }
        case 401:
            updateSession(generation) { _ in .expired }
        case 200...299:
            guard data.count <= InnerTubeClient.maxResponseBytes, let root = try? JSON.parse(data) else { return }
            if !root.findAll("activeAccountHeaderRenderer").isEmpty {
                updateSession(generation) { previous in if case .suspect = previous { .active } else { previous } }
            } else if !root.path("actions").items.isEmpty {
                // メニューはあるのにアカウントのヘッダが無い: ログアウトしている
                updateSession(generation) { _ in .expired }
            }
        default:
            break
        }
    }

    var currentCredentials: Credentials? { lock.withLock { credentials } }

    private var visitor: String? { lock.withLock { credentials?.visitorData ?? anonymousVisitor } }

    /// youtubei/v1/{endpoint} に POST する。setLogin なら（ログイン中のとき）認証のヘッダを付ける。
    public func post(_ endpoint: String, body: JSON, setLogin: Bool = true, query: [(String, String)] = []) async throws -> ApiResult<JSON> {
        let creds = setLogin ? currentCredentials : nil
        let loginGeneration = generation
        let request = try makeRequest(endpoint, body: body, creds: creds, query: query)
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // 送ったあとでログインが変わっていたら、別のアカウントの結果として使わない
            if creds != nil && loginGeneration != generation { return .failed(.notLoggedIn) }
            // 401 は切れた。403・リダイレクトは一時的な拒否のこともあるので疑いにして確かめる（ADR-0004）
            if creds != nil && status == 401 {
                updateSession(loginGeneration) { _ in .expired }
                return .failed(.expired, status: status)
            }
            if creds != nil && (status == 403 || (300...399).contains(status)) {
                suspect(loginGeneration)
                return .failed(sessionState == .expired ? .expired : .suspect, status: status)
            }
            if status < 200 || status > 299 { return .failed(.http, status: status) }
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

    private func makeRequest(_ endpoint: String, body: JSON, creds: Credentials?, query: [(String, String)]) throws -> URLRequest {
        var body = body.obj ?? JSONObject()
        body["context"] = context(creds)
        var url = InnerTubeClient.apiBase + endpoint + "?prettyPrint=false"
        for (name, value) in query { url += "&" + name + "=" + InnerTubeClient.escape(value) }
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.httpBody = JSON.object(body).data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        try addHeaders(&request, creds)
        return request
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
