import Foundation
import SwiftUI
import WaveCore

/// ダウンロードの画面（Mac・iPhone で同じ中身。ADR-0016 の 13・14）: スマートダウンロード（入り切り・分類・曲）、
/// 丸ごと取っているもの、1曲ずつ取った曲とエピソード、使っている容量と進み具合。
@MainActor @Observable
final class DownloadsModel {
    /// スマートダウンロードの分類のチップ（nil は「すべて」）。
    var category: SmartCategory?

    var downloads: Downloads { AppServices.downloads }

    static func label(_ category: SmartCategory?) -> String {
        switch category {
        case nil: L.t("すべて", "All")
        case .frequent: L.t("いつも聴いている", "Your favorites")
        case .discover: L.t("好きそう", "Something new")
        case .recommended: L.t("おすすめ", "Recommended")
        }
    }

    static let chips: [SmartCategory?] = [nil, .frequent, .discover, .recommended]

    /// 選んだ分類のスマートダウンロードの曲（取る順）。
    var smartSongs: [Song] { downloads.manifest.smartIds(category).compactMap { downloads.song($0) } }

    var collections: [DownloadCollection] { downloads.manifest.collections.sorted { $0.addedAtMs > $1.addedAtMs } }

    private var singles: [DownloadSong] { downloads.manifest.singles.compactMap { downloads.manifest.songs[$0] } }
    var singleSongs: [Song] { singles.filter { !$0.episode }.map(\.song) }
    var episodes: [Song] { singles.filter(\.episode).map(\.song) }

    /// 「3 / 10 曲」（取り終えたら曲数だけ）。
    func countText(_ c: DownloadCollection) -> String {
        let done = c.trackIds.filter { downloads.isDownloaded($0) }.count
        return done >= c.trackIds.count ? L.songs(c.trackIds.count) : "\(done) / " + L.songs(c.trackIds.count)
    }

    func kindText(_ c: DownloadCollection) -> String {
        switch c.kind {
        case .album: L.t("アルバム", "Album")
        case .playlist: L.t("プレイリスト", "Playlist")
        case .liked: L.t("プレイリスト • 自動", "Playlist • Auto")
        }
    }

    func open(_ c: DownloadCollection) {
        switch c.kind {
        case .liked: AppServices.nav.likedSongs()
        case .album: AppServices.nav.album(c.id, AlbumSummary(browseId: c.id, title: c.title, subtitle: nil, thumbnailUrl: c.thumbnailUrl))
        case .playlist: AppServices.nav.playlist(PlaylistSummary(playlistId: c.id, title: c.title, subtitle: nil, thumbnailUrl: c.thumbnailUrl))
        }
    }

    func play(_ songs: [Song], start: Int = 0, shuffle: Bool = false) {
        guard !songs.isEmpty else { return }
        AppServices.player.playList(songs, start: start, source: "downloads", shuffleOverride: shuffle ? true : nil)
    }

    /// 使っている容量と進み具合の1行。
    var statusText: String { DownloadsModel.statusText(downloads) }

    static func statusText(_ d: Downloads) -> String {
        let used = L.t("使っている容量: ", "Storage used: ") + ByteCountFormatter.string(fromByteCount: d.totalBytes, countStyle: .file)
        let pending = d.pending.count
        if pending == 0 { return used }
        let state: String
        if d.waitingForNetwork {
            state = d.offline ? L.t("通信を待っています", "Waiting for a connection")
                : L.t("Wi-Fi など従量制でない通信を待っています", "Waiting for Wi-Fi or another unmetered network")
        } else if let id = d.active, let song = d.song(id) {
            state = L.t("取っています: \(song.title)（\(Int(d.activeProgress * 100))%）", "Downloading: \(song.title) (\(Int(d.activeProgress * 100))%)")
        } else {
            state = L.t("待っています", "Queued")
        }
        return used + " • " + L.t("残り \(pending) 曲", "\(pending) left") + " • " + state
    }

    static func policyLabel(_ p: DownloadNetworkPolicy) -> String {
        switch p {
        case .unmetered: L.t("Wi-Fi など従量制でない通信だけ", "Wi-Fi and other unmetered networks only")
        case .any: L.t("どの通信でも", "Any network")
        }
    }

    /// 曲数の選択肢の表示。
    static func countLabel(_ n: Int) -> String { L.songs(n) }
}
