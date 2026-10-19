import Foundation

/// 画像の URL の大きさを選ぶ（YouTube の画像は URL で大きさを選べる。Android の Media3QueuePlayer.largeArtwork と同じ形を扱う）。
public enum ArtworkUrl {
    private static let sizeSuffix = #/=w\d+-h\d+((?:-[a-z0-9]+)*)$/#
    private static let squareSuffix = #/=s\d+((?:-[a-z0-9]+)*)$/#
    private static let ytimgFile = #/\/[a-z0-9_]+\.jpg.*$/#

    /// px 四方の大きさにする。googleusercontent・ggpht の `=w…-h…`（後ろの `-l90-rj` などは残す）・`=s…`・大きさの指定が無いもの。
    /// ほかの形はそのまま。
    public static func resized(_ url: String, px: Int) -> String {
        if let match = url.firstMatch(of: sizeSuffix) {
            return url.replacingCharacters(in: match.range, with: "=w\(px)-h\(px)\(match.output.1)")
        }
        let base = url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? url
        guard isGoogleImage(base) else { return url }
        if let match = base.firstMatch(of: squareSuffix) {
            return base.replacingCharacters(in: match.range, with: "=w\(px)-h\(px)")
        }
        // 大きさの指定が無い（`=` の後ろが無い）ものにも足す
        if !base.contains("=") { return base + "=w\(px)-h\(px)" }
        return url
    }

    /// 再生中・ロック画面などに出す大きい画像（既定は 544 四方）。i.ytimg の動画のサムネイルは、必ずある hqdefault.jpg にする。
    public static func large(_ url: String, px: Int = 544) -> String {
        let base = url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? url
        if base.contains("i.ytimg.com"), let match = base.firstMatch(of: ytimgFile) {
            return base.replacingCharacters(in: match.range, with: "/hqdefault.jpg")
        }
        if isGoogleImage(base) {
            // 問い合わせの部分は落とす（大きさの指定と混ざらないように）
            let stem = base.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? base
            return stem + "=w\(px)-h\(px)"
        }
        return resized(url, px: px)
    }

    private static func isGoogleImage(_ url: String) -> Bool { url.contains("googleusercontent.com") || url.contains("ggpht.com") }
}
