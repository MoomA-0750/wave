#if os(iOS)
import SwiftUI
import WaveCore
import WebKit

/// Google アカウントへのログイン（Android の WebView・Mac の WKWebView と同じやり方）。
/// music.youtube.com に着いたら cookie と yt.config_ の VISITOR_DATA・DATASYNC_ID を読み、キーチェーンに保存する。
/// 開いてよいのはログインに要る Google・YouTube のホストだけで、ほかは止める。
struct LoginView: View {
    @State private var model = LoginModel()

    var body: some View {
        NavigationStack {
            ZStack {
                LoginWebView(model: model)
                    .opacity(model.finishing ? 0 : 1)
                if model.finishing || model.status != nil {
                    VStack(spacing: 12) {
                        if model.finishing { ProgressView() }
                        if let status = model.status { Text(status).multilineTextAlignment(.center).foregroundStyle(.secondary) }
                    }
                    .padding()
                }
            }
            .navigationTitle(L.t("wave にログイン", "Log in to wave"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Label(model.host ?? "accounts.google.com", systemImage: "lock.fill").font(.footnote).labelStyle(.titleAndIcon)
                }
                ToolbarItem(placement: .cancellationAction) {
                    // ログインせずに使う（検索・歌詞などは見られる。曲は鳴らない）
                    Button(L.t("あとで", "Later")) { LoginGate.shared.skipped = true }
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

@MainActor @Observable
final class LoginModel {
    static let startUrl = URL(string: "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F")!
    static let landingHost = "music.youtube.com"
    /// 既定の UA には Safari の名が無く、YouTube Music が「対応していないブラウザ」のページを返す（yt.config_ が無い）。Safari と名乗る
    static let safariName = "Version/18.0 Mobile/15E148 Safari/604.1"

    /// メインフレームで開いてよいホスト（利用者がページを置ける sites・docs などは含めない）。
    static let loginHosts: Set<String> = [
        "accounts.google.com", "accounts.google.co.jp", "accounts.youtube.com", "myaccount.google.com", "gds.google.com",
        "consent.google.com", "consent.youtube.com", "google.com", "www.google.com", "www.google.co.jp",
        "youtube.com", "www.youtube.com", "m.youtube.com", landingHost,
    ]

    var host: String?
    var status: String?
    var finishing = false

    static func isAllowed(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", url.port == nil, let host = url.host else { return false }
        return loginHosts.contains(host)
    }

    static func hasSapisid(_ cookie: String?) -> Bool {
        guard let cookie else { return false }
        return cookie.range(of: #"(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+"#, options: .regularExpression) != nil
    }

    func tryFinish(_ webView: WKWebView) async {
        guard !finishing, webView.url?.host == LoginModel.landingHost else { return }
        var cookie = await cookies(webView)
        guard LoginModel.hasSapisid(cookie) else { return }
        finishing = true
        status = L.t("ログインしています…", "Logging in…")
        // ページの yt.config_ から値を読む（読めるまで少し待つ）
        var visitor: String?, dataSync: String?
        for attempt in 0..<20 where visitor == nil || dataSync == nil {
            if attempt > 0 { try? await Task.sleep(for: .milliseconds(500)) }
            if webView.url?.host != LoginModel.landingHost { break }
            visitor = await config(webView, "VISITOR_DATA")
            dataSync = await config(webView, "DATASYNC_ID")
        }
        cookie = await cookies(webView)
        let credentials = cookie.flatMap {
            Credentials.fromLogin(rawCookie: $0, visitorData: visitor, rawDataSyncId: dataSync, nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        }
        AppPaths.log("login landed sapisid=\(LoginModel.hasSapisid(cookie)) visitor=\(visitor != nil) dataSync=\(dataSync != nil) ok=\(credentials != nil)")
        guard let credentials, AppServices.account.logIn(credentials) else {
            status = credentials == nil ? L.t("ログイン情報を取得できませんでした", "Couldn't get your login details")
                : L.t("ログイン情報を保存できませんでした", "Couldn't save your login details")
            finishing = false
            return
        }
        AppServices.toast.show(L.t("ログインしました", "Logged in"))
    }

    /// music.youtube.com に送られる cookie（Mac・Windows と同じ選び方）。
    private func cookies(_ webView: WKWebView) async -> String? {
        let all = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
        let host = LoginModel.landingHost
        let picked = all.filter { c in
            let domain = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            return host == domain || host.hasSuffix("." + domain)
        }
        return picked.isEmpty ? nil : picked.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    private func config(_ webView: WKWebView, _ key: String) async -> String? {
        let value = try? await webView.evaluateJavaScript("String(window.yt?.config_?.\(key) ?? '')") as? String
        return (value ?? "").isEmpty ? nil : value
    }
}

private struct LoginWebView: UIViewRepresentable {
    var model: LoginModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.applicationNameForUserAgent = LoginModel.safariName
        let webView = WKWebView(frame: .zero, configuration: config)
        // 実アカウントでログイン中は開発者ツールを使わせない（Android 版の決定7と同じ）
        webView.isInspectable = false
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.load(URLRequest(url: LoginModel.startUrl))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: LoginModel

        init(model: LoginModel) { self.model = model }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            let url = action.request.url
            if action.targetFrame?.isMainFrame == false {
                // 埋め込みの枠は https だけを通す
                return url?.scheme == "https" || url?.scheme == "about" ? .allow : .cancel
            }
            if LoginModel.isAllowed(url) { return .allow }
            AppPaths.log("login blocked host=\(url?.host ?? "") user=\(action.navigationType == .linkActivated)")
            // 利用者が自分で押したリンクだけ、Safari で開く
            if action.navigationType == .linkActivated, let url, url.scheme == "https" { await UIApplication.shared.open(url) }
            return .cancel
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView?
        {
            // 新しい窓は開かず、許した先なら同じ窓で開く
            if LoginModel.isAllowed(action.request.url) { webView.load(action.request) }
            return nil
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            MainActor.assumeIsolated { model.host = webView.url?.host }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in await model.tryFinish(webView) }
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            AppPaths.log("login webview process terminated")
            MainActor.assumeIsolated { model.status = L.t("ログイン画面が異常終了しました。閉じてやり直してください", "The login page crashed. Close it and try again") }
        }
    }
}
#endif
