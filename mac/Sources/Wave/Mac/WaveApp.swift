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
                // 窓の地は後ろが透けるすりガラス（枠はその上に Liquid Glass の板として浮かせる）
                .containerBackground(.ultraThinMaterial, for: .window)
        }
        .defaultSize(width: 1480, height: 940)
        .windowToolbarStyle(.unified(showsTitle: false))
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandMenu(L.t("再生", "Playback")) {
                // スペース・⌘←/→ は主画面で扱う（文字の入力中は除く）。ここはメニューから選ぶ分
                Button(L.t("再生/一時停止", "Play/Pause")) { AppServices.player.togglePlay() }
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
                if url.host == "add-to-playlist", let song = AppServices.player.current { Dialogs.shared.addToPlaylist(song) }
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        // 閉じるときに、キューと位置を書いておく（次の起動で戻す）
        MainActor.assumeIsolated { AppServices.player.saveQueue() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
#endif
