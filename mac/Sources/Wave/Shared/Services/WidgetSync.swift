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
        // ウィジェットの文言を画面の言語に合わせる
        WidgetStore.write(L.ja ? "ja" : "en", "language.json")
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
        let wasPlaying = player.isPlaying
        let shouldPlay: Bool
        switch command {
        case .togglePlay:
            player.togglePlay()
            shouldPlay = !wasPlaying
        case .next:
            player.next()
            shouldPlay = wasPlaying
        case .previous:
            player.previous()
            shouldPlay = wasPlaying
        case .toggleLike:
            // ウィジェットでは✓を押すと評価を外す（アプリの中のボタンと違い、プレイリストに追加する画面は開かない）
            player.toggleLike()
            shouldPlay = false
        case .playPlaylist(let id, let shuffle):
            if player.shuffle != shuffle { player.toggleShuffle() }
            let title = WidgetStore.playlists.first { $0.id == id }?.title ?? ""
            AppServices.nav.play(.playlist(PlaylistSummary(playlistId: id, title: title, subtitle: nil, thumbnailUrl: nil)))
            shouldPlay = true
        }
        // iPhone はボタンの処理が終わるとアプリをまた眠らせる。曲は読み込み終えてから鳴るので、
        // 鳴り始めるまで（長くて30秒。裏では読み込みが遅く、1曲に15秒ほどかかることがある）処理を終えずに待つ（待たないと、読み込みの途中で止まって鳴らない）
        guard shouldPlay, !player.isRemote else { return }
        let deadline = Date().addingTimeInterval(30)
        while !player.isPlaying, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(100))
        }
        AppPaths.log("widget command playing=\(player.isPlaying)")
    }

    private func tick() {
        guard let player = AppServices.player else { return }
        let now = player.current.map { song in
            let next = player.isRemote || player.index + 1 >= player.queue.count ? nil : player.queue[player.index + 1]
            return WidgetNowPlaying(videoId: song.videoId, title: song.title, artist: song.artistsText, playing: player.isPlaying,
                                    device: player.isRemote ? player.remoteDevice : nil, nextTitle: next?.title, nextArtist: next?.artistsText,
                                    liked: LikeButton.available(player) && AppServices.client.isLoggedIn ? player.like == .like : nil)
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
            for name in old where name.hasPrefix("artwork-") && name != WidgetStore.artworkName(videoId) {
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
