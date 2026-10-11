#if os(macOS)
#if WAVE_DEBUG
import Foundation
import WebKit

/// 開発用: ログインの窓と同じ保存場所の WebView で music.youtube.com を開き、ページの設定の値が「あるか」だけを記録する（値は書かない）。
@MainActor
final class LoginProbe: NSObject, WKNavigationDelegate {
    private static var current: LoginProbe?
    private let webView: WKWebView

    static func run() {
        let probe = LoginProbe()
        current = probe
        probe.webView.load(URLRequest(url: URL(string: "https://music.youtube.com/")!))
    }

    private override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        // 既定の UA には Safari の名が無く、YouTube Music が「対応していないブラウザ」のページを返す（yt.config_ が無い）。Safari と名乗る
        config.applicationNameForUserAgent = LoginWindowController.safariName
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 800, height: 600), configuration: config)
        webView.isInspectable = false
        super.init()
        webView.navigationDelegate = self
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task {
            try? await Task.sleep(for: .seconds(3))
            let script = """
            JSON.stringify({host: location.host, title: document.title, ua: navigator.userAgent, scripts: document.scripts.length, yt: typeof window.yt, config: !!(window.yt && window.yt.config_),
              ytcfg: typeof window.ytcfg, ytcfgData: !!(window.ytcfg && window.ytcfg.data_),
              visitorConfig: !!(window.yt && window.yt.config_ && window.yt.config_.VISITOR_DATA),
              dataSyncConfig: !!(window.yt && window.yt.config_ && window.yt.config_.DATASYNC_ID),
              dataSyncYtcfg: !!(window.ytcfg && window.ytcfg.get && window.ytcfg.get('DATASYNC_ID')),
              visitorYtcfg: !!(window.ytcfg && window.ytcfg.get && window.ytcfg.get('VISITOR_DATA'))})
            """
            do {
                let result = try await webView.evaluateJavaScript(script)
                AppPaths.log("debug login-probe \(result as? String ?? "non-string")")
            } catch {
                AppPaths.log("debug login-probe error=\(error)")
            }
            LoginProbe.current = nil
        }
    }
}
#endif
#endif
