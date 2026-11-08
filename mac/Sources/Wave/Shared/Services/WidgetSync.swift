import Foundation
import WaveCore
import WidgetKit
#if os(iOS)
    import ActivityKit
    import UIKit
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
        WidgetBridge.search = { query in await WidgetSync.search(query) }
        // ウィジェットの文言を画面の言語に合わせる
        WidgetStore.write(L.ja ? "ja" : "en", "language.json")
        #if os(iOS)
            // 前に起動したときの「再生中」が残っていれば、動いているもの1つを引き継ぎ、ほかは消す
            // （消さないと、古い曲のままのものがロック画面に並んで残る）
            let activities = Activity<NowPlayingAttributes>.activities
            activity = activities.first { $0.activityState == .active }
            for old in activities where old.id != activity?.id {
                Task { await old.end(nil, dismissalPolicy: .immediate) }
            }
        #endif
        #if os(iOS)
            // 裏では始められないので、前に出たときに始め直す（裏で失敗したまま、曲が変わるまで出ないことがあった）
            NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    let sync = WidgetSync.shared
                    if let shown = sync.shown, sync.activity?.activityState != .active { sync.updateActivity(shown) }
                }
            }
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

    /// ショートカットの「選んだものを再生」の検索結果（曲・ライブ・アルバム・プレイリストを少しずつ。検索の順）。
    static func search(_ query: String) async -> [WidgetItem] {
        guard let music = AppServices.music else { return [] }
        async let songsResult = try? music.searchSongs(query)
        async let liveResult = try? music.searchLive(query)
        async let albumsResult = try? music.searchAlbums(query)
        async let playlistsResult = try? music.searchPlaylists(query)
        let songs = await songsResult?.value?.songs ?? []
        let lives = await liveResult?.value?.songs.filter(\.isLive) ?? []
        let albums = await albumsResult?.value?.items ?? []
        let playlists = await playlistsResult?.value?.items ?? []
        let thumb = { (url: String?) in Ui.resize(url, 240)?.absoluteString }
        let songItems = (songs.prefix(8) + lives.prefix(5)).map { song in
            WidgetItem(kind: song.isLive ? .live : .song, id: song.videoId, title: song.title, subtitle: song.artistsText, thumbnailUrl: thumb(song.thumbnailUrl))
        }
        let albumItems = albums.prefix(5).map { WidgetItem(kind: .album, id: $0.browseId, title: $0.title, subtitle: $0.subtitle, thumbnailUrl: thumb($0.thumbnailUrl)) }
        let playlistItems = playlists.prefix(5).map {
            WidgetItem(kind: .playlist, id: $0.playlistId, title: $0.title, subtitle: $0.subtitle, thumbnailUrl: thumb($0.thumbnailUrl))
        }
        var seen = Set<String>()
        return (songItems + albumItems + playlistItems).filter { seen.insert("\($0.kind.rawValue):\($0.id)").inserted }
    }

    /// ショートカットの「リンクで再生」。動画は next の先頭の曲で題・アーティスト・ライブかを調べてから鳴らし（曲の後ろはラジオ）、
    /// プレイリスト・アルバムは読めることを確かめてから「選んだものを再生」と同じく鳴らす。読み込めなければ誤りを返す。
    /// 同じものが鳴っている最中なら何もしない（止めない）。ほかの端末を映している間はその端末で鳴らす。
    static func playLink(_ link: YouTubeLink, shuffle: Bool) async throws {
        guard let player = AppServices.player, let music = AppServices.music else { throw PlayLinkError.notReady }
        AppPaths.log("play link \(link)")
        let item: WidgetItem
        switch link {
        case .video(let id):
            let songs = (try? await music.upNext(videoId: id, playlistId: nil))?.value?.songs ?? []
            // 配信中のライブは next の行の「ライブ」のバッジで分かる（MusicParsers.playlistPanelSong）
            guard let song = songs.first(where: { $0.videoId == id }) else { throw PlayLinkError.videoNotFound }
            item = WidgetItem(kind: song.isLive ? .live : .song, id: id, title: song.title, subtitle: song.artistsText, thumbnailUrl: song.thumbnailUrl)
            if item.isCurrent(shared.shown), player.isPlaying { return }
            if item.isCurrent(shared.shown) {
                player.togglePlay()
            } else {
                player.playSong(song)
            }
            await waitUntilPlaying(player)
            return
        case .album(let id):
            guard let album = (try? await music.album(id))?.value, !album.songs.isEmpty else { throw PlayLinkError.albumNotFound }
            item = WidgetItem(kind: .album, id: id, title: album.title, subtitle: album.subtitle, thumbnailUrl: album.thumbnailUrl)
        case .playlist(let id):
            if let page = (try? await music.playlist(id))?.value, !page.songs.isEmpty {
                item = WidgetItem(kind: .playlist, id: id, title: page.title ?? "", subtitle: page.subtitle, thumbnailUrl: page.thumbnailUrl)
            } else if id.hasPrefix("RD"), let songs = (try? await music.upNext(videoId: nil, playlistId: id))?.value?.songs, !songs.isEmpty {
                // ミックス（RD…）はプレイリストのページとして読めないことがあるので、next で曲を取って流す
                if player.sourceId == id, player.isPlaying { return }
                player.playList(songs, start: 0, source: id, shuffleOverride: shuffle ? true : nil)
                await waitUntilPlaying(player)
                return
            } else {
                throw PlayLinkError.playlistNotFound
            }
        }
        if item.isCurrent(shared.shown), player.isPlaying, !shuffle { return }
        await handle(.playItem(item, shuffle: shuffle))
        // プレイリスト・アルバムは読み込んでから鳴らし始めるので、キューが替わってから鳴り始めるまで待つ（前の曲が鳴ったままで待ち終えないように）
        let deadline = Date().addingTimeInterval(30)
        while player.sourceId != item.id, !player.isRemote, Date() < deadline {
            try? await Task.sleep(for: .milliseconds(100))
        }
        await waitUntilPlaying(player)
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
        guard shouldPlay else { return }
        await waitUntilPlaying(player)
    }

    /// 鳴り始めるまで待つ（ほかの端末で鳴らすときは待たない）。
    private static func waitUntilPlaying(_ player: PlayerService) async {
        guard !player.isRemote else { return }
        let deadline = Date().addingTimeInterval(30)
        // 前の曲が鳴ったままの間に読み込み始めることがあるので、読み込み中も待つ
        while !player.isPlaying || player.isLoading, Date() < deadline {
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
                                    upNext: player.queue.indices.dropFirst(player.index + 1).prefix(8).map { i in
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
            // すぐ知らせる（ジャケットを待たない）。今の曲か次の曲が替わったら、ジャケットを書いてからもう一度知らせる
            let artChanged = now?.videoId != shown?.videoId || now?.upNext?.map(\.videoId) != shown?.upNext?.map(\.videoId)
            shown = now
            publish(now)
            if let now, artChanged {
                let upNext = (now.upNext ?? []).map { ($0.videoId, Ui.resize(player.queue[$0.index].thumbnailUrl, 60)) }
                Task { await saveArtwork(now.videoId, current: Ui.resize(player.current?.thumbnailUrl, 240), upNext: upNext) }
            }
        }
        #if os(iOS)
            // 位置の棒: シークしたとき・止めた位置が変わったときに知らせ直す（鳴っている間の進みは OS に任せる）
            if let shown, Self.moved(activityTiming(player), shownTiming) { updateActivity(shown) }
        #endif
        syncPlaylists()
    }

    /// ジャケット（今の曲と次に流れる曲）を置き場に書いて、知らせ直す（ウィジェットとロック画面は通信せずに読む）。
    /// 今の曲のジャケットを書いたらすぐ知らせ、次に流れる曲の分（最大8枚）は後から書く。
    private func saveArtwork(_ videoId: String, current: URL?, upNext: [(videoId: String, url: URL?)]) async {
        let keep = Set(([videoId] + upNext.map(\.videoId)).map(WidgetStore.artworkName))
        if let dir = WidgetStore.container, let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for name in old where name.hasPrefix("artwork-") && !keep.contains(name) {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
            }
        }
        await saveImage(videoId, current)
        guard shown?.videoId == videoId else { return }
        #if os(iOS)
            artworkReady = videoId
        #endif
        publish(shown)
        for image in upNext { await saveImage(image.videoId, image.url) }
        guard shown?.videoId == videoId else { return }
        publish(shown)
    }

    private func saveImage(_ videoId: String, _ url: URL?) async {
        guard let url, url.scheme == "https", let file = WidgetStore.artworkFile(videoId), !FileManager.default.fileExists(atPath: file.path),
              let (data, _) = try? await ImageLoader.session.data(from: url) else { return }
        try? data.write(to: file, options: .atomic)
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
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetStore.queueKind)
        #if os(iOS)
            updateActivity(now)
        #endif
    }

    // MARK: ロック画面の再生中

    #if os(iOS)
    /// 位置の棒の時刻。鳴っている間は曲の頭と終わりの時刻（秒に丸める）、止めている間は位置の割合。
    struct ActivityTiming: Equatable {
        var startedAt: Date?
        var endsAt: Date?
        var fraction: Double?
    }

    private var shownTiming: ActivityTiming?
    private var sentState: NowPlayingAttributes.ContentState?
    private var artworkReady: String?

    private func activityTiming(_ player: PlayerService) -> ActivityTiming? {
        guard let song = player.current, !song.isLive, player.duration > 0 else { return nil }
        if !player.isPlaying { return ActivityTiming(fraction: player.position / player.duration) }
        let start = Date().addingTimeInterval(-player.position)
        return ActivityTiming(startedAt: start, endsAt: start.addingTimeInterval(player.duration))
    }

    /// 知らせ直すほど位置が動いたか（位置の読み取りのぶれで毎秒送らないよう、2秒・1% までは同じと見る）。
    private static func moved(_ a: ActivityTiming?, _ b: ActivityTiming?) -> Bool {
        guard let a, let b else { return (a == nil) != (b == nil) }
        switch (a.startedAt, b.startedAt, a.endsAt, b.endsAt) {
        case let (sa?, sb?, ea?, eb?): return abs(sa.timeIntervalSince(sb)) > 2 || abs(ea.timeIntervalSince(eb)) > 2
        case (nil, nil, _, _): return abs((a.fraction ?? 0) - (b.fraction ?? 0)) > 0.01
        default: return true
        }
    }

    private func updateActivity(_ now: WidgetNowPlaying?) {
        guard let now else {
            if let activity {
                self.activity = nil
                sentState = nil
                Task { await activity.end(nil, dismissalPolicy: .immediate) }
            }
            return
        }
        let timing = AppServices.player.flatMap(activityTiming)
        shownTiming = timing
        let file = WidgetStore.artworkFile(now.videoId)
        let artwork = artworkReady == now.videoId || file.map { FileManager.default.fileExists(atPath: $0.path) } == true
        let state = NowPlayingAttributes.ContentState(videoId: now.videoId, title: now.title, artist: now.artist, playing: now.playing,
                                                      device: now.device, nextTitle: now.nextTitle,
                                                      startedAt: timing?.startedAt, endsAt: timing?.endsAt, fraction: timing?.fraction, artwork: artwork)
        // 同じ中身は送らない（位置の棒の確かめで毎秒呼ばれる）
        guard state != sentState || activity?.activityState != .active else { return }
        let content = ActivityContent(state: state, staleDate: nil)
        if let activity, activity.activityState == .active {
            sentState = state
            Task { await activity.update(content) }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            // 始められるのは画面が前にあるときだけ。裏で失敗したら、次に変わったときにまた試す
            activity = try Activity.request(attributes: NowPlayingAttributes(), content: content)
            sentState = state
            AppPaths.log("live activity start")
        } catch {
            AppPaths.log("live activity failed state=\(activity.map { "\($0.activityState)" } ?? "none") error=\(error)")
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
        // Siri の「wave で〈プレイリスト〉を再生」が知るプレイリストを読み直させる
        WaveShortcuts.updateAppShortcutParameters()
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
