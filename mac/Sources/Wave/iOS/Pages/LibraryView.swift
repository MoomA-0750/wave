#if os(iOS)
import SwiftUI
import WaveCore

/// ライブラリのタブ（仮。Android の LibraryTabScreen と同じにする）。
struct LibraryView: View {
    var body: some View { Text(L.t("ライブラリ", "Library")).navigationTitle(L.t("ライブラリ", "Library")) }
}

/// 再生履歴（仮）。
struct HistoryView: View {
    var body: some View { Text(L.t("再生履歴", "History")) }
}
#endif
