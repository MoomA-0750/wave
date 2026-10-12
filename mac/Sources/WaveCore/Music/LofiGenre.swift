import Foundation

/// 気分とジャンルに、アプリで足す Lo-Fi のタイル（日本の YouTube Music には無い）。
/// 押すと YouTube のジャンルのページの代わりに、ライブ配信と lofi のプレイリストを並べたページを組む。
/// Android・Windows も同じ値・同じ並び（Kotlin の LofiGenre と同じ）。
public enum LofiGenre {
    /// タイルの params の代わりの印（YouTube の params とぶつからない形）。
    public static let params = "wave:lofi"
    public static let title = "Lo-Fi"
    /// タイルの色（ARGB）。落ち着いた紫。
    public static let color: Int64 = 0xFF8E_7CC3
    public static let liveQuery = "lofi hip hop radio"
    public static let playlistQuery = "lofi"
    public static let liveTitle = "ライブ配信"
    public static let featuredTitle = "YouTube Music のプレイリスト"
    public static let communityTitle = "みんなのプレイリスト"
    public static let maxItems = 12

    public static var category: MoodCategory { MoodCategory(title: title, params: params, color: color) }

    /// ジャンルの格子の先頭に足す。ジャンルの格子は見出しで探し（「ジャンル」「Genres」）、見つからなければ最後の格子にする。
    /// 格子が無ければそのまま返す。二度足さない。
    public static func addTo(_ sections: [MoodSection]) -> [MoodSection] {
        if sections.isEmpty || sections.contains(where: { $0.categories.contains { $0.params == params } }) { return sections }
        let index = sections.firstIndex { $0.title.range(of: "ジャンル|genre", options: [.regularExpression, .caseInsensitive]) != nil } ?? sections.count - 1
        var result = sections
        result[index].categories.insert(category, at: 0)
        return result
    }

    /// YouTube Music のおすすめのプレイリストのうち、lofi らしいもの。1つも無ければ上から順に。
    public static func featured(_ playlists: [PlaylistSummary]) -> [PlaylistSummary] {
        let lofi = playlists.filter { $0.title.range(of: "lo-?fi|ローファイ|jazzhop|chill|beats", options: [.regularExpression, .caseInsensitive]) != nil }
        return Array((lofi.isEmpty ? playlists : lofi).prefix(maxItems))
    }

    static func shelf(_ title: String, _ items: [HomeItem]) -> HomeShelf? {
        items.isEmpty ? nil : HomeShelf(title: title, strapline: nil, items: items)
    }
}
