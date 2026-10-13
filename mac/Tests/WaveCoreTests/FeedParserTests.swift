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

    // MARK: Kotlin の FeedLibrarySyncTest と同じ

    func testRoundTripsThroughTheSyncBodyAndRemovalWins() {
        let a = FeedLibraryState().subscribe(FeedSubscription(feedUrl: "https://a.example/f", title: "A", addedAtMs: 10))
            .remember("https://a.example/1.mp3", EpisodePosition.of(positionSeconds: 60, durationSeconds: 600, nowMs: 11))
        // もう1台: 何も持っていない → 受け取る
        let b = FeedLibraryState().apply(a.toBody())
        XCTAssertEqual(b.subscriptions.map(\.title), ["A"])
        XCTAssertEqual(b.positions["https://a.example/1.mp3"]?.positionSeconds, 60)
        // b で外す（後の時刻）→ a に当てると外れる
        let removed = b.unsubscribe("https://a.example/f", atMs: 20)
        let a2 = a.apply(a.toBody().merge(removed.toBody()))
        XCTAssertTrue(a2.subscriptions.isEmpty)
        XCTAssertEqual(a2.removed["https://a.example/f"], 20)
        // 古い登録の知らせは、新しい「外した」を覆さない
        XCTAssertTrue(a2.apply(a.toBody()).subscriptions.isEmpty)
        // 外した後にもう一度登録すると、印は消える
        let again = a2.subscribe(FeedSubscription(feedUrl: "https://a.example/f", title: "A", addedAtMs: 30))
        XCTAssertNil(again.removed["https://a.example/f"])
        XCTAssertEqual(a2.apply(again.toBody()).subscriptions.map(\.title), ["A"])
    }

    /// 外した印は端末の JSON に残る（次の起動でも伝えられる）。
    func testRemovedMarksSurviveTheLocalJson() {
        let state = FeedLibraryState().subscribe(FeedSubscription(feedUrl: "https://a.example/f", title: "A", addedAtMs: 1)).unsubscribe("https://a.example/f", atMs: 5)
        XCTAssertEqual(FeedLibraryState.encode(state).flatMap(FeedLibraryState.decode)?.removed["https://a.example/f"], 5)
    }

    // MARK: Kotlin の NewEpisodesTest と同じ

    private let now: Int64 = 1_791_853_200  // 2026-10-13 01:00 UTC

    private func youtube(_ id: String, _ published: String?) -> PodcastEpisode {
        PodcastEpisode(song: Song(videoId: id, title: id, artists: [], album: nil, durationSeconds: nil, thumbnailUrl: nil), publishedText: published)
    }

    func testReadsYouTubeDates() {
        XCTAssertEqual(NewEpisodes.approximateEpochSeconds("6 日前", nowEpochSeconds: now), now - 6 * 86_400)
        XCTAssertEqual(NewEpisodes.approximateEpochSeconds("2 時間前", nowEpochSeconds: now), now - 2 * 3_600)
        XCTAssertEqual(NewEpisodes.approximateEpochSeconds("2025/09/08", nowEpochSeconds: now), FeedParser.parseRfc822("8 Sep 2025 00:00:00 GMT"))
        XCTAssertNil(NewEpisodes.approximateEpochSeconds("ライブ", nowEpochSeconds: now))
    }

    func testMergesNewestFirst() {
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let rfc = [3, 10, 200].map { days -> String in
            let parts = FeedParser.dateText(now - Int64(days) * 86_400).split(separator: "/").map(String.init)
            return "<item><title>r\(days)</title><pubDate>\(parts[2]) \(months[Int(parts[1])! - 1]) \(parts[0]) 01:00:00 GMT</pubDate><enclosure url=\"https://x.example/\(days).mp3\"/></item>"
        }.joined()
        guard let show = FeedParser.parse(feedUrl: "https://x.example/f", xml: "<rss><channel><title>R</title>\(rfc)</channel></rss>") else { return XCTFail("読めない") }
        let merged = NewEpisodes.merge([youtube("y1", "1 日前"), youtube("y6", "6 日前"), youtube("y?", nil), youtube("y30", "2026/09/13")], [show],
                                       nowEpochSeconds: now)
        // 200 日前の RSS の回は混ぜない。読めない表示の回は一つ前と同じ日時
        XCTAssertEqual(merged.map(\.song.title), ["y1", "r3", "y6", "y?", "r10", "y30"])
    }

    func testHandoffAcceptsExternalAudioIds() {
        XCTAssertTrue(HandoffCodec.isItemVideoId("ext:https://example.com/ep2.mp3?x=1&y=2"))
        XCTAssertTrue(HandoffCodec.isItemVideoId("dQw4w9WgXcQ"))
        XCTAssertFalse(HandoffCodec.isItemVideoId("ext:http://example.com/a.mp3"))
        XCTAssertFalse(HandoffCodec.isItemVideoId("ext:https://example.com/a b.mp3"))
        XCTAssertFalse(HandoffCodec.isVideoId("ext:https://example.com/a.mp3"))
    }
}
