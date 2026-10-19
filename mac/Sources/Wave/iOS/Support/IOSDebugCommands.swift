#if os(iOS) && WAVE_DEBUG
import AVFoundation
import Foundation
import UIKit
import WaveCore

/// 開発用（WAVE_DEBUG を付けて組んだときだけ）: データの場所の debug-command.txt に書いた命令を行い、結果を記録に書く
/// （Mac の DebugCommands・Android の DebugActions と同じ）。シミュレーターの画面を外から確かめるためのもの。Mac と同じ命令は Shared の DebugCommon で行う。
@MainActor
enum IOSDebugCommands {
    static func start() { DebugCommon.start(run) }

    private static func run(_ line: String) async {
        let (command, arg) = DebugCommon.split(line)
        let player = AppServices.player!
        let nav = AppServices.nav!
        let shell = IOSShell.shared
        switch command {
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
        case "now":
            AppPaths.log("debug now title=\(player.current?.title ?? "nil") position=\(Int(player.position)) duration=\(Int(player.duration)) playing=\(player.isPlaying) loading=\(player.isLoading) like=\(String(describing: player.like)) lyrics=\(player.lyrics != nil) video=\(player.video != nil) remote=\(player.isRemote) loggedIn=\(AppServices.client.isLoggedIn) " + player.engine.debugState)
        case "route":
            AppPaths.log("debug tab=\(shell.tab) path=\(shell.paths[shell.tab]?.count ?? 0) player=\(shell.playerOpen) queue=\(shell.queueOpen)")
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
        case "video":
            // 映像: on・off（曲｜動画の切り替え）、full・exit（全画面）、pip（小窓の入り切り）
            switch arg {
            case "on", "off": shell.playerOpen = true; shell.showVideo = arg == "on"
            case "full": shell.playerOpen = true; shell.showVideo = true; shell.videoFullScreen = true
            case "exit": shell.videoFullScreen = false
            case "pip": VideoPip.shared.toggle()
            default: break
            }
            AppPaths.log("debug video show=\(shell.showVideo) full=\(shell.videoFullScreen) pip=\(VideoPip.shared.active) player=\(shell.playerOpen) \(VideoPip.shared.debugState)")
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
        default: await DebugCommon.run(command, arg)
        }
    }
}
#endif
