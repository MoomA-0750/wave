import Foundation
import XCTest
@testable import WaveCore

/// RSS のポッドキャスト（ADR-0012）。Android の FeedParserTest と同じ fixture・同じ期待値。
final class FeedParserTests: XCTestCase {
    private func fixture(_ name: String) -> Data {
        let url = Fixtures.directory.appendingPathComponent(name)
        guard let data = try? Data(contentsOf: url) else { fatalError("fixture が読めない: \(url.path)") }
        return data
    }

    private func show() -> FeedShow {
        guard let show = FeedParser.parse(feedUrl: "https://example.com/feed.xml", data: fixture("feed_rss.xml")) else { fatalError("読めない") }
        return show
    }

    func testParsesShowAndEpisodesNewestFirst() {
        let show = show()
        XCTAssertEqual(show.title, "テストの番組 & 友だち")
        XCTAssertEqual(show.author, "wave")
        XCTAssertEqual(show.description, "説明の一行目\n二行目")
        XCTAssertEqual(show.thumbnailUrl, "https://example.com/show.jpg")
        XCTAssertEqual(show.allEpisodes.map(\.title), ["第2回", "第1回"])
        let newest = show.allEpisodes[0]
        XCTAssertEqual(newest.audioUrl, "https://example.com/ep2.mp3?x=1&y=2")
        XCTAssertEqual(newest.durationSeconds, 2525)
        XCTAssertEqual(newest.thumbnailUrl, "https://example.com/ep2.jpg")
        // 説明はエスケープされた HTML で来る（タグを落とし、文の中の実体は戻す）
        XCTAssertEqual(newest.description, "エピソードの説明 1 < 2")
        XCTAssertEqual(show.allEpisodes[1].durationSeconds, 3723)
    }

    func testEpisodesBecomeExternalSongs() {
        let page = show().toPage(saved: true) { $0.hasSuffix("ep1.mp3") ? EpisodePosition.of(positionSeconds: 600, durationSeconds: 3723, nowMs: 0) : nil }
        XCTAssertEqual(page.browseId, "rss:https://example.com/feed.xml")
        let first = page.episodes[0]
        XCTAssertEqual(first.song.videoId, "ext:https://example.com/ep2.mp3?x=1&y=2")
        XCTAssertTrue(Feed.isExternal(first.song.videoId))
        XCTAssertEqual(Feed.audioUrlOf(first.song.videoId), "https://example.com/ep2.mp3?x=1&y=2")
        XCTAssertEqual(first.publishedText, "2026/10/13")
        XCTAssertEqual(first.durationText, "42:05")
        XCTAssertEqual(first.song.artists.first?.name, "テストの番組 & 友だち")
        XCTAssertEqual(first.song.artists.count, 1)
        XCTAssertEqual(page.episodes[1].publishedText, "2026/10/05")
        XCTAssertEqual(page.episodes[1].durationText, "1:02:03")
        XCTAssertEqual(page.episodes[1].progressPercent, 16)
        XCTAssertNil(first.progressPercent)
    }

    func testParsesRfc822WithOffsets() {
        XCTAssertEqual(FeedParser.parseRfc822("Tue, 13 Oct 2026 01:00:00 GMT"), 1_791_853_200)
        XCTAssertEqual(FeedParser.parseRfc822("Tue, 13 Oct 2026 10:00:00 +0900"), 1_791_853_200)
        XCTAssertEqual(FeedParser.parseRfc822("13 Oct 2026 01:00 GMT"), 1_791_853_200)
        XCTAssertNil(FeedParser.parseRfc822("not a date"))
    }

    func testNormalizesFeedUrls() {
        XCTAssertEqual(Feed.normalizeFeedUrl(" feed://example.com/feed "), "https://example.com/feed")
        XCTAssertEqual(Feed.normalizeFeedUrl("http://example.com/feed"), "https://example.com/feed")
        XCTAssertEqual(Feed.normalizeFeedUrl("example.com/feed"), "https://example.com/feed")
        XCTAssertNil(Feed.normalizeFeedUrl("ftp://example.com/feed"))
        XCTAssertNil(Feed.normalizeFeedUrl("ポッドキャスト"))
    }

    func testFinishedNearTheEnd() {
        XCTAssertTrue(EpisodePosition.of(positionSeconds: 3700, durationSeconds: 3723, nowMs: 0).finished)
        XCTAssertEqual(EpisodePosition.of(positionSeconds: 3700, durationSeconds: 3723, nowMs: 0).resumeSeconds, 0)
        XCTAssertEqual(EpisodePosition.of(positionSeconds: 3700, durationSeconds: 3723, nowMs: 0).percent, 100)
        XCTAssertEqual(EpisodePosition.of(positionSeconds: 600, durationSeconds: 3723, nowMs: 0).resumeSeconds, 600)
    }

    func testReadsDirectoryResults() {
        let body = #"{"resultCount":2,"results":[{"collectionName":"番組A","artistName":"A","feedUrl":"https://a.example/feed","artworkUrl600":"https://a.example/a.jpg"},{"collectionName":"フィード無し"}]}"#
        let results = PodcastFeedClient.directoryResults(body)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].browseId, "rss:https://a.example/feed")
        XCTAssertEqual(results[0].title, "番組A")
    }

    func testToleratesBrokenXml() {
        let show = FeedParser.parse(feedUrl: "https://x.example/f", xml: "<rss><channel><title>T</title><item><title>a<enclosure url='https://x.example/a.mp3'/></item>")
        XCTAssertEqual(show?.title, "T")
    }

    func testLibraryKeepsNewestFirstAndRoundTrips() {
        let a = FeedSubscription(feedUrl: "https://a.example/f", title: "A")
        let b = FeedSubscription(feedUrl: "https://b.example/f", title: "B")
        let state = FeedLibraryState().subscribe(a).subscribe(b).subscribe(a)
            .remember("https://a.example/1.mp3", EpisodePosition.of(positionSeconds: 10, durationSeconds: 100, nowMs: 5))
        XCTAssertEqual(state.subscriptions.map(\.title), ["A", "B"])
        let decoded = FeedLibraryState.encode(state).flatMap(FeedLibraryState.decode)
        XCTAssertEqual(decoded, state)
        XCTAssertEqual(state.unsubscribe(a.feedUrl).subscriptions.map(\.title), ["B"])
        var renamed = a
        renamed.title = "A2"
        XCTAssertEqual(state.refresh(renamed).subscriptions.first?.title, "A2")
    }

    /// Android が書いた形（既定値の欄が無い）も読める。
    func testDecodesAndroidJson() {
        let json = #"{"subscriptions":[{"feedUrl":"https://a.example/f","title":"A","addedAtMs":3}],"positions":{"https://a.example/1.mp3":{"positionSeconds":10}}}"#
        let state = FeedLibraryState.decode(Data(json.utf8))
        XCTAssertEqual(state?.subscriptions.first?.addedAtMs, 3)
        XCTAssertEqual(state?.positions["https://a.example/1.mp3"]?.positionSeconds, 10)
        XCTAssertEqual(state?.positions["https://a.example/1.mp3"]?.finished, false)
    }

    func testDropsOldestPositionsOverTheLimit() {
        var state = FeedLibraryState()
        for i in 0...FeedLibraryState.maxPositions {
            state = state.remember("https://x.example/\(i).mp3", EpisodePosition(positionSeconds: 1, updatedAtMs: Int64(i)))
        }
        XCTAssertEqual(state.positions.count, FeedLibraryState.maxPositions)
        XCTAssertNil(state.positions["https://x.example/0.mp3"])
    }

    func testPagesThroughLongFeedsAndReversesOldestFirst() {
        let items = (1...70).map { n in "<item><title>回\(n)</title><pubDate>\(min(n, 28)) Oct 2026 00:00:00 GMT</pubDate><enclosure url=\"https://x.example/\(n).mp3\"/></item>" }.joined()
        guard let show = FeedParser.parse(feedUrl: "https://x.example/f", xml: "<rss><channel><title>連続</title>\(items)</channel></rss>") else { return XCTFail("読めない") }
        let first = show.toPage(saved: false)
        XCTAssertEqual(first.episodes.count, 30)
        // 古い順のフィードは新しい順に並べ直す
        XCTAssertEqual(first.episodes.first?.song.title, "回70")
        let second = show.more(first.continuation ?? "")
        XCTAssertEqual(second.items.first?.song.title, "回40")
        let third = show.more(second.continuation ?? "")
        XCTAssertEqual(third.items.count, 10)
        XCTAssertNil(third.continuation)
    }

    /// 長いフィード（数百回分・数 MB）を速く読めること。
    func testParsesLargeFeedQuickly() {
        let description = String(repeating: "<p>とても長い説明 &amp; HTML</p>", count: 200)
        let items = (1...600).map { n in
            "<item><title>回\(n)</title><pubDate>Mon, 05 Oct 2026 10:00:00 +0900</pubDate><enclosure url=\"https://x.example/\(n).mp3\" type=\"audio/mpeg\"/>" +
                "<itunes:duration>1:00:00</itunes:duration><description><![CDATA[\(description)]]></description></item>"
        }.joined()
        let xml = "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"><channel><title>長い番組</title>\(items)</channel></rss>"
        let data = Data(xml.utf8)
        XCTAssertGreaterThan(data.count, 2_000_000)
        let started = Date()
        let show = FeedParser.parse(feedUrl: "https://x.example/f", data: data)
        let page = show?.toPage(saved: nil)
        let elapsed = Date().timeIntervalSince(started)
        XCTAssertEqual(show?.itemCount, 600)
        XCTAssertEqual(page?.episodes.count, 30)
        XCTAssertLessThan(elapsed, 3.0)
        print("feed parse bytes=\(data.count) seconds=\(String(format: "%.3f", elapsed))")
    }

    func testHandoffAcceptsExternalAudioIds() {
        XCTAssertTrue(HandoffCodec.isItemVideoId("ext:https://example.com/ep2.mp3?x=1&y=2"))
        XCTAssertTrue(HandoffCodec.isItemVideoId("dQw4w9WgXcQ"))
        XCTAssertFalse(HandoffCodec.isItemVideoId("ext:http://example.com/a.mp3"))
        XCTAssertFalse(HandoffCodec.isItemVideoId("ext:https://example.com/a b.mp3"))
        XCTAssertFalse(HandoffCodec.isVideoId("ext:https://example.com/a.mp3"))
    }
}
