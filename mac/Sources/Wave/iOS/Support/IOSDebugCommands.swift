#if os(iOS) && WAVE_DEBUG
import Foundation
import WaveCore

/// 開発用（WAVE_DEBUG を付けて組んだときだけ）: データの場所の debug-command.txt に書いた命令を行い、結果を記録に書く
/// （Mac の DebugCommands・Android の DebugActions と同じ）。シミュレーターの画面を外から確かめるためのもの。1行に1命令。読んだらファイルを消す。
@MainActor
enum IOSDebugCommands {
    private static let fileURL = AppPaths.file("debug-command.txt")
    private static var timer: Timer?

    static func start() {
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

    private static func firstSong(_ query: String) async -> Song? {
        (try? await AppServices.music.searchSongs(query))?.value?.songs.first
    }

    private static func run(_ line: String) async {
        let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
        let arg = parts.count > 1 ? parts[1] : ""
        let player = AppServices.player!
        let nav = AppServices.nav!
        let shell = IOSShell.shared
        switch parts[0] {
        case "wait": try? await Task.sleep(for: .milliseconds(Int(arg) ?? 0))
        case "login-skip": LoginGate.shared.skipped = true
        case "tab":
            switch arg {
            case "search": shell.select(.search)
            case "ai": shell.select(.ai)
            case "library": shell.select(.library)
            default: shell.select(.home)
            }
        case "back": if !(shell.paths[shell.tab] ?? []).isEmpty { shell.paths[shell.tab]?.removeLast() }
        case "player": shell.playerOpen = arg != "close"
        case "queue": shell.playerOpen = true; shell.queueOpen = arg != "close"
        case "lyrics": shell.playerOpen = true; LyricsSheetState.shared.open = arg != "close"
        case "search": nav.search(arg)
        case "album": nav.album(arg)
        case "artist": nav.artist(arg)
        case "playlist": nav.playlist(PlaylistSummary(playlistId: arg, title: arg, subtitle: nil, thumbnailUrl: nil))
        case "liked": nav.likedSongs()
        case "history": nav.history()
        case "settings": nav.settings()
        case "devices": nav.devices()
        case "song-card": if let song = await firstSong(arg) { nav.openCard(.song(song)) }
        case "search-play":
            if let songs = (try? await AppServices.music.searchSongs(arg))?.value?.songs, !songs.isEmpty { player.playList(Array(songs.prefix(5))) }
        case "song-play": if let song = await firstSong(arg) { player.playSong(song) }
        case "queue-add": if let song = await firstSong(arg) { player.addToQueue(song) }
        case "queue-next": if let song = await firstSong(arg) { player.playNext(song) }
        case "queue-dump":
            let from = max(0, player.index)
            let shown = (from..<min(player.queue.count, from + 8)).map { (player.isUserAdded($0) ? "U:" : "") + player.queue[$0].title }
            AppPaths.log("debug queue index=\(player.index) count=\(player.queue.count) " + shown.joined(separator: " | "))
        case "shuffle": player.toggleShuffle()
        case "repeat": player.cycleRepeat()
        case "next": player.next()
        case "previous": player.previous()
        case "seek": player.seek(Double(arg) ?? 0)
        case "pause": if player.isPlaying { player.togglePlay() }
        case "play": if !player.isPlaying { player.togglePlay() }
        case "like": LikeButton.press(player)
        case "now":
            AppPaths.log("debug now title=\(player.current?.title ?? "nil") position=\(Int(player.position)) duration=\(Int(player.duration)) playing=\(player.isPlaying) loading=\(player.isLoading) like=\(String(describing: player.like)) lyrics=\(player.lyrics != nil) video=\(player.video != nil) remote=\(player.isRemote) loggedIn=\(AppServices.client.isLoggedIn)")
        case "route":
            AppPaths.log("debug tab=\(shell.tab) path=\(shell.paths[shell.tab]?.count ?? 0) player=\(shell.playerOpen) queue=\(shell.queueOpen)")
        case "releases-check": await AppServices.releases.check()
        default: AppPaths.log("debug unknown command")
        }
    }
}
#endif
