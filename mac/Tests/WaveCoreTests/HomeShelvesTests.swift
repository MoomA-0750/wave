import XCTest
@testable import WaveCore

/// ホームの新作の棚（Android の HomeFeedViewModel と同じ並び）。
final class HomeShelvesTests: XCTestCase {
    private func album(_ id: String) -> AlbumSummary { AlbumSummary(browseId: id, title: id, subtitle: nil, thumbnailUrl: nil) }
    private func shelf(_ title: String, _ ids: [String]) -> HomeShelf { HomeShelf(title: title, strapline: nil, items: ids.map { .album(album($0)) }) }

    func testPutsNewReleasesFirstAndLimitsTo20() {
        let releases = (0..<25).map { album("r\($0)") }
        let shelves = HomeShelves.withNewReleases(releases, title: "新作", shelves: [shelf("おすすめ", ["a", "b"])])
        XCTAssertEqual(["新作", "おすすめ"], shelves.map(\.title))
        XCTAssertEqual(20, shelves[0].items.count)
        XCTAssertEqual("r0", shelves[0].items.first?.key.dropFirst(2).description)
    }

    func testNoReleasesKeepsShelves() {
        XCTAssertEqual(["おすすめ"], HomeShelves.withNewReleases([], title: "新作", shelves: [shelf("おすすめ", ["a"])]).map(\.title))
    }

    func testSkipsWhenHomeAlreadyHasTheSameReleases() {
        let releases = ["x", "y", "z", "w"].map(album)
        // 2枚の重なりなら出す、3枚重なれば出さない
        XCTAssertEqual(2, HomeShelves.withNewReleases(releases, title: "新作", shelves: [shelf("新着", ["x", "y", "q"])]).count)
        XCTAssertEqual(["新着"], HomeShelves.withNewReleases(releases, title: "新作", shelves: [shelf("新着", ["x", "y", "z"])]).map(\.title))
    }
}
