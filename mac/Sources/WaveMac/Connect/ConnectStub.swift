// 仮置き: 端末の連携のコア（WaveCore/Connect）を取り込むまで、画面を組めるようにする。
import SwiftUI
import WaveCore

enum ControlAction { case play, pause, next, previous, seek, playHere }

@MainActor @Observable
final class ConnectService {
    var configured: Bool { false }
    init(player: PlayerService, relayUrl: String?, relayHost: String?) {}
}

struct DevicesPageView: View {
    var body: some View { Text(L.t("端末の連携", "Devices")).font(.system(size: 48, weight: .bold)).padding(40) }
}

struct DevicesPopover: View {
    @Binding var isOpen: Bool
    var body: some View { Text("").frame(width: 320) }
}
