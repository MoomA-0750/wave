#if os(iOS)
import SwiftUI
import WaveCore

/// iPhone 版。画面の並びと機能は Android 版（androidApp/src/phone）と同じにする。
@main
struct WaveIOSApp: App {
    init() {
        AppServices.initialize()
    }

    var body: some Scene {
        WindowGroup {
            Text("wave")
        }
    }
}
#endif
