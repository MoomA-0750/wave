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
        default: AppPaths.log("debug unknown command")
        }
    }
}
#endif
