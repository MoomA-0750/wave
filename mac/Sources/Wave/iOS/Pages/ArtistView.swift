#if os(iOS)
import SwiftUI
import WaveCore

/// アーティストのページ（仮。Android の ArtistScreen と同じにする）。
struct ArtistView: View {
    var browseId: String
    var body: some View { Text(browseId) }
}
#endif
