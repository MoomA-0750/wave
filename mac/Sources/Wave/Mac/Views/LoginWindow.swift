#if os(macOS)
import AppKit
import SwiftUI
import WaveCore
import WebKit

/// Google アカウントへのログインの窓（決まりは Shared の WebLogin）。
@MainActor
final class LoginWindowController: NSObject, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate {
    private static var shared: LoginWindowController?
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

    private override init() {
        webView = WebLogin.makeWebView()
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
        webView.load(URLRequest(url: WebLogin.startUrl))
    }

    func windowWillClose(_ notification: Notification) {
        LoginWindowController.shared = nil
    }

    // MARK: 開いてよい先

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        await WebLogin.policy(action) { NSWorkspace.shared.open($0) }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView?
    {
        WebLogin.openInPlace(webView, action)
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

    private func tryFinish() async {
        guard !finishing else { return }
        let result = await WebLogin.finish(webView) {
            finishing = true
            webView.isHidden = true
            showStatus(L.t("ログインしています…", "Logging in…"))
        }
        switch result {
        case .notYet: break
        case .loggedIn: window.close()
        case .failed(let text):
            status.stringValue = text
            spinner.stopAnimation(nil)
            spinner.isHidden = true
            finishing = false
        }
    }
}
#endif
