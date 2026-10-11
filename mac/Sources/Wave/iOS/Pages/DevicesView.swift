#if os(iOS)
import SwiftUI
import WaveCore

/// 端末の連携の画面（仮。Android の ConnectScreen・ConnectV2Section と同じにする）。
struct DevicesView: View {
    var body: some View { Text(L.t("端末の連携", "Devices")) }
}

/// 再生中の画面から開く端末の選び（仮。Android の DevicePickerSheet と同じにする）。
struct DevicesSheet: View {
    var body: some View { Text(L.t("デバイスに接続", "Connect to a device")) }
}
#endif
