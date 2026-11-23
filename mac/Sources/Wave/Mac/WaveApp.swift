#if os(macOS)
import AppKit
import SwiftUI
import WaveCore

@main
struct WaveApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    init() {
        AppServices.initialize()
    }

    var body: some Scene {
        Window("wave", id: "main") {
            MainView()
                .frame(minWidth: 900, minHeight: 600)
        }
        .defaultSize(width: 1480, height: 940)
        .windowToolbarStyle(.unified(showsTitle: false))
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandMenu(L.t("再生", "Playback")) {
                // スペース・⌘←/→ は主画面で扱う（文字の入力中は除く）。ここはメニューから選ぶ分
                Button(L.t("再生・一時停止", "Play/Pause")) { AppServices.player.togglePlay() }
                Button(L.t("次へ", "Next")) { AppServices.player.next() }
                Button(L.t("前へ", "Previous")) { AppServices.player.previous() }
                Divider()
                Button(L.t("シャッフル", "Shuffle")) { AppServices.player.toggleShuffle() }
                Button(L.t("リピート", "Repeat")) { AppServices.player.cycleRepeat() }
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // メニューバーに再生中の曲を出す
        MainActor.assumeIsolated {
            MenuBarController.shared.start()
            // ウィジェットに今の曲・プレイリストを渡し、そのボタンの頼みを受ける
            WidgetSync.shared.start()
        }
    }

    /// ウィジェットから開いたとき（wave://nowplaying・wave://add-to-playlist）。
    func application(_ application: NSApplication, open urls: [URL]) {
        MainActor.assumeIsolated {
            for url in urls where url.scheme == "wave" {
                MenuBarController.showMainWindow()
                if url.host == "add-to-playlist", let song = AppServices.player.current, !Feed.isExternal(song.videoId) { Dialogs.shared.addToPlaylist(song) }
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        // 閉じるときに、キューと位置を書いておく（次の起動で戻す）
        MainActor.assumeIsolated { AppServices.player.saveQueue() }
    }

    /// 設定「窓を閉じても再生を続ける」がオンなら、最後の窓を閉じてもアプリを終了しない（Dock・メニューバーの小窓から戻せる）。
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        MainActor.assumeIsolated { !AppServices.settings.keepPlayingWhenClosed }
    }

    /// Dock のアイコンを押したとき、窓が無ければ主画面を出し直す（SwiftUI の既定の再オープンに任せる）。
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { MainActor.assumeIsolated { MenuBarController.showMainWindow() } }
        return true
    }
}
#endif
