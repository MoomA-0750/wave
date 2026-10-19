#if os(iOS)
import SwiftUI
import WaveCore
import WebKit

/// Google アカウントへのログインのシート（決まりは Shared の WebLogin）。
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
                    Button(L.t("あとで", "Later")) {
                        // ログインし直すために開いたときは、閉じるだけ（今のログインのまま）
                        if LoginGate.shared.reLogin { LoginGate.shared.reLogin = false } else { LoginGate.shared.skipped = true }
                    }
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

@MainActor @Observable
final class LoginModel {
    var host: String?
    var status: String?
    var finishing = false

    func tryFinish(_ webView: WKWebView) async {
        guard !finishing else { return }
        let result = await WebLogin.finish(webView) {
            finishing = true
            status = L.t("ログインしています…", "Logging in…")
        }
        switch result {
        case .notYet: break
        case .loggedIn: LoginGate.shared.reLogin = false
        case .failed(let text):
            status = text
            finishing = false
        }
    }
}

private struct LoginWebView: UIViewRepresentable {
    var model: LoginModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let webView = WebLogin.makeWebView()
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.load(URLRequest(url: WebLogin.startUrl))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: LoginModel

        init(model: LoginModel) { self.model = model }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            // 利用者が自分で押したリンクだけ、Safari で開く
            await WebLogin.policy(action) { await UIApplication.shared.open($0) }
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView?
        {
            MainActor.assumeIsolated { WebLogin.openInPlace(webView, action) }
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
