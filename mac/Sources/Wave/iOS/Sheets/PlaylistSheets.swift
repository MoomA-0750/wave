#if os(iOS)
import SwiftUI
import WaveCore

/// プレイリストに追加する画面（仮。Android の AddToPlaylistDialog と同じにする）。
struct AddToPlaylistSheet: View {
    var song: Song
    var body: some View { Text(L.t("プレイリストに追加", "Add to playlist")) }
}

/// プレイリストを作成する画面（仮）。
struct CreatePlaylistSheet: View {
    var song: Song?
    var body: some View { Text(L.t("プレイリストを作成", "Create playlist")) }
}
#endif
