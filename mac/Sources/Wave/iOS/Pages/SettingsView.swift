#if os(iOS)
import SwiftUI
import WaveCore

/// 設定（仮。Android の SettingsScreen と同じにする）。
struct SettingsView: View {
    var body: some View { Text(L.t("設定", "Settings")) }
}
#endif
