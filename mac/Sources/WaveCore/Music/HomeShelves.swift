import Foundation

/// ホームの棚の並び（Android の HomeShelves・HomeFeedViewModel と同じ）。
public enum HomeShelves {
    /// 新作の棚に出す数。
    public static let newReleasesCount = 20

    /// YouTube Music の新作（アルバム・シングル）の棚を、ホームの棚の先頭に置く。
    /// ホームにも新作の棚が来ていて（新作のアルバムが3枚以上重なる棚がある）同じものが2つ並ぶなら、こちらは出さない。
    public static func withNewReleases(_ releases: [AlbumSummary], title: String, shelves: [HomeShelf]) -> [HomeShelf] {
        guard !releases.isEmpty else { return shelves }
        let ours = HomeShelf(title: title, strapline: nil, items: releases.prefix(newReleasesCount).map(HomeItem.album))
        if duplicatesNewReleases(ours, in: shelves) { return shelves }
        return [ours] + shelves
    }

    /// 新作の棚と、アルバムが3枚以上重なるほかの棚があるか。
    static func duplicatesNewReleases(_ ours: HomeShelf, in shelves: [HomeShelf]) -> Bool {
        let ids = Set(ours.items.compactMap { item -> String? in if case .album(let a) = item { a.browseId } else { nil } })
        return shelves.contains { shelf in
            shelf.items.filter { item in if case .album(let a) = item { ids.contains(a.browseId) } else { false } }.count >= 3
        }
    }
}
