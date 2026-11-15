import SwiftUI

/// 「新しいエピソード」の絵（画像の無い自動のプレイリスト）。
struct NewEpisodesCover: View {
    var size: CGFloat
    var radius: CGFloat = Kasane.Radius.sm

    var body: some View {
        RoundedRectangle(cornerRadius: radius, style: Kasane.cornerStyle)
            .fill(LinearGradient(colors: [Color(red: 0.0, green: 0.45, blue: 0.4), Color(red: 0.2, green: 0.75, blue: 0.6)], startPoint: .topLeading, endPoint: .bottomTrailing)) // apple-ui-allow: 作品の絵の色（Windows・Android 版と同じ）
            .frame(width: size, height: size)
            .overlay(Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: size * 0.4)).foregroundStyle(.white)) // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
    }
}
