import AppKit
import Foundation
import Observation
import UserNotifications
import WaveCore

/// 登録したアーティストの新作（Android の Releases と同じ）。アプリが動いている間、起動の少し後と6時間おきに確かめ、
/// 見つけたら macOS の通知で知らせる。最近の新作はホームとアーティストのページに出す。
@MainActor @Observable
final class Releases: NSObject, UNUserNotificationCenterDelegate {
    private static var fileURL: URL { AppPaths.file("release-watch.json") }
    private static let interval: TimeInterval = 6 * 60 * 60
    private static let maxArtists = 300
    private static let maxNotifications = 10
    /// 新作を通知するのに要る、そのアーティストの高く評価した曲の数。
    private static let minLikedToNotify = 3

    /// 最近の新作（新しい順）。
    private(set) var recent: [NewRelease] = []
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var checking = false

    private static func read() -> ReleaseWatchState {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return .empty }
        return ReleaseWatchState.decode(text) ?? .empty
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 保存してある最近の新作を読み、定期の確かめを始める。通知を押したらそのアルバム（動画ならアーティスト）を開く。
    func start() {
        recent = Releases.read().expire(Releases.nowMs).recent
        // 通知はアプリの包み（.app）から動いているときだけ使える
        if Bundle.main.bundleIdentifier != nil {
            let center = UNUserNotificationCenter.current()
            center.delegate = self
            center.requestAuthorization(options: [.alert, .sound]) { granted, _ in AppPaths.log("notify authorized=\(granted)") }
        }
        // 最初は起動の1分後、その後は6時間おき
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                Task { await self.check() }
                self.timer = Timer.scheduledTimer(withTimeInterval: Releases.interval, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { if let self { Task { await self.check() } } }
                }
            }
        }
    }

    /// 登録したアーティストのページを順に見て、新作を探す（同時に1本だけ）。
    func check() async {
        if checking || !AppServices.client.isLoggedIn || !AppServices.settings.releaseNotices { return }
        checking = true
        defer { checking = false }
        guard let subscribed = (try? await AppServices.music.subscribedArtists())?.value else { return }
        let artists = Array(subscribed.prefix(Releases.maxArtists))
        let now = Releases.nowMs
        // 登録の一覧は途中のページで失敗すると欠けて返るので、一覧に無いアーティストの記録は捨てない（Android と同じ）
        var state = Releases.read().expire(now)
        var found: [NewRelease] = []
        let year = Calendar.current.component(.year, from: Date())
        for artist in artists {
            if let page = (try? await AppServices.music.artist(artist.browseId))?.value {
                let (fresh, next) = state.observe(artist.browseId, page: page, nowEpochMs: now, year: year)
                state = next
                found += fresh
            }
            // まとめて叩かない
            try? await Task.sleep(for: .milliseconds(300))
        }
        try? state.encode().write(to: Releases.fileURL, atomically: true, encoding: .utf8)
        recent = state.recent
        AppPaths.log("release watch artists=\(artists.count) new=\(found.count) recent=\(recent.count)")
        if found.isEmpty { return }
        // 鳴らす通知は、よく聴くアーティスト（高く評価した曲が minLikedToNotify 曲以上）の新作だけにする（Android と同じ）
        let (liked, _) = await AppServices.library.likedSongs()
        let favorites = found.filter { LibraryState.byArtist(liked, $0.artistId, $0.artistName).count >= Releases.minLikedToNotify }
        AppPaths.log("release notify found=\(found.count) favorites=\(favorites.count) liked=\(liked.count)")
        for release in favorites.prefix(Releases.maxNotifications) { await notify(release) }
    }

    func notify(_ release: NewRelease) async {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = release.videoId != nil ? L.t("\(release.artistName) の新しい動画", "New video from \(release.artistName)")
            : L.t("\(release.artistName) の新作", "New from \(release.artistName)")
        content.body = release.title
        content.userInfo = release.albumBrowseId.map { ["album": $0] } ?? ["artist": release.artistId]
        if let file = await imageFile(release), let attachment = try? UNNotificationAttachment(identifier: "art", url: file) {
            content.attachments = [attachment]
        }
        do {
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: release.key, content: content, trigger: nil))
        } catch {
            AppPaths.log("notify failed error=\((error as NSError).domain)/\((error as NSError).code)")
        }
    }

    /// 通知の画像（ファイルに落としてから付ける）。
    private func imageFile(_ release: NewRelease) async -> URL? {
        guard let url = Ui.resize(release.thumbnailUrl, 256), url.scheme == "https",
            let (data, _) = try? await ImageLoader.session.data(from: url)
        else { return nil }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("wave-\(abs(release.key.hashValue)).jpg")
        return (try? data.write(to: file)) != nil ? file : nil
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let album = info["album"] as? String, artist = info["artist"] as? String
        await MainActor.run {
            if let album { AppServices.nav.album(album) } else if let artist { AppServices.nav.artist(artist) }
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    /// ログアウト・アカウントの切り替え。
    func clear() {
        try? FileManager.default.removeItem(at: Releases.fileURL)
        recent = []
    }
}
