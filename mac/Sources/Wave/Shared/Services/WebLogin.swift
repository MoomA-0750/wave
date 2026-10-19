import Foundation
import WaveCore
import WebKit

/// Google アカウントへのログインの決まり（Android の WebView・Windows の WebView2 と同じやり方を WKWebView で）。Mac の窓・iPhone のシートで使う。
/// music.youtube.com に着いたら cookie と yt.config_ の VISITOR_DATA・DATASYNC_ID を読み、キーチェーンに保存する。
/// 開いてよいのはログインに要る Google・YouTube のホストだけで、ほかは止める。
@MainActor
enum WebLogin {
    static let startUrl = URL(string: "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F")!
    static let landingHost = "music.youtube.com"
    /// 既定の UA には Safari の名が無く、YouTube Music が「対応していないブラウザ」のページを返す（yt.config_ が無い）。Safari と名乗る
    #if os(macOS)
        static let safariName = "Version/18.0 Safari/605.1.15"
    #else
        static let safariName = "Version/18.0 Mobile/15E148 Safari/604.1"
    #endif

    /// メインフレームで開いてよいホスト（利用者がページを置ける sites・docs などは含めない）。
    static let loginHosts: Set<String> = [
        "accounts.google.com", "accounts.google.co.jp", "accounts.youtube.com", "myaccount.google.com", "gds.google.com",
        "consent.google.com", "consent.youtube.com", "google.com", "www.google.com", "www.google.co.jp",
        "youtube.com", "www.youtube.com", "m.youtube.com", landingHost,
    ]

    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", url.port == nil, let host = url.host else { return false }
        return loginHosts.contains(host)
    }

    /// ログインの WebView（ログインの窓と同じ保存場所）。
    static func makeWebView(frame: CGRect = .zero) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.applicationNameForUserAgent = safariName
        let webView = WKWebView(frame: frame, configuration: config)
        // 実アカウントでログイン中は開発者ツールを使わせない（Android 版の決定7と同じ）
        webView.isInspectable = false
        return webView
    }

    /// 開いてよい先か。止めた先のうち、利用者が自分で押した https のリンクだけ openOutside に渡す（ふだんのブラウザで開く）。
    static func policy(_ action: WKNavigationAction, openOutside: (URL) async -> Void) async -> WKNavigationActionPolicy {
        let url = action.request.url
        if action.targetFrame?.isMainFrame == false {
            // 埋め込みの枠は https だけを通す
            return url?.scheme == "https" || url?.scheme == "about" ? .allow : .cancel
        }
        if isAllowed(url) { return .allow }
        AppPaths.log("login blocked host=\(url?.host ?? "") user=\(action.navigationType == .linkActivated)")
        if action.navigationType == .linkActivated, let url, url.scheme == "https" { await openOutside(url) }
        return .cancel
    }

    /// 新しい窓は開かず、許した先なら同じ窓で開く。
    static func openInPlace(_ webView: WKWebView, _ action: WKNavigationAction) {
        if isAllowed(action.request.url) { webView.load(action.request) }
    }

    static func hasSapisid(_ cookie: String?) -> Bool {
        guard let cookie else { return false }
        return cookie.range(of: #"(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+"#, options: .regularExpression) != nil
    }

    enum Finish {
        /// まだ着いていない（ログインの cookie が無い）
        case notYet
        case loggedIn
        /// 失敗の理由の文
        case failed(String)
    }

    /// music.youtube.com に着いてログインの cookie があれば、値を読んで保存する。読み始めるときに begin を呼ぶ。
    static func finish(_ webView: WKWebView, begin: () -> Void) async -> Finish {
        guard webView.url?.host == landingHost else { return .notYet }
        var cookie = await cookies(webView)
        guard hasSapisid(cookie) else { return .notYet }
        begin()
        // ページの yt.config_ から値を読む（読めるまで少し待つ）
        var visitor: String?, dataSync: String?
        for attempt in 0..<20 where visitor == nil || dataSync == nil {
            if attempt > 0 { try? await Task.sleep(for: .milliseconds(500)) }
            if webView.url?.host != landingHost { break }
            visitor = await config(webView, "VISITOR_DATA")
            dataSync = await config(webView, "DATASYNC_ID")
        }
        cookie = await cookies(webView)
        let credentials = cookie.flatMap {
            Credentials.fromLogin(rawCookie: $0, visitorData: visitor, rawDataSyncId: dataSync, nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        }
        AppPaths.log("login landed sapisid=\(hasSapisid(cookie)) visitor=\(visitor != nil) dataSync=\(dataSync != nil) ok=\(credentials != nil)")
        guard let credentials else { return .failed(L.t("ログイン情報を取得できませんでした", "Couldn't get your login details")) }
        guard AppServices.account.logIn(credentials) else { return .failed(L.t("ログイン情報を保存できませんでした", "Couldn't save your login details")) }
        AppServices.toast.show(L.t("ログインしました", "Logged in"))
        return .loggedIn
    }

    /// music.youtube.com に送られる cookie（WebView2 の GetCookiesAsync と同じ選び方）。
    private static func cookies(_ webView: WKWebView) async -> String? {
        let all = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
        let picked = all.filter { c in
            let domain = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            return landingHost == domain || landingHost.hasSuffix("." + domain)
        }
        return picked.isEmpty ? nil : picked.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    private static func config(_ webView: WKWebView, _ key: String) async -> String? {
        let value = try? await webView.evaluateJavaScript("String(window.yt?.config_?.\(key) ?? '')") as? String
        return (value ?? "").isEmpty ? nil : value
    }
}
