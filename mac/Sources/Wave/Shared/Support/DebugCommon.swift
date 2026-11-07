#if WAVE_DEBUG
import Foundation
import WaveCore

/// 開発用（WAVE_DEBUG を付けて組んだときだけ）: Mac の DebugCommands・iPhone の IOSDebugCommands で同じところ。
/// データの場所の debug-command.txt に書いた命令（1行に1命令）を読み、読んだらファイルを消す。命令の中身は Mac・iPhone で同じものをここで行う。
@MainActor
enum DebugCommon {
    private static let fileURL = AppPaths.file("debug-command.txt")
    private static var timer: Timer?

    /// 命令のファイルを見張り、1行ずつ run に渡す。
    static func start(_ run: @escaping @MainActor (String) async -> Void) {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
            MainActor.assumeIsolated {
                guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
                try? FileManager.default.removeItem(at: fileURL)
                let lines = text.split(separator: "\n").map(String.init).filter { !$0.isEmpty }
                Task {
                    for line in lines {
                        AppPaths.log("debug command=" + (line.split(separator: " ").first.map(String.init) ?? ""))
                        await run(line)
                    }
                }
            }
        }
    }

    /// 命令と引数に分ける。
    static func split(_ line: String) -> (command: String, arg: String) {
        let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
        return (parts.first ?? "", parts.count > 1 ? parts[1] : "")
    }

    static func firstSong(_ query: String) async -> Song? {
        (try? await AppServices.music.searchSongs(query))?.value?.songs.first
    }

    /// Mac・iPhone で同じ命令を行う（それぞれの命令に無いもの）。知らない命令なら記録に書く。
    static func run(_ command: String, _ arg: String) async {
        let player = AppServices.player!
        let nav = AppServices.nav!
        switch command {
        case "wait": try? await Task.sleep(for: .milliseconds(Int(arg) ?? 0))
        case "settings": nav.settings()
        case "original-titles":
            // 設定の「曲名を元の表記で」を切り替える（on|off）
            AppServices.settings.originalTitles = arg == "on"
            AppServices.settings.save()
            AppServices.client.originalTitles = arg == "on"
            AppPaths.log("debug original-titles \(AppServices.client.originalTitles)")
        case "search-names":
            // 曲の検索の先頭5曲の曲名・アーティスト名（元の表記になっているかを見る）
            let songs = (try? await AppServices.music.searchSongs(arg))?.value?.songs.prefix(5) ?? []
            AppPaths.log("debug search-names " + songs.map { "\($0.title) / \($0.artistsText)" }.joined(separator: " | "))
        case "devices": nav.devices()
        case "history": nav.history()
        case "liked": nav.likedSongs()
        case "album": nav.album(arg)
        case "artist": nav.artist(arg)
        case "podcast": nav.podcast(PodcastSummary(browseId: arg, title: arg, author: nil, thumbnailUrl: nil))
        case "playlist": nav.playlist(PlaylistSummary(playlistId: arg, title: arg, subtitle: nil, thumbnailUrl: nil))
        // RSS のポッドキャスト（ADR-0012）: 目録で探してフィードを読む・最新のエピソードを流す・聴いたところ・登録・番組のページ
        case "feed": await FeedDebug.run(arg, play: false)
        case "feed-play": await FeedDebug.run(arg, play: true)
        case "feed-state": FeedDebug.state()
        case "feed-subscribe": await FeedDebug.toggleSubscription(arg)
        case "feed-open": await FeedDebug.open(arg)
        case "song-card":
            // 検索の先頭の曲を、棚のカードを押したのと同じ開き方で開く
            if let song = await firstSong(arg) { nav.openCard(.song(song)) }
            AppPaths.log("debug song-card route=\(nav.current.route) playing=\(player.current != nil)")
        case "search-play":
            if let songs = (try? await AppServices.music.searchSongs(arg))?.value?.songs, !songs.isEmpty { player.playList(Array(songs.prefix(5))) }
        case "song-play": if let song = await firstSong(arg) { player.playSong(song) }
        case "queue-add": if let song = await firstSong(arg) { player.addToQueue(song) }
        case "queue-next": if let song = await firstSong(arg) { player.playNext(song) }
        case "queue-dump":
            let from = max(0, player.index)
            let shown = (from..<min(player.queue.count, from + 8)).map { (player.isUserAdded($0) ? "U:" : "") + player.queue[$0].title }
            AppPaths.log("debug queue index=\(player.index) count=\(player.queue.count) " + shown.joined(separator: " | "))
        // キューの並べ替え・外す（ほかの端末を映している間は、その端末へ queue_edit で送る）。位置はキュー全体の番号
        case "queue-move":
            let parts = arg.split(separator: " ").compactMap { Int($0) }
            if parts.count == 2 { player.moveUpcoming(parts[0], parts[1]) }
        case "queue-remove": if let position = Int(arg) { player.removeAt(position) }
        // 「次に流れる曲」の消去（元の流れの曲を外して流れを止める。ほかの端末で鳴っている間は queue_edit の clear_flow で送る）
        case "queue-clear-flow": player.clearFlow()
        case "shuffle": player.toggleShuffle()
        case "repeat": player.cycleRepeat()
        case "next": player.next()
        case "previous": player.previous()
        case "seek": player.seek(Double(arg) ?? 0)
        case "pause": if player.isPlaying { player.togglePlay() }
        case "play": if !player.isPlaying { player.togglePlay() }
        case "like": LikeButton.press(player)
        // 連携でほかの端末を映していたら、この端末で鳴らす
        case "connect-transfer": AppServices.connect.transfer()
        case "releases-check": await AppServices.releases.check()
        // ダウンロード（ADR-0016）: 画面を開く・プレイリスト（liked は高く評価した曲。2つ目の引数で先頭の曲数に絞る）・アルバムを取る・
        // 検索の先頭の曲を取る・状態を記録に書く・一覧の読み直しとスマートダウンロードの選び直し・通信できないことにする（on・off）・すべて消す
        case "downloads": nav.downloads()
        case "dl-playlist", "dl-album":
            let album = command == "dl-album"
            let parts = arg.split(separator: " ").map(String.init)
            let id = parts.first == "liked" ? MusicRepository.likedSongs : parts.first ?? ""
            let limit = parts.count > 1 ? Int(parts[1]) : nil
            let songs = album ? (try? await AppServices.music.album(id))?.value?.songs : (try? await AppServices.music.fullPlaylist(id))?.value?.songs
            if let songs, !songs.isEmpty {
                AppServices.downloads.downloadCollection(album ? .album : id == MusicRepository.likedSongs ? .liked : .playlist, id: id, title: id == MusicRepository.likedSongs ? L.t("高く評価した曲", "Liked Songs") : id, thumbnailUrl: nil,
                                                         songs: Array(songs.prefix(limit ?? songs.count)))
            }
            AppPaths.log("debug \(command) songs=\(songs?.count ?? -1)")
        case "dl-song": if let song = await firstSong(arg) { AppServices.downloads.download(song) }
        // 目録で探した最初の番組の、新しい10件のうち一番短い RSS のエピソードを取る
        case "dl-feed":
            let feeds = AppServices.feeds!
            if let first = await feeds.client.search(arg)?.first, let url = Feed.feedUrlOf(first.browseId), case .ok(let show) = await feeds.client.show(url),
               let episode = show.toPage(saved: false).episodes.prefix(10).min(by: { ($0.song.durationSeconds ?? .max) < ($1.song.durationSeconds ?? .max) })
            {
                AppServices.downloads.download(episode.song, episode: true)
                AppPaths.log("debug dl-feed duration=\(episode.song.durationSeconds ?? -1)")
            } else {
                AppPaths.log("debug dl-feed failed")
            }
        case "dl-state":
            let d = AppServices.downloads!
            let collections = d.manifest.collections.map { "\($0.kind.rawValue):\(d.state($0.id))" }.joined(separator: ",")
            let smart = SmartCategory.allCases.map { "\($0.rawValue)=\(d.manifest.smartIds($0).count)" }.joined(separator: ",")
            AppPaths.log("debug dl-state wanted=\(d.manifest.wanted.count) completed=\(d.completed.count) pending=\(d.pending.count) active=\(d.active != nil) " +
                "progress=\(Int(d.activeProgress * 100)) bytes=\(d.totalBytes) connected=\(d.connected) metered=\(d.metered) allowed=\(d.allowed) " +
                "offline=\(d.offline) collections=[\(collections)] smart=[\(smart)]")
        case "dl-refresh": await AppServices.downloads.refresh(collections: true)
        case "dl-smart":
            if arg == "off" {
                AppServices.downloads.setSmart(false)
            } else {
                if let n = Int(arg) { AppServices.downloads.settings.smartDownloadCount = n }
                AppServices.downloads.settings.smartDownloads = true
                await AppServices.downloads.refreshSmart(force: true)
            }
        case "dl-offline": AppServices.downloads.debugOffline = arg != "off"
        case "dl-policy": AppServices.downloads.setPolicy(arg == "any" ? .any : .unmetered)
        case "dl-delete-all": AppServices.downloads.deleteAll()
        // 取った曲だけを並べて鳴らす（引数は曲数）。取っていない曲を混ぜるときは dl-play-mixed
        case "dl-play", "dl-play-mixed":
            let d = AppServices.downloads!
            let ids = command == "dl-play" ? d.manifest.wanted.filter { d.isDownloaded($0) } : d.manifest.wanted
            player.playList(ids.prefix(Int(arg) ?? 5).compactMap { d.song($0) })
        default: AppPaths.log("debug unknown command")
        }
    }
}
#endif
