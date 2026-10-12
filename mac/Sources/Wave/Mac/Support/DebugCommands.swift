#if os(macOS)
#if WAVE_DEBUG
import Foundation
import UserNotifications
import WaveCore

/// 開発用（WAVE_DEBUG を付けて組んだときだけ）: データの場所の debug-command.txt に書いた命令を行い、結果を記録に書く
/// （Windows の DebugCommands・Android の DebugActions と同じ）。画面の操作を外から確かめるためのもの。1行に1命令。読んだらファイルを消す。
@MainActor
enum DebugCommands {
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
        let window = MainWindowModel.shared
        switch parts[0] {
        case "wait":
            try? await Task.sleep(for: .milliseconds(Int(arg) ?? 0))
        case "home": nav.home()
        case "settings": nav.settings()
        case "login": LoginWindowController.show()
        case "login-probe": LoginProbe.run()
        case "devices": nav.devices()
        case "history": nav.history()
        case "lyrics": nav.lyrics()
        case "liked": nav.likedSongs()
        case "back": nav.goBack()
        case "album": nav.album(arg)
        case "artist": nav.artist(arg)
        case "playlist": nav.playlist(PlaylistSummary(playlistId: arg, title: arg, subtitle: nil, thumbnailUrl: nil))
        case "search":
            // 上の検索欄に打って Enter を押したのと同じ
            window.searchFor(arg)
        case "suggest":
            window.searchText = arg
            window.searchTextChanged(arg)
            try? await Task.sleep(for: .milliseconds(1500))
            AppPaths.log("debug suggest n=\(window.suggestions.count)")
        case "pane":
            switch arg {
            case "queue": window.showQueue()
            case "history": window.showQueue(history: true)
            case "ai": window.showAi()
            case "ai-toggle": window.toggleAi()
            case "close": window.setRightPane(open: false, queue: false)
            default: window.setRightPane(open: true, queue: false)
            }
            AppPaths.log("debug pane \(arg) right=\(window.showRight) view=\(window.rightView)")
        case "fullscreen": window.setFullScreen(arg == "on")
        case "video": window.showVideo = arg == "on"
        case "ai-pair":
            AppPaths.log("debug ai pair ok=\(AiChat.pair(arg))")
        case "ai":
            window.showAi()
            AppServices.ai.send(arg)
            try? await Task.sleep(for: .milliseconds(300))
            while AppServices.ai.running { try? await Task.sleep(for: .milliseconds(500)) }
            for m in AppServices.ai.messages {
                let text: String = switch m {
                case .user: "user"
                case .assistant(let t, let videoId): "assistant chars=\(t.count) stamps=\(AiText.timestamps(t).map(\.label)) synced=\(videoId != nil)"
                case .action(let t): "action " + t
                case .songs(let s): "songs n=\(s.count)"
                case .failure(let t): "failure " + t
                }
                AppPaths.log("debug ai " + text)
            }
        // Claude の会話の履歴: 一覧・新しい会話・開く（番号）・消す（番号）・最後の答えの時刻へ飛ぶ（番号）
        case "ai-history":
            let chat = AppServices.ai!
            for (i, c) in chat.history.conversations.enumerated() {
                AppPaths.log("debug ai history \(i) title_chars=\(c.title.count) messages=\(c.messages.count) current=\(c.id == chat.conversationId)")
            }
            AppPaths.log("debug ai history n=\(chat.history.conversations.count)")
        case "ai-new":
            AppServices.ai.clear()
        case "ai-open":
            let list = AppServices.ai.history.conversations
            let i = Int(arg) ?? 0
            if list.indices.contains(i) { AppServices.ai.open(list[i].id) }
            AppPaths.log("debug ai open messages=\(AppServices.ai.messages.count)")
        case "ai-delete":
            let list = AppServices.ai.history.conversations
            let i = Int(arg) ?? 0
            if list.indices.contains(i) { AppServices.ai.delete(list[i].id) }
        case "ai-seek":
            var found: (String?, AiText.Timestamp?) = (nil, nil)
            for case .assistant(let t, let videoId) in AppServices.ai.messages.reversed() {
                let stamps = AiText.timestamps(t)
                if stamps.isEmpty { continue }
                found = (videoId, stamps.indices.contains(Int(arg) ?? 0) ? stamps[Int(arg) ?? 0] : nil)
                break
            }
            AppPaths.log("debug ai seek stamp=\(found.1?.label ?? "nil") synced=\(found.0 != nil)")
            if let id = found.0, let stamp = found.1 { AiChat.seek(videoId: id, positionMs: stamp.positionMs) }
        case "membership-toggle":
            // 追加の画面で、名前の一致するプレイリストの行を押したのと同じ（入っていれば外し、無ければ足す）
            guard let song = player.current, let target = AppServices.library.editable.first(where: { $0.title == arg }) else {
                AppPaths.log("debug membership-toggle no playlist")
                break
            }
            let status = await AppServices.library.membership.check(target.playlistId, song.videoId)
            if status == .isIn { _ = await SongActions.remove(target, song) } else { await SongActions.add(target, song) }
            AppPaths.log("debug membership-toggle was=\(status) inPlaylist=\(player.inPlaylist)")
        case "song-card":
            // 検索の先頭の曲を、棚のカードを押したのと同じ開き方で開く
            if let song = await firstSong(arg) { nav.openCard(.song(song)) }
            AppPaths.log("debug song-card route=\(nav.current.route) playing=\(player.current != nil)")
        case "search-play":
            if let songs = (try? await AppServices.music.searchSongs(arg))?.value?.songs, !songs.isEmpty { player.playList(Array(songs.prefix(5))) }
        case "song-play":
            if let song = await firstSong(arg) { player.playSong(song) }
        case "artist-play":
            // アーティストのページの再生ボタンと同じ流し方（人気の曲から始め、「すべての曲」の残りを足す）
            guard let artist = (try? await AppServices.music.artist(arg))?.value else { break }
            let all = artist.allSongsPlaylistId.map { id in Task { try? await AppServices.music.fullPlaylist(id) } }
            await ArtistPageView.playArtist(player, arg, artist.topSongs, start: 0, shuffle: nil, allSongs: all)
            AppPaths.log("debug artist-play queue=\(player.queue.count) artists=\(Set(player.queue.compactMap { $0.artists.first?.name }).count)")
        case "queue-add":
            if let song = await firstSong(arg) { player.addToQueue(song) }
        case "queue-next":
            if let song = await firstSong(arg) { player.playNext(song) }
        case "queue-dump":
            let from = max(0, player.index)
            let shown = (from..<min(player.queue.count, from + 8)).map { (player.isUserAdded($0) ? "U:" : "") + player.queue[$0].title }
            AppPaths.log("debug queue index=\(player.index) count=\(player.queue.count) " + shown.joined(separator: " | "))
        case "queue-clear": player.clearUserQueue()
        case "shuffle": player.toggleShuffle()
        case "repeat": player.cycleRepeat()
        case "next": player.next()
        case "previous": player.previous()
        case "seek": player.seek(Double(arg) ?? 0)
        case "volume": player.volume = Double(arg) ?? player.volume
        case "pause": if player.isPlaying { player.togglePlay() }
        case "play": if !player.isPlaying { player.togglePlay() }
        case "like": LikeButton.press(player)
        case "set-like":
            // 検索の先頭の曲の評価を直接変える（確かめで変えた評価を戻す）。引数は「on|off 検索語」
            let p = arg.split(separator: " ", maxSplits: 1).map(String.init)
            guard p.count == 2, let song = await firstSong(p[1]) else { break }
            let r = try? await AppServices.music.setLiked(song.videoId, liked: p[0] == "on")
            if r?.isOk == true { AppServices.library.likedChanged(song.videoId, p[0] == "on") }
            AppPaths.log("debug set-like \(p[0]) ok=\(r?.isOk ?? false) liked=\(AppServices.library.isLiked(song.videoId))")
        case "dialog-close":
            Dialogs.shared.addToPlaylist = nil
            Dialogs.shared.creating = false
        case "now":
            AppPaths.log("debug now title=\(player.current?.title ?? "nil") position=\(Int(player.position)) duration=\(Int(player.duration)) playing=\(player.isPlaying) loading=\(player.isLoading) like=\(String(describing: player.like)) lyrics=\(player.lyrics != nil) video=\(player.video != nil) remote=\(player.isRemote) videoSize=\(MusicVideoPlayer.shared.player.currentItem?.presentationSize ?? .zero)")
        case "route":
            AppPaths.log("debug route=\(nav.current.route) back=\(nav.canGoBack)")
        case "playlist-delete":
            // 名前の一致する自分のプレイリストを消す（確かめで作ったものの後片付け）
            let targets = AppServices.library.editable.filter { $0.title == arg }
            if targets.isEmpty { AppPaths.log("debug playlist delete none") }
            for p in targets {
                let id = p.playlistId.hasPrefix("VL") ? String(p.playlistId.dropFirst(2)) : p.playlistId
                let r = try? await AppServices.client.post("playlist/delete", body: .obj(["playlistId": .string(id)]))
                AppPaths.log("debug playlist delete ok=\(r?.isOk ?? false)")
            }
            AppServices.library.refresh()
        case "playlist-create":
            let r = try? await AppServices.music.createPlaylist(arg)
            AppPaths.log("debug playlist create ok=\(r?.isOk ?? false)")
        case "playlists":
            AppPaths.log("debug playlists " + AppServices.library.editable.map(\.title).joined(separator: " | "))
        case "connect-accept":
            // 家の端末が出した文字列（wave-pair:…）で加わる（「はじめる」に貼って「加わる」を押したのと同じ）
            AppServices.connect.accept(arg)
        case "connect-offer":
            // 「端末を追加」を押したのと同じ。出た文字列（wave-pair:…）は加える端末へ渡すため、データの場所の debug-offer.txt に書く（2分で無効）
            let c = AppServices.connect!
            c.startOffer()
            for _ in 0..<40 where c.pairingState.offerText == nil { try? await Task.sleep(for: .milliseconds(250)) }
            if let offer = c.pairingState.offerText { try? offer.write(to: AppPaths.file("debug-offer.txt"), atomically: true, encoding: .utf8) }
            AppPaths.log("debug connect offer ok=\(c.pairingState.offerText != nil)")
        case "connect-code": AppServices.connect.enterCode(arg)
        case "connect-confirm": AppServices.connect.confirmOnB()
        case "connect-cancel": AppServices.connect.cancelPairing()
        case "connect-transfer": AppServices.connect.transfer()
        case "connect-play":
            // デバイスの一覧で、名前の一致する端末を選んだのと同じ
            if let m = AppServices.connect.members.first(where: { $0.label == arg }) { AppServices.connect.playOnDevice(m.deviceId) }
        case "connect-state":
            let c = AppServices.connect!
            let p = c.pairingState
            AppPaths.log("debug connect relay=\(c.info.relay) household=\(c.info.inHousehold) pairing=\(p.kind) code=\(p.code ?? "") peer=\(p.peerName ?? "") offer=\(p.offerText != nil) text=\(p.text ?? "")")
            for m in c.members { AppPaths.log("debug connect member label=\(m.label) self=\(m.isSelf) online=\(m.online) active=\(m.active) pc=\(m.isPc) controller=\(m.isController)") }
            if let r = c.remote { AppPaths.log("debug connect remote device=\(r.deviceLabel) title=\(r.title ?? "") playing=\(r.playing) positionMs=\(r.positionMs) online=\(r.online)") }
        case "menubar": MenuBarController.shared.toggle()
        case "notify-plain":
            let content = UNMutableNotificationContent()
            content.title = "wave"
            content.body = L.t("通知の確かめ", "Notification test")
            do { try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "debug-plain", content: content, trigger: nil)) }
            catch { AppPaths.log("debug notify-plain error=\(error)") }
            try? await Task.sleep(for: .seconds(2))
            AppPaths.log("debug notify-plain delivered=\(await UNUserNotificationCenter.current().deliveredNotifications().count)")
        case "notify-test":
            // 最近の新作の先頭を、新しく見つけたときと同じ通知で出す
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            AppPaths.log("debug notify status=\(settings.authorizationStatus.rawValue) recent=\(AppServices.releases.recent.count)")
            if let release = AppServices.releases.recent.first { await AppServices.releases.notify(release) }
            AppPaths.log("debug notify delivered=\(await UNUserNotificationCenter.current().deliveredNotifications().count)")
        case "releases-check":
            await AppServices.releases.check()
        default:
            AppPaths.log("debug unknown command")
        }
    }
}
#endif
#endif
