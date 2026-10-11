#if os(iOS)
import SwiftUI
import WaveCore

/// 歌詞の全画面（仮。Android の LyricsPanel と同じにする）。
struct LyricsView: View {
    var body: some View { Text(L.t("歌詞", "Lyrics")) }
}
#endif
