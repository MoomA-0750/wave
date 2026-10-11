#if os(iOS)
import SwiftUI
import WaveCore

/// プレイリスト・アルバムのページ（仮。Android の PlaylistScreen・AlbumScreen と同じにする）。
struct CollectionView: View {
    var request: CollectionRequest
    var body: some View { Text(request.title ?? "") }
}
#endif
