#if os(iOS)
import SwiftUI
import WaveCore

/// Claude のタブ（仮。Android の AiChatSheet と同じにする）。
struct AiTabView: View {
    var body: some View { Text("Claude").navigationTitle("Claude") }
}
#endif
