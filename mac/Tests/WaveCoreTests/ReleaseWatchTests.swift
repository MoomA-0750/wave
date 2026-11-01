import XCTest
@testable import WaveCore

final class ReleaseWatchTests: XCTestCase {
    private func page(_ albums: [String], _ videos: [String] = []) -> ArtistPage {
        ArtistPage(name: "A", thumbnailUrl: nil, topSongs: [], allSongsPlaylistId: nil,
                   albumShelves: [AlbumShelf(title: "Albums", albums: albums.map {
                       AlbumSummary(browseId: $0, title: "title-" + $0, subtitle: $0.hasSuffix("new") ? "Single • 2026" : "Album • 2019", thumbnailUrl: nil)
                   })],
                   videos: videos.map { Song(videoId: $0, title: "video-" + $0, artists: [], album: nil, durationSeconds: nil, thumbnailUrl: nil) })
    }

    func testTheFirstLookOnlyRemembersAndSeedsThisYear() {
        let (fresh, state) = ReleaseWatchState.empty.observe("UC1", page: page(["MPold", "MPnew"], ["v1"]), nowEpochMs: 1, year: 2026)
        XCTAssertTrue(fresh.isEmpty)
        XCTAssertEqual(["a:MPold", "a:MPnew", "v:v1"], state.seen["UC1"])
        XCTAssertEqual(state.recent.count, 1)
        XCTAssertTrue(state.recent[0].seeded)
    }

    func testOnlyNewAlbumsOfThisYearAreReportedNewestFirst() throws {
        let (_, first) = ReleaseWatchState.empty.observe("UC1", page: page(["MPold", "MPnew"], ["v1"]), nowEpochMs: 1, year: 2026)
        // 古い作品（MP2）が棚に入り直した・動画が増えただけでは知らせない
        let (fresh, second) = first.observe("UC1", page: page(["MP3new", "MP2", "MPold", "MPnew"], ["v2", "v1"]), nowEpochMs: 2, year: 2026)
        XCTAssertEqual(["MP3new"], fresh.compactMap(\.albumBrowseId))
        XCTAssertTrue(fresh.compactMap(\.videoId).isEmpty)
        XCTAssertTrue(second.seen["UC1"]!.contains("a:MP2"))
        XCTAssertTrue(second.seen["UC1"]!.contains("v:v2"))
        XCTAssertEqual(["MP3new", "MPnew"], second.recent.map(\.key))
        // 文字にして戻しても同じ
        let decoded = try XCTUnwrap(ReleaseWatchState.decode(second.encode()))
        XCTAssertEqual(second.recent, decoded.recent)
        XCTAssertEqual(second.seen["UC1"], decoded.seen["UC1"])
    }

    func testDecodesTheWindowsFormat() throws {
        // Windows 版（System.Text.Json の既定）が書いた形
        let text = #"{"Seen":{"UC1":["a:MP1","v:v1"]},"Recent":[{"ArtistId":"UC1","ArtistName":"A","Title":"t","AlbumBrowseId":"MP1","VideoId":null,"ThumbnailUrl":null,"FoundAtEpochMs":1700000000000,"Seeded":true,"Key":"MP1"}]}"#
        let state = try XCTUnwrap(ReleaseWatchState.decode(text))
        XCTAssertEqual(["a:MP1", "v:v1"], state.seen["UC1"])
        XCTAssertEqual([NewRelease(artistId: "UC1", artistName: "A", title: "t", albumBrowseId: "MP1", videoId: nil, thumbnailUrl: nil,
                                   foundAtEpochMs: 1_700_000_000_000, seeded: true)], state.recent)
        XCTAssertNil(ReleaseWatchState.decode("not json"))
    }

    func testAnEmptyPageDoesNotForget() {
        let (_, first) = ReleaseWatchState.empty.observe("UC1", page: page(["MP1"]), nowEpochMs: 1, year: nil)
        let (fresh, second) = first.observe("UC1", page: page([]), nowEpochMs: 2, year: nil)
        XCTAssertTrue(fresh.isEmpty)
        XCTAssertEqual(first, second)
    }

    func testDiversifiedTakesTwoPerArtistInTurns() {
        let releases = ["a1", "a2", "a3"].map { NewRelease(artistId: "A", artistName: "A", title: $0, albumBrowseId: $0, videoId: nil, thumbnailUrl: nil) }
            + [NewRelease(artistId: "B", artistName: "B", title: "b1", albumBrowseId: "b1", videoId: nil, thumbnailUrl: nil)]
        XCTAssertEqual(["a1", "b1", "a2"], ReleaseWatchState.diversified(releases).map(\.title))
    }

    func testReleasesThatLeaveTheArtistPageAreDropped() {
        // 同じ名前の別人の作品がまとめられ、後でページから外された（Android と同じ）
        let (_, first) = ReleaseWatchState.empty.observe("UC1", page: page(["MPold"]), nowEpochMs: 1, year: 2026)
        let (_, second) = first.observe("UC1", page: page(["MPothernew", "MPold"]), nowEpochMs: 2, year: 2026)
        XCTAssertEqual(["MPothernew"], second.recent.map(\.albumBrowseId))
        let (_, third) = second.observe("UC1", page: page(["MPold"]), nowEpochMs: 3, year: 2026)
        XCTAssertTrue(third.recent.isEmpty)
        // 今年の作品だけの棚からは、押し出されただけかもしれないので外さない。ほかのアーティストの分は残す
        XCTAssertEqual(second.recent, ReleaseWatchState.listed(second.recent, artistId: "UC1", page: page(["MP4new", "MP5new"]), year: 2026))
        XCTAssertEqual(second.recent, ReleaseWatchState.listed(second.recent, artistId: "UC2", page: page(["MPold"]), year: 2026))
    }

    func testReleasesMissingBeforeOlderReleasesOfThisYearAreDropped() {
        // 今年の作品だけの棚でも、先に見つけた新作が残っているのに後から見つけた新作が無ければ、外されたもの（Android と同じ）
        let (_, first) = ReleaseWatchState.empty.observe("UC1", page: page(["MP1new", "MPold"]), nowEpochMs: 1, year: 2026)
        let (_, second) = first.observe("UC1", page: page(["MP2new", "MP1new"]), nowEpochMs: 2, year: 2026)
        let (_, third) = second.observe("UC1", page: page(["MPothernew", "MP2new", "MP1new"]), nowEpochMs: 3, year: 2026)
        XCTAssertEqual(["MPothernew", "MP2new", "MP1new"], third.recent.map(\.albumBrowseId))
        let (_, fourth) = third.observe("UC1", page: page(["MP2new", "MP1new"]), nowEpochMs: 4, year: 2026)
        XCTAssertEqual(["MP2new", "MP1new"], fourth.recent.map(\.albumBrowseId))
        // 前から見ていた今年の作品が残っているときも外す。前に見た作品の記録が無い（表示のとき）と外さない
        let other = third.recent.filter { $0.albumBrowseId == "MPothernew" }
        XCTAssertTrue(ReleaseWatchState.listed(other, artistId: "UC1", page: page(["MP2new", "MP1new"]), year: 2026, seen: third.seen["UC1"]).isEmpty)
        XCTAssertEqual(other, ReleaseWatchState.listed(other, artistId: "UC1", page: page(["MP2new", "MP1new"]), year: 2026))
        // より新しい作品に押し出されただけなら外さない
        let (_, pushed) = third.observe("UC1", page: page(["MP5new", "MP4new"]), nowEpochMs: 5, year: 2026)
        XCTAssertEqual(["MP5new", "MP4new", "MPothernew", "MP2new", "MP1new"], pushed.recent.map(\.albumBrowseId))
    }

    func testPlaylistOrderParams() {
        // {139: {1: 2, 2: 3}} を base64 にしたもの
        XCTAssertEqual("2ggECAIQAw==", PlaylistOrder.params(PlaylistOrder.added, descending: true))
        XCTAssertEqual("2ggECAEQAQ==", PlaylistOrder.params(PlaylistOrder.published, descending: false))
    }
}
