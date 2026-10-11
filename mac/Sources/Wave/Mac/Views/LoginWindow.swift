#if os(macOS)
import AppKit
import SwiftUI
import WaveCore
import WebKit

/// Google アカウントへのログイン（Android 版の WebView・Windows 版の WebView2 と同じやり方を WKWebView で）。
/// music.youtube.com に着いたら cookie と yt.config_ の VISITOR_DATA・DATASYNC_ID を読み、キーチェーンに保存する。
/// 開いてよいのはログインに要る Google・YouTube のホストだけで、ほかは止める。
@MainActor
final class LoginWindowController: NSObject, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate {
    private static var shared: LoginWindowController?
    private static let startUrl = URL(string: "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F")!
    private static let landingHost = "music.youtube.com"
    static let safariName = "Version/18.0 Safari/605.1.15"

    /// メインフレームで開いてよいホスト（利用者がページを置ける sites・docs などは含めない）。
    private static let loginHosts: Set<String> = [
        "accounts.google.com", "accounts.google.co.jp", "accounts.youtube.com", "myaccount.google.com", "gds.google.com",
        "consent.google.com", "consent.youtube.com", "google.com", "www.google.com", "www.google.co.jp",
        "youtube.com", "www.youtube.com", "m.youtube.com", landingHost,
    ]

    private let window: NSWindow
    private let webView: WKWebView
    private let hostLabel = NSTextField(labelWithString: L.t("Google アカウントでログインしてください", "Log in with your Google account"))
    private let status = NSTextField(labelWithString: L.t("ログインしています…", "Logging in…"))
    private let spinner = NSProgressIndicator()
    private var finishing = false

    static func show() {
        if let shared {
            shared.window.makeKeyAndOrderFront(nil)
            return
        }
        let controller = LoginWindowController()
        shared = controller
        controller.window.makeKeyAndOrderFront(nil)
    }

    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", url.port == nil, let host = url.host else { return false }
        return loginHosts.contains(host)
    }

    private override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        // 既定の UA には Safari の名が無く、YouTube Music が「対応していないブラウザ」のページを返す（yt.config_ が無い）。Safari と名乗る
        config.applicationNameForUserAgent = LoginWindowController.safariName
        // 実アカウントでログイン中は開発者ツールを使わせない（Android 版の決定7と同じ）
        webView = WKWebView(frame: .zero, configuration: config)
        webView.isInspectable = false
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 760), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        super.init()
        window.title = L.t("wave にログイン", "Log in to wave")
        window.identifier = NSUserInterfaceItemIdentifier("login")
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        webView.navigationDelegate = self
        webView.uiDelegate = self

        let lock = NSImageView(image: NSImage(systemSymbolName: "lock.fill", accessibilityDescription: nil)!)
        hostLabel.font = .systemFont(ofSize: 12)
        hostLabel.lineBreakMode = .byTruncatingTail
        let bar = NSStackView(views: [lock, hostLabel])
        bar.orientation = .horizontal
        bar.spacing = 8
        bar.edgeInsets = NSEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
        status.isHidden = true
        spinner.style = .spinning
        spinner.isHidden = true
        let overlay = NSStackView(views: [spinner, status])
        overlay.orientation = .vertical
        overlay.spacing = 12
        let content = NSView()
        for v in [bar, webView, overlay] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(v)
        }
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: content.topAnchor),
            bar.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            webView.topAnchor.constraint(equalTo: bar.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            overlay.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            overlay.centerYAnchor.constraint(equalTo: content.centerYAnchor),
        ])
        window.contentView = content
        webView.load(URLRequest(url: LoginWindowController.startUrl))
    }

    func windowWillClose(_ notification: Notification) {
        LoginWindowController.shared = nil
    }

    // MARK: 開いてよい先

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        let url = action.request.url
        if action.targetFrame?.isMainFrame == false {
            // 埋め込みの枠は https だけを通す
            return url?.scheme == "https" || url?.scheme == "about" ? .allow : .cancel
        }
        if LoginWindowController.isAllowed(url) { return .allow }
        AppPaths.log("login blocked host=\(url?.host ?? "") user=\(action.navigationType == .linkActivated)")
        // 利用者が自分で押したリンクだけ、ふだんのブラウザで開く
        if action.navigationType == .linkActivated, let url, url.scheme == "https" { NSWorkspace.shared.open(url) }
        return .cancel
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView?
    {
        // 新しい窓は開かず、許した先なら同じ窓で開く
        if LoginWindowController.isAllowed(action.request.url) { webView.load(action.request) }
        return nil
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        hostLabel.stringValue = webView.url?.host ?? ""
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { await tryFinish() }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        AppPaths.log("login webview process terminated")
        showStatus(L.t("ログイン画面が異常終了しました。閉じてやり直してください", "The login page crashed. Close it and try again"))
    }

    private func showStatus(_ text: String) {
        status.stringValue = text
        status.isHidden = false
        spinner.isHidden = false
        spinner.startAnimation(nil)
    }

    // MARK: 着いたら値を読む

    private static func hasSapisid(_ cookie: String?) -> Bool {
        guard let cookie else { return false }
        return cookie.range(of: #"(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+"#, options: .regularExpression) != nil
    }

    private func tryFinish() async {
        guard !finishing, webView.url?.host == LoginWindowController.landingHost else { return }
        var cookie = await cookies()
        guard LoginWindowController.hasSapisid(cookie) else { return }
        finishing = true
        webView.isHidden = true
        showStatus(L.t("ログインしています…", "Logging in…"))
        // ページの yt.config_ から値を読む（読めるまで少し待つ）
        var visitor: String?, dataSync: String?
        for attempt in 0..<20 where visitor == nil || dataSync == nil {
            if attempt > 0 { try? await Task.sleep(for: .milliseconds(500)) }
            if webView.url?.host != LoginWindowController.landingHost { break }
            visitor = await config("VISITOR_DATA")
            dataSync = await config("DATASYNC_ID")
        }
        cookie = await cookies()
        let credentials = cookie.flatMap {
            Credentials.fromLogin(rawCookie: $0, visitorData: visitor, rawDataSyncId: dataSync, nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        }
        AppPaths.log("login landed sapisid=\(LoginWindowController.hasSapisid(cookie)) visitor=\(visitor != nil) dataSync=\(dataSync != nil) ok=\(credentials != nil)")
        guard let credentials, AppServices.account.logIn(credentials) else {
            status.stringValue = credentials == nil ? L.t("ログイン情報を取得できませんでした", "Couldn't get your login details")
                : L.t("ログイン情報を保存できませんでした", "Couldn't save your login details")
            spinner.stopAnimation(nil)
            spinner.isHidden = true
            finishing = false
            return
        }
        AppServices.toast.show(L.t("ログインしました", "Logged in"))
        window.close()
    }

    /// music.youtube.com に送られる cookie（WebView2 の GetCookiesAsync と同じ選び方）。
    private func cookies() async -> String? {
        let all = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
        let host = LoginWindowController.landingHost
        let picked = all.filter { c in
            let domain = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            return host == domain || host.hasSuffix("." + domain)
        }
        return picked.isEmpty ? nil : picked.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    private func config(_ key: String) async -> String? {
        let value = try? await webView.evaluateJavaScript("String(window.yt?.config_?.\(key) ?? '')") as? String
        return (value ?? "").isEmpty ? nil : value
    }
}
#endif
