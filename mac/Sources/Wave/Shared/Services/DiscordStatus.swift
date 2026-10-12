#if os(macOS)
import Foundation
import WaveCore

/// Discord に「wave を聴いています」を出す（設定でオンにし、Discord のアプリの ID を入れたときだけ。Windows の DiscordStatus と同じ）。
/// ほかの端末の再生を映しているとき（スマホで鳴らしているなど）も、その曲を出す。スマホの Discord には出す手段が無いため。
/// iPhone の Discord にはローカルの IPC が無いので、macOS だけで動かす。
@MainActor
final class DiscordStatus {
    private let presence = DiscordPresence(log: { AppPaths.log($0) })
    private var timer: Timer?

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.push() }
        }
    }

    /// 今の状態の文（設定の画面に出す）。
    var statusText: String {
        switch presence.status {
        case .connected: L.t("Discord につないでいます", "Connected to Discord")
        case .notFound: L.t("Discord が見つかりません（Discord のアプリを起動すると、自動でつなぎます）", "Discord not found (wave connects when the Discord app is running)")
        case .refused: L.t("Discord につなげませんでした。アプリケーション ID を確かめてください", "Discord refused the connection. Check the application ID")
        case .off: L.t("曲を再生すると Discord につなぎます", "wave connects to Discord when you play a song")
        }
    }

    /// 設定が変わった・曲が変わったなどで、すぐ反映する。
    func push() {
        let settings = AppServices.settings!
        let clientId = settings.discordPresence ? settings.discordClientId : nil
        presence.update(clientId: clientId, track: clientId == nil ? nil : Self.track(AppServices.player))
    }

    private static func track(_ player: PlayerService) -> PresenceTrack? {
        guard let song = player.current else { return nil }
        let place = player.isRemote ? player.remoteDevice.map { L.t("\($0) で再生中", "Playing on \($0)") } : nil
        let large = [song.album?.title, place].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        let duration = player.duration > 0 ? player.duration : Double(song.durationSeconds ?? 0)
        return PresenceTrack(videoId: song.videoId, title: song.title, artists: song.artistsText, largeText: large.isEmpty ? nil : large,
                             thumbnailUrl: song.thumbnailUrl, positionSeconds: player.position, durationSeconds: duration, playing: player.isPlaying,
                             buttonLabel: L.t("YouTube Music で開く", "Open in YouTube Music"))
    }
}
#endif
