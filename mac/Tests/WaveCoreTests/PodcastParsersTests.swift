import Foundation
import XCTest
@testable import WaveCore

/// Android 版の PodcastParsersTest と同じ確かめ方（匿名で取得した公開データ）。
final class PodcastParsersTests: XCTestCase {
    func testPodcastSearchReadsShows() throws {
        let page = MusicParsers.searchPodcasts(Fixtures.json("search_podcasts"))
        XCTAssertEqual(8, page.items.count)
        let first = page.items[0]
        XCTAssertEqual("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.browseId)
        XCTAssertEqual("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", first.playlistId)
        XCTAssertEqual("Rebuild", first.title)
        XCTAssertEqual("Rebuild Podcast", first.author)
        XCTAssertTrue(try XCTUnwrap(first.thumbnailUrl).hasPrefix("https://"))
        XCTAssertTrue(page.items.allSatisfy { $0.browseId.hasPrefix("MPSP") })
        XCTAssertNotNil(page.continuation)
    }

    func testEpisodeSearchTakesDateAndShowNotArtist() {
        let page = MusicParsers.searchEpisodes(Fixtures.json("search_episodes"))
        XCTAssertEqual(8, page.items.count)
        let first = page.items[0]
        XCTAssertEqual("ZVIAE__9jhY", first.song.videoId)
        XCTAssertTrue(first.song.title.hasPrefix("How To Rebuild Your Character"))
        // 日付をアーティストと取り違えず、番組をアーティストの代わりにする
        XCTAssertEqual([ArtistRef(name: "Munroe Global Available Teachings", browseId: nil)], first.song.artists)
        XCTAssertEqual("2018/04/28", first.publishedText)
        XCTAssertEqual("MPSPPLiSxXTy3ZWc52InDNx3QUGeewlp3jNmRW", first.showBrowseId)
        XCTAssertEqual("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", first.song.kind)
        XCTAssertTrue(page.items.allSatisfy { ($0.showBrowseId ?? "").hasPrefix("MPSP") && $0.publishedText != nil })
        XCTAssertNotNil(page.continuation)
    }

    func testPodcastPageReadsHeaderAndEpisodes() throws {
        let page = try XCTUnwrap(MusicParsers.podcastPage(Fixtures.json("browse_podcast"), browseId: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv"))
        XCTAssertEqual("Rebuild", page.title)
        XCTAssertEqual("Rebuild Podcast", page.author)
        XCTAssertTrue((page.description ?? "").hasPrefix("ウェブ開発、プログラミング"))
        XCTAssertTrue((page.thumbnailUrl ?? "").hasPrefix("https://"))
        XCTAssertEqual("PLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", page.playlistId)
        // ログインしていない応答では、保存しているかは分からない
        XCTAssertNil(page.saved)
        XCTAssertEqual(8, page.episodes.count)
        let first = page.episodes[0]
        XCTAssertEqual("mLtB1Fr7o64", first.song.videoId)
        XCTAssertEqual("433: That Meeting Was So Jev (らんぶる)", first.song.title)
        XCTAssertEqual([ArtistRef(name: "Rebuild", browseId: nil)], first.song.artists)
        XCTAssertEqual("6 日前", first.publishedText)
        XCTAssertEqual("1 時間 37 分", first.durationText)
        XCTAssertTrue((first.description ?? "").hasPrefix("らんぶるさんをゲストに迎えて"))
        XCTAssertNil(first.progressPercent)
        XCTAssertTrue((first.song.thumbnailUrl ?? "").hasPrefix("https://"))
        XCTAssertNotNil(page.continuation)
    }

    func testPodcastContinuationReadsEpisodes() {
        let show = ArtistRef(name: "Rebuild", browseId: nil)
        let page = MusicParsers.podcastEpisodes(Fixtures.json("browse_podcast_continuation"), show: show, showBrowseId: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv")
        XCTAssertEqual(4, page.items.count)
        let first = page.items[0]
        XCTAssertEqual("h61yHZbIQvk", first.song.videoId)
        XCTAssertEqual("413: Always Fully Hyped (yuka)", first.song.title)
        XCTAssertEqual([show], first.song.artists)
        XCTAssertEqual("2025/09/08", first.publishedText)
        XCTAssertEqual("1 時間 48 分", first.durationText)
        XCTAssertNil(page.continuation)
    }

    func testLibraryPodcastsReadCardsAndRows() {
        // ログインが要るので、番組のカードの形で確かめる（番組以外のカードは除く）
        let card = """
            {"contents":{"gridRenderer":{"items":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"New episodes"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDPN","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Rebuild"}]},"subtitle":{"runs":[{"text":"Rebuild Podcast"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}}
            ]}}}
            """
        let shows = MusicParsers.libraryPodcasts(Fixtures.parse(card))
        XCTAssertEqual([PodcastSummary(browseId: "MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", title: "Rebuild", author: "Rebuild Podcast", thumbnailUrl: nil)], shows)
        XCTAssertEqual(8, MusicParsers.libraryPodcasts(Fixtures.json("search_podcasts")).count)
    }

    func testNewEpisodesPageTakesShowFromRowAndHasNoSaveButton() throws {
        // 「新しいエピソード」（VLRDPN）は番組のページと同じ形。行の補足に番組が付く
        let page = try XCTUnwrap(MusicParsers.podcastPage(Fixtures.json("browse_podcast"), browseId: MusicRepository.newEpisodesBrowse))
        XCTAssertNil(page.saved)
        XCTAssertEqual("RDPN", page.playlistId)
        let row = Fixtures.parse("""
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"New episodes"}]}}}]}}}}],
            "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicMultiRowListItemRenderer":{
            "onTap":{"watchEndpoint":{"videoId":"mLtB1Fr7o64"}},"title":{"runs":[{"text":"433"}]},
            "subtitle":{"runs":[{"text":"Rebuild","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}},{"text":" • "},{"text":"6 日前"}]}}}]}}]}}}}}
            """)
        let episode = try XCTUnwrap(MusicParsers.podcastPage(row, browseId: "VLRDPN")?.episodes.first)
        XCTAssertEqual([ArtistRef(name: "Rebuild", browseId: nil)], episode.song.artists)
        XCTAssertEqual("MPSPPLKl_9MuIE3cMl6HkKJoloUBMkYPcn-zUv", episode.showBrowseId)
        XCTAssertEqual("6 日前", episode.publishedText)
    }

    func testUnknownShapesGiveEmptyResults() {
        let empty = JSON.obj([:])
        XCTAssertTrue(MusicParsers.searchPodcasts(empty).items.isEmpty)
        XCTAssertNil(MusicParsers.searchPodcasts(empty).continuation)
        XCTAssertTrue(MusicParsers.searchEpisodes(empty).items.isEmpty)
        XCTAssertNil(MusicParsers.podcastPage(empty, browseId: "MPSPx"))
        XCTAssertTrue(MusicParsers.libraryPodcasts(empty).isEmpty)
    }
}
