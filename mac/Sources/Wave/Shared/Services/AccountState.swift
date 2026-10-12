import Foundation
import Observation
import WaveCore
import WebKit

/// ログインの状態とアカウントの名前・画像。
@MainActor @Observable
final class AccountState {
    var isLoggedIn = false
    var name: String?
    var photoUrl: String?
    /// 保存したログイン情報が読めなかった（壊れた・別のユーザー）。
    var savedLoginUnreadable = false
    /// Premium 音質を使えるか（nil は確かめている・分からない）。
    var premiumAudio: Bool?
    /// ログインを確かめられなかった（期限切れなど。もう一度ログインを勧める）。
    var accountCheckFailed = false
    /// アカウントを確かめ終えた（確かめている間は「確かめています」と出す）。
    var accountChecked = false

    /// ログイン・ログアウトしたとき。
    @ObservationIgnored var changed: [() -> Void] = []

    /// 今のログインの取得時刻（ログインしていなければ 0）。保存したキューを、同じログインのときだけ戻すのに使う。
    private(set) var obtainedAtEpochMs: Int64 = 0

    func restore() {
        let (state, credentials) = AppServices.credentials.load()
        savedLoginUnreadable = state == .unreadable
        if let credentials { apply(credentials) }
        AppPaths.log("account restore state=\(state.rawValue)")
    }

    /// ログインで得た値を使い始め、保存する。
    func logIn(_ credentials: Credentials) -> Bool {
        guard AppServices.credentials.save(credentials) else { return false }
        savedLoginUnreadable = false
        AppServices.player.stop()
        apply(credentials)
        return true
    }

    private func apply(_ credentials: Credentials) {
        AppServices.client.setCredentials(credentials)
        obtainedAtEpochMs = credentials.obtainedAtEpochMs
        isLoggedIn = true
        changed.forEach { $0() }
        Task { await refreshAccount() }
    }

    func refreshAccount() async {
        let result = (try? await AppServices.music.account()) ?? .failed(.network)
        if let info = result.value {
            name = info.name
            photoUrl = info.photoUrl
        }
        accountCheckFailed = !result.isOk
        accountChecked = true
        premiumAudio = result.isOk ? (try? await AppServices.resolver.premiumAudio()) ?? nil : nil
        AppPaths.log("account check ok=\(result.isOk) failure=\(String(describing: result.failure))")
    }

    func logOut() {
        AppServices.player.stop()
        AppServices.client.setCredentials(nil)
        AppServices.credentials.clear()
        AppServices.releases.clear()
        // 前のアカウントの Claude との会話を残さない（履歴も消す。Android と同じ）
        AppServices.ai.wipe()
        obtainedAtEpochMs = 0
        isLoggedIn = false
        premiumAudio = nil
        accountCheckFailed = false
        accountChecked = false
        name = nil
        photoUrl = nil
        // ログイン用のブラウザに残る Google の cookie も消す
        let store = WKWebsiteDataStore.default()
        store.fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in
            store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: records) {}
        }
        changed.forEach { $0() }
    }
}
