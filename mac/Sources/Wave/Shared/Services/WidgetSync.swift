import Foundation
import WaveCore
import WidgetKit
#if os(iOS)
    import ActivityKit
#endif

/// ウィジェットと、iPhone のロック画面の「再生中」（Live Activity）に、今の曲とライブラリのプレイリストを渡し、そのボタンの頼みを受ける。
@MainActor
final class WidgetSync {
    static let shared = WidgetSync()

    private var timer: Timer?
    private var shown: WidgetNowPlaying?
    private var shownPlaylists: [WidgetPlaylist]?
    #if os(iOS)
        private var activity: Activity<NowPlayingAttributes>?
    #endif

    func start() {
        WidgetBridge.handler = { command in await WidgetSync.handle(command) }
        #if os(iOS)
            activity = Activity<NowPlayingAttributes>.activities.first
        #endif
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            MainActor.assumeIsolated { WidgetSync.shared.tick() }
        }
    }

    private static func handle(_ command: WidgetCommand) async {
        guard let player = AppServices.player else { return }
        AppPaths.log("widget command")
        switch command {
        case .togglePlay: player.togglePlay()
        case .next: player.next()
        case .previous: player.previous()
        case .playPlaylist(let id, let shuffle):
            if player.shuffle != shuffle { player.toggleShuffle() }
            let title = WidgetStore.playlists.first { $0.id == id }?.title ?? ""
            AppServices.nav.play(.playlist(PlaylistSummary(playlistId: id, title: title, subtitle: nil, thumbnailUrl: nil)))
        }
    }

    private func tick() {
        guard let player = AppServices.player else { return }
        let now = player.current.map { song in
            let next = player.isRemote || player.index + 1 >= player.queue.count ? nil : player.queue[player.index + 1]
            return WidgetNowPlaying(videoId: song.videoId, title: song.title, artist: song.artistsText, playing: player.isPlaying,
                                    device: player.isRemote ? player.remoteDevice : nil, nextTitle: next?.title, nextArtist: next?.artistsText)
        }
        if now != shown {
            let songChanged = now?.videoId != shown?.videoId
            shown = now
            if let now, songChanged {
                Task { await saveArtwork(now.videoId, player.current?.thumbnailUrl) }
            } else {
                publish(now)
            }
        }
        syncPlaylists()
    }

    /// ジャケットを置き場に書いてから知らせる（ウィジェットとロック画面は通信せずに読む）。
    private func saveArtwork(_ videoId: String, _ thumbnail: String?) async {
        if let dir = WidgetStore.container, let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for name in old where name.hasPrefix("artwork-") && name != "artwork-\(videoId).jpg" {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
            }
        }
        if let url = Ui.resize(thumbnail, 240), url.scheme == "https", let file = WidgetStore.artworkFile(videoId),
           !FileManager.default.fileExists(atPath: file.path), let (data, _) = try? await ImageLoader.session.data(from: url)
        {
            try? data.write(to: file, options: .atomic)
        }
        guard shown?.videoId == videoId else { return }
        publish(shown)
    }

    private func publish(_ now: WidgetNowPlaying?) {
        if let now {
            WidgetStore.write(now, "nowplaying.json")
        } else if let file = WidgetStore.file("nowplaying.json") {
            try? FileManager.default.removeItem(at: file)
        }
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.nowPlayingKind)
        #if os(iOS)
            updateActivity(now)
        #endif
    }

    // MARK: ロック画面の再生中

    #if os(iOS)
    private func updateActivity(_ now: WidgetNowPlaying?) {
        guard let now else {
            if let activity {
                self.activity = nil
                Task { await activity.end(nil, dismissalPolicy: .immediate) }
            }
            return
        }
        let state = NowPlayingAttributes.ContentState(videoId: now.videoId, title: now.title, artist: now.artist, playing: now.playing,
                                                      device: now.device, nextTitle: now.nextTitle)
        let content = ActivityContent(state: state, staleDate: nil)
        if let activity, activity.activityState == .active {
            Task { await activity.update(content) }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            // 始められるのは画面が前にあるときだけ。裏で失敗したら、次に変わったときにまた試す
            activity = try Activity.request(attributes: NowPlayingAttributes(), content: content)
            AppPaths.log("live activity start")
        } catch {
            AppPaths.log("live activity failed")
        }
    }
    #endif

    // MARK: プレイリスト

    private func syncPlaylists() {
        guard let library = AppServices.library else { return }
        var list = [WidgetPlaylist(id: MusicRepository.likedSongs, title: L.t("高く評価した曲", "Liked songs"), thumbnailUrl: nil)]
        list += library.playlists.map { WidgetPlaylist(id: $0.playlistId, title: $0.title, thumbnailUrl: Ui.resize($0.thumbnailUrl, 240)?.absoluteString) }
        guard AppServices.client.isLoggedIn, list != shownPlaylists, !library.playlists.isEmpty else { return }
        shownPlaylists = list
        WidgetStore.write(list, "playlists.json")
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.playlistKind)
    }
}
