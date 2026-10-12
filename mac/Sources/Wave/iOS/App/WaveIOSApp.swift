#if os(iOS)
import AVFoundation
import SwiftUI
import WaveCore

/// iPhone 版。画面の並びと機能は Android 版（androidApp/src/phone）と同じにし、見た目は iOS の部品（タブバー・Liquid Glass）で作る。
@main
struct WaveIOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // 画面を消しても、ほかのアプリの音と重ねずに鳴らし続ける（Info.plist の UIBackgroundModes audio と組み）
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
        AppServices.initialize()
        // ほかの端末の再生を映している間も、ロック画面に操作を出し、裏でつながりを保つ
        RemoteKeepAlive.shared.start()
        #if WAVE_DEBUG
            IOSDebugCommands.start()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { _, phase in
            // 裏へ回ったら、キューと位置を書いておく（そのまま終了されても次の起動で戻す）
            if phase == .background { AppServices.player.saveQueue() }
        }
    }
}
#endif
