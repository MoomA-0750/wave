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
    private var shownItems: [WidgetItem]?
    /// 再生履歴の最初のページ（起動して一度だけ読む）と、その後にこの端末で鳴らした曲・ライブ（新しい順）。ワンタップ再生の候補にする
    private var history: [Song] = []
    private var played: [Song] = []
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
        Task { await loadHistory() }
    }

    private func loadHistory() async {
        // ログインを読み終えるまで待つ（起動の直後は、まだ読めていない）
        var waited = 0
        while !(AppServices.client?.isLoggedIn ?? false), waited < 30 {
            try? await Task.sleep(for: .seconds(1))
            waited += 1
        }
        if let page = (try? await AppServices.music.history())?.value {
            history = page.sections.flatMap(\.songs)
        }
        // ライブは履歴に無いことが多いので、無ければ Lo-Fi のページと同じライブを後ろに足す（Android の選ぶ画面と同じ）
        if !history.contains(where: \.isLive), let lives = (try? await AppServices.music.searchLive(LofiGenre.liveQuery))?.value {
            history += lives.songs.filter(\.isLive)
        }
        AppPaths.log("widget history songs=\(history.count) live=\(history.filter(\.isLive).count)")
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
            // ＋のときだけ届く（✓はアプリを開いてプレイリストに追加する画面を出す）。届くまでに評価が変わっていたら何もしない
            if player.like != .like && !player.inPlaylist { player.toggleLike() }
            shouldPlay = false
        case .playQueueItem(let index, let videoId):
            // 押すまでにキューが動いていたら、今の曲より後ろで同じ曲を探す
            let queue = player.queue
            let position = index < queue.count && queue[index].videoId == videoId ? index
                : queue.indices.first { $0 > player.index && queue[$0].videoId == videoId }
            guard let position else { return }
            player.playAt(position)
            shouldPlay = true
        case .playPlaylist(let id, let shuffle):
            if player.shuffle != shuffle { player.toggleShuffle() }
            let title = WidgetStore.playlists.first { $0.id == id }?.title ?? ""
            AppServices.nav.play(.playlist(PlaylistSummary(playlistId: id, title: title, subtitle: nil, thumbnailUrl: nil)))
            shouldPlay = true
        case .playItem(let item, let shuffle):
            // 今鳴っているものなら、頭からやり直さずに一時停止・再開
            if !shuffle, item.isCurrent(WidgetSync.shared.shown) {
                player.togglePlay()
                shouldPlay = !wasPlaying
                break
            }
            switch item.kind {
            case .playlist:
                if player.shuffle != shuffle { player.toggleShuffle() }
                AppServices.nav.play(.playlist(PlaylistSummary(playlistId: item.id, title: item.title, subtitle: item.subtitle, thumbnailUrl: item.thumbnailUrl)))
            case .album:
                if player.shuffle != shuffle { player.toggleShuffle() }
                AppServices.nav.play(.album(AlbumSummary(browseId: item.id, title: item.title, subtitle: item.subtitle, thumbnailUrl: item.thumbnailUrl)))
            case .song, .live:
                let artists = (item.subtitle ?? "").isEmpty ? [] : [ArtistRef(name: item.subtitle!, browseId: nil)]
                player.playSong(Song(videoId: item.id, title: item.title, artists: artists, album: nil, durationSeconds: nil,
                                     thumbnailUrl: item.thumbnailUrl, isLive: item.kind == .live))
            }
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
                                    liked: LikeButton.available(player) && AppServices.client.isLoggedIn ? LikeButton.accent(player) : nil,
                                    upNext: player.queue.indices.dropFirst(player.index + 1).prefix(3).map { i in
                                        WidgetQueueItem(index: i, videoId: player.queue[i].videoId, title: player.queue[i].title, artist: player.queue[i].artistsText)
                                    },
                                    sourceId: player.sourceId)
        }
        if let song = player.current, song.videoId != shown?.videoId, !song.videoId.hasPrefix("ext:") {
            // 鳴らした曲・ライブを、ワンタップ再生の候補の先頭に足す
            played.removeAll { $0.videoId == song.videoId }
            played.insert(song, at: 0)
            if played.count > 50 { played.removeLast(played.count - 50) }
        }
        if now != shown {
            // 今の曲か次の曲が替わったら、ジャケットを書いてから知らせる
            let artChanged = now?.videoId != shown?.videoId || now?.upNext?.map(\.videoId) != shown?.upNext?.map(\.videoId)
            shown = now
            if let now, artChanged {
                let upNext = (now.upNext ?? []).map { ($0.videoId, Ui.resize(player.queue[$0.index].thumbnailUrl, 60)) }
                Task { await saveArtwork(now.videoId, [(now.videoId, Ui.resize(player.current?.thumbnailUrl, 240))] + upNext) }
            } else {
                publish(now)
            }
        }
        syncPlaylists()
    }

    /// ジャケット（今の曲と次に流れる曲）を置き場に書いてから知らせる（ウィジェットとロック画面は通信せずに読む）。
    private func saveArtwork(_ videoId: String, _ images: [(videoId: String, url: URL?)]) async {
        let keep = Set(images.map { WidgetStore.artworkName($0.videoId) })
        if let dir = WidgetStore.container, let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for name in old where name.hasPrefix("artwork-") && !keep.contains(name) {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
            }
        }
        for image in images {
            if let url = image.url, url.scheme == "https", let file = WidgetStore.artworkFile(image.videoId),
               !FileManager.default.fileExists(atPath: file.path), let (data, _) = try? await ImageLoader.session.data(from: url)
            {
                try? data.write(to: file, options: .atomic)
            }
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
        // プレイリストのウィジェットも、上に再生中の曲を出し、鳴っているプレイリストに印を付ける
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.playlistKind)
        // ワンタップ再生も、鳴っているものは一時停止のボタンにする
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.itemKind)
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
        // ライブラリの「最近」と同じ並び（高く評価した曲を先頭に、あとは最近再生した順。ウィジェットはこの順で並べる）
        let list = LibraryEntry.list(library, kind: "playlist", filter: "", sort: "recent", matchSubtitle: false).map { entry in
            if case .playlist(let p) = entry.item {
                WidgetPlaylist(id: p.playlistId, title: p.title, thumbnailUrl: Ui.resize(p.thumbnailUrl, 240)?.absoluteString)
            } else {
                WidgetPlaylist(id: MusicRepository.likedSongs, title: entry.title, thumbnailUrl: Ui.resize(entry.thumbnailUrl, 240)?.absoluteString)
            }
        }
        guard AppServices.client.isLoggedIn, !library.playlists.isEmpty else { return }
        syncItems(library, list)
        guard list != shownPlaylists else { return }
        shownPlaylists = list
        WidgetStore.write(list, "playlists.json")
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.playlistKind)
    }

    /// ワンタップ再生の候補（ライブラリのプレイリスト・アルバム、最近再生した曲・ライブ）。置いたウィジェットは中身を ID に持つので、ここは選ぶときの一覧だけ。
    private func syncItems(_ library: LibraryState, _ playlists: [WidgetPlaylist]) {
        let albums = LibraryEntry.list(library, kind: "album", filter: "", sort: "recent", matchSubtitle: false).compactMap { entry -> WidgetItem? in
            guard case .album(let a) = entry.item else { return nil }
            return WidgetItem(kind: .album, id: a.browseId, title: a.title, subtitle: a.subtitle, thumbnailUrl: Ui.resize(a.thumbnailUrl, 240)?.absoluteString)
        }
        var seen = Set<String>()
        let songs = (played + history).filter { !$0.videoId.hasPrefix("ext:") && seen.insert($0.videoId).inserted }.map { song in
            WidgetItem(kind: song.isLive ? .live : .song, id: song.videoId, title: song.title, subtitle: song.artistsText,
                       thumbnailUrl: Ui.resize(song.thumbnailUrl, 240)?.absoluteString)
        }
        let items = playlists.map { WidgetItem(kind: .playlist, id: $0.id, title: $0.title, subtitle: nil, thumbnailUrl: $0.thumbnailUrl) }
            + albums + songs.filter { $0.kind == .song }.prefix(50) + songs.filter { $0.kind == .live }.prefix(20)
        guard items != shownItems else { return }
        shownItems = items
        WidgetStore.write(items, "items.json")
    }
}
