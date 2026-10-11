#if os(iOS)
import SwiftUI
import WaveCore

/// 検索（仮。Android の SearchScreen・SearchBrowse と同じにする）。
struct SearchView: View {
    var initialQuery: String?
    var body: some View { Text(L.t("検索", "Search")).navigationTitle(L.t("検索", "Search")) }
}
#endif
