import SwiftUI

/// 「新しいエピソード」の絵（画像の無い自動のプレイリスト）。
struct NewEpisodesCover: View {
    var size: CGFloat
    var radius: CGFloat = 4

    var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(LinearGradient(colors: [Color(red: 0.0, green: 0.45, blue: 0.4), Color(red: 0.2, green: 0.75, blue: 0.6)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: size * 0.4)).foregroundStyle(.white))
    }
}
