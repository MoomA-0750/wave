import Foundation

/// プレイリストをサーバーで並べる params（Android の PlaylistOrder と同じ。プレイリストのページの並べ替えのメニューの値）。
/// params は `{139: {1: 向き（1 = 昇順, 2 = 降順）, 2: 並べ方}}`、カスタム（Default ordering）は `{139: {}}`。
/// 自分のプレイリストではサーバーが最後に選んだ並べ方を覚えるので、開くたびに明示して送る。
public enum PlaylistOrder {
    /// 並べ替えのメニューの「Default ordering」。
    public static let defaultOrdering = "2ggA"

    /// 追加した日。
    public static let added = 3

    /// 動画の公開日（リリース順の代わり）。
    public static let published = 1

    public static func params(_ key: Int, descending: Bool) -> String {
        Data([0xda, 0x08, 0x04, 0x08, descending ? 2 : 1, 0x10, UInt8(truncatingIfNeeded: key)]).base64EncodedString()
    }
}
