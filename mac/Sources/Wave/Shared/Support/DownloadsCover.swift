import SwiftUI

/// 「ダウンロード」の絵（マイライブラリの行・ダウンロードの画面の頭）。
struct DownloadsCover: View {
    var size: CGFloat
    var radius: CGFloat = 4

    var body: some View {
        // 「新しいエピソード」「高く評価した曲」の絵と同じく、決まった色の絵
        RoundedRectangle(cornerRadius: radius)
            .fill(LinearGradient(colors: [Color(red: 0.12, green: 0.36, blue: 0.85), Color(red: 0.35, green: 0.8, blue: 0.95)], startPoint: .topLeading, endPoint: .bottomTrailing))  // apple-ui-allow: 決まった色の絵
            .frame(width: size, height: size)
            .overlay(Image(systemName: "arrow.down.circle.fill").font(.system(size: size * 0.42)).foregroundStyle(.white))  // apple-ui-allow: 絵の大きさに合わせた記号
    }
}
