import SwiftUI
import WaveCore

/// 画面から呼ぶ小さな変換（Windows の Ui と同じ）。
enum Ui {
    /// 画像の URL を、表示する大きさに合うものにする（YouTube の画像は URL で大きさを選べる）。
    static func resize(_ url: String?, _ size: Int) -> URL? {
        guard let url, !url.isEmpty else { return nil }
        // lh3.googleusercontent.com/...=w120-h120-l90-rj の形
        let px = max(size * 2, 120)
        if let regex = try? NSRegularExpression(pattern: #"=w\d+-h\d+((?:-[a-z0-9]+)*)$"#),
            let match = regex.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)),
            let range = Range(match.range, in: url)
        {
            let suffix = Range(match.range(at: 1), in: url).map { String(url[$0]) } ?? ""
            return URL(string: url.replacingCharacters(in: range, with: "=w\(px)-h\(px)\(suffix)"))
        }
        return URL(string: url)
    }

    static func img(_ url: String?) -> URL? { resize(url, 226) }
    static func large(_ url: String?) -> URL? { resize(url, 544) }
    static func small(_ url: String?) -> URL? { resize(url, 96) }

    static func duration(_ seconds: Int?) -> String { seconds.map { time(Double($0)) } ?? "" }

    static func time(_ seconds: Double) -> String {
        let t = Int(max(0, seconds.isFinite ? seconds : 0))
        let h = t / 3600, m = (t % 3600) / 60, s = t % 60
        return h >= 1 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func kind(_ item: HomeItem) -> String {
        switch item {
        case .song: L.t("曲", "Song")
        case .album: L.t("アルバム", "Album")
        case .playlist: L.t("プレイリスト", "Playlist")
        case .artist: L.t("アーティスト", "Artist")
        }
    }

    /// 白い文字が読めるよう、明るすぎる色を暗くする（YouTube の色は淡いものがある）。
    private static func readable(_ r: Double, _ g: Double, _ b: Double) -> Color {
        let luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        let factor = luminance > 0.45 ? 0.45 / luminance : 1
        return Color(red: r * factor / 255, green: g * factor / 255, blue: b * factor / 255)
    }

    /// ARGB の数を色に（気分・ジャンルのタイル）。
    static func tile(_ argb: Int64?, _ index: Int) -> Color {
        if let v = argb { return readable(Double((v >> 16) & 0xFF), Double((v >> 8) & 0xFF), Double(v & 0xFF)) }
        let fallback: [Int64] = [0xFF8D67AB, 0xFFE8115B, 0xFF1E3264, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF477D95, 0xFFAF2896]
        let f = fallback[index % fallback.count]
        return Color(red: Double((f >> 16) & 0xFF) / 255, green: Double((f >> 8) & 0xFF) / 255, blue: Double(f & 0xFF) / 255)
    }

    /// 高く評価した曲の絵の地（Spotify の紫から緑のグラデーション）。
    static let likedGradient = LinearGradient(
        colors: [Color(red: 0x45 / 255, green: 0x0A / 255, blue: 0xF5 / 255), Color(red: 0xC4 / 255, green: 0xEF / 255, blue: 0xD9 / 255)],
        startPoint: .topLeading, endPoint: .bottomTrailing)
}
