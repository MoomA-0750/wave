import SwiftUI

/// ライブ配信の印（赤い点と「ライブ」）。再生の操作ではシークバーの代わりに、一覧の行では長さの代わりに出す。
struct LiveBadge: View {
    var font: Font = .caption.weight(.bold)
    var dot: CGFloat = 7

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(Color.red).frame(width: dot, height: dot)
            Text(L.t("ライブ", "LIVE")).font(font).foregroundStyle(Color.red)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L.t("ライブ配信", "Live stream"))
    }
}
