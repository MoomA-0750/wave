#if os(iOS)
import SwiftUI
import WaveCore

/// ホーム（仮。Android の HomeScreen と同じにする）。
struct HomeView: View {
    var body: some View { Text(L.t("ホーム", "Home")).navigationTitle(L.t("ホーム", "Home")) }
}

/// 気分・ジャンルのページ（仮）。
struct MoodView: View {
    var mood: MoodCategory
    var body: some View { Text(mood.title) }
}

/// 曲の画面（仮）。
struct SongView: View {
    var song: Song
    var body: some View { Text(song.title) }
}
#endif
