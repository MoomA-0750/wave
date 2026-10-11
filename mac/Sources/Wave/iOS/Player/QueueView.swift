#if os(iOS)
import SwiftUI
import WaveCore

/// キュー（仮。Android の QueueSheet と同じにする）。
struct QueueView: View {
    var body: some View { Text(L.t("キュー", "Queue")) }
}
#endif
