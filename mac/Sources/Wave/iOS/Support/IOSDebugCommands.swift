#if os(iOS) && WAVE_DEBUG
import AVFoundation
import Foundation
import UIKit
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
        case "search-tab":
            // 検索の結果の絞り込み（all・songs・albums・artists・live・podcasts・episodes・lyrics）を選ぶ。歌詞は読み終わるのを待って記録に書く
            let ok = SearchDebug.selectTab(arg)
            if arg == "lyrics" {
                for _ in 0..<80 where SearchDebug.lyricsLoading { try? await Task.sleep(for: .milliseconds(250)) }
                AppPaths.log("debug search-tab lyrics ok=\(ok) \(SearchDebug.lyricsSummary)")
            } else {
                AppPaths.log("debug search-tab \(arg) ok=\(ok)")
            }
        case "album": nav.album(arg)
        case "artist": nav.artist(arg)
        case "podcast": nav.podcast(PodcastSummary(browseId: arg, title: arg, author: nil, thumbnailUrl: nil))
        // RSS のポッドキャスト（ADR-0012。Mac の DebugCommands と同じ）
        case "feed": await FeedDebug.run(arg, play: false)
        case "feed-play": await FeedDebug.run(arg, play: true)
        case "feed-state": FeedDebug.state()
        case "feed-subscribe": await FeedDebug.toggleSubscription(arg)
        case "feed-open": await FeedDebug.open(arg)
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
            AppPaths.log("debug now title=\(player.current?.title ?? "nil") position=\(Int(player.position)) duration=\(Int(player.duration)) playing=\(player.isPlaying) loading=\(player.isLoading) like=\(String(describing: player.like)) lyrics=\(player.lyrics != nil) video=\(player.video != nil) remote=\(player.isRemote) loggedIn=\(AppServices.client.isLoggedIn) " + player.engine.debugState)
        case "route":
            AppPaths.log("debug tab=\(shell.tab) path=\(shell.paths[shell.tab]?.count ?? 0) player=\(shell.playerOpen) queue=\(shell.queueOpen)")
        // 連携でほかの端末を映していたら、この端末で鳴らす（Mac の DebugCommands と同じ）
        case "connect-transfer": AppServices.connect.transfer()
        case "system-volume":
            // 本体の音量を変える（再生画面を開いている間だけ効く）。変えた後の OS の音量を記録する
            if let v = Float(arg) { SystemVolume.shared.set(v) }
            try? await Task.sleep(for: .milliseconds(500))
            AppPaths.log("debug system-volume output=\(AVAudioSession.sharedInstance().outputVolume) shown=\(SystemVolume.shared.value) slider=\(SystemVolume.shared.hasSlider) route=\(AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue })")
        case "orient":
            // 画面の向きを変える（実機を回せないときに横向きを確かめる）。landscape・portrait
            if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: arg == "portrait" ? .portrait : .landscapeRight)) { error in
                    AppPaths.log("debug orient failed \(error.localizedDescription)")
                }
            }
        case "connect-state":
            for m in AppServices.connect.members {
                AppPaths.log("debug connect member label=\(m.label) id=\(m.deviceId.prefix(6)) self=\(m.isSelf) online=\(m.online) active=\(m.active) controller=\(m.isController)")
            }
        case "music-pip":
            switch arg {
            case "on", "off":
                AppServices.settings.musicPip = arg == "on"
                AppServices.settings.save()
                MusicPip.shared.settingChanged()
            case "stop": MusicPip.shared.stop()
            case "dump": try? MusicPip.shared.debugImage()?.write(to: AppPaths.file("music-pip.png"))
            default: MusicPip.shared.start()
            }
            AppPaths.log("debug music-pip setting=\(AppServices.settings.musicPip) active=\(MusicPip.shared.active)")
        case "releases-check": await AppServices.releases.check()
        default: AppPaths.log("debug unknown command")
        }
    }
}
#endif
