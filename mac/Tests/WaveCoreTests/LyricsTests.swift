import XCTest
@testable import WaveCore

final class LyricsTests: XCTestCase {
    func testLrcLinesAreSortedAndOffsetApplied() {
        let lines = Lrc.parse("[ar:x]\n[offset:+500]\n[00:02.00][00:10.5]サビ\n[00:01.905]はじめ\nno time")
        XCTAssertEqual([1405, 1500, 10000], lines.map(\.startMs))
        XCTAssertEqual("はじめ", lines[0].text)
    }

    func testLrcHandlesCrLfAndCaseInsensitiveOffset() {
        let lines = Lrc.parse("[OFFSET: -200]\r\n[01:00]a\r\n[00:00.1] b \r\n")
        XCTAssertEqual([LyricLine(startMs: 300, text: "b"), LyricLine(startMs: 60200, text: "a")], lines)
    }

    func testCurrentIndexLeadsSlightly() {
        let lines = [LyricLine(startMs: 1000, text: "a"), LyricLine(startMs: 2000, text: "b")]
        XCTAssertEqual(-1, LyricsTiming.currentIndex(lines, positionMs: 500))
        XCTAssertEqual(0, LyricsTiming.currentIndex(lines, positionMs: 900))
        XCTAssertEqual(1, LyricsTiming.currentIndex(lines, positionMs: 5000))
        XCTAssertEqual(-1, LyricsTiming.currentIndex([], positionMs: 5000))
    }

    func testCurrentAndNextSkipsReadingsAndMarksBlankLines() {
        let lines = [
            LyricLine(startMs: 1000, text: "原文"), LyricLine(startMs: 1000, text: "よみ"),
            LyricLine(startMs: 3000, text: " "), LyricLine(startMs: 5000, text: "次"),
        ]
        XCTAssertTrue(LyricsTiming.currentAndNext(lines, positionMs: 0) == (nil, "原文"))
        XCTAssertTrue(LyricsTiming.currentAndNext(lines, positionMs: 1500) == ("原文", "♪"))
        XCTAssertTrue(LyricsTiming.currentAndNext(lines, positionMs: 3500) == ("♪", "次"))
        XCTAssertTrue(LyricsTiming.currentAndNext(lines, positionMs: 9000) == ("次", nil))
    }

    func testSearchTitlesDropDecorations() {
        XCTAssertEqual(["夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"], LrclibClient.searchTitles("夜に駆ける (From \"THE FIRST TAKE\")"))
        XCTAssertEqual(["Song - Orion", "Song"], LrclibClient.searchTitles("Song - Orion"))
        XCTAssertEqual(["曲【MV】", "曲"], LrclibClient.searchTitles("曲【MV】"))
    }

    func testPickPrefersTheClosestDuration() {
        typealias T = LrclibClient.Track
        let tracks = [
            T(duration: 200, syncedLyrics: "[00:01]a"),
            T(duration: 203, instrumental: true, syncedLyrics: "[00:01]i"),
            T(duration: 204, syncedLyrics: "[00:01]b"),
            T(duration: 201, syncedLyrics: "  "),
        ]
        XCTAssertEqual(tracks[2], LrclibClient.pick(tracks, durationSeconds: 205))
        XCTAssertEqual(tracks[0], LrclibClient.pick(tracks, durationSeconds: 199))
        XCTAssertEqual(tracks[0], LrclibClient.pick(tracks, durationSeconds: nil))
        XCTAssertNil(LrclibClient.pick(tracks, durationSeconds: 300))
    }

    func testLrclibTriesExactThenSearch() async throws {
        let session = StubURLProtocol.session { request in
            let url = request.url!.absoluteString
            if url.contains("/api/get") { return (404, [:], Data()) }
            if url.contains("track_name=Song&") {
                return (200, [:], Data(#"[{"trackName":"Song","duration":181.0,"instrumental":false,"syncedLyrics":"[00:01.00]one\n[00:02.00]two"}]"#.utf8))
            }
            return (200, [:], Data("[]".utf8))
        }
        let client = LrclibClient(session: session, userAgent: "wave-mac/test", baseUrl: "https://lrclib.invalid")
        let result = try await client.synced(title: "Song (Live)", artist: "A B", album: "X", durationSeconds: 180)
        XCTAssertEqual(.found([LyricLine(startMs: 1000, text: "one"), LyricLine(startMs: 2000, text: "two")]), result)
        let urls = StubURLProtocol.recorded.map { $0.url!.absoluteString }
        XCTAssertEqual([
            "https://lrclib.invalid/api/get?track_name=Song%20%28Live%29&artist_name=A%20B&album_name=X&duration=180",
            "https://lrclib.invalid/api/search?track_name=Song%20%28Live%29&artist_name=A%20B",
            "https://lrclib.invalid/api/search?track_name=Song&artist_name=A%20B",
        ], urls)
        XCTAssertEqual("wave-mac/test", StubURLProtocol.recorded.first?.value(forHTTPHeaderField: "User-Agent"))
    }

    func testLrclibServerErrorIsAFailure() async throws {
        let session = StubURLProtocol.session { _ in (500, [:], Data()) }
        let result = try await LrclibClient(session: session, userAgent: "t").synced(title: "a", artist: "b", album: nil, durationSeconds: nil)
        XCTAssertEqual(.failed, result)
    }

    // MARK: LyricsService

    /// YouTube Music。歌詞のタブは browseId（result が失敗ならタブを探すのも失敗）、時刻なしは result。
    private final class FakeMusic: PlainLyricsSource, @unchecked Sendable {
        var result: ApiResult<SongLyrics.Plain?>
        let browseId: String
        var calls = 0
        init(_ result: ApiResult<SongLyrics.Plain?>, browseId: String = "MPLYt_LfJd6hctedK-1") {
            self.result = result
            self.browseId = browseId
        }
        func lyricsBrowseId(videoId: String) async throws -> ApiResult<String?> {
            calls += 1
            if let failure = result.failure { return .failed(failure) }
            return .ok(browseId)
        }
        func plainLyrics(browseId: String) async throws -> ApiResult<SongLyrics.Plain?> { result }
    }

    private struct FakeLrclib: SyncedLyricsSource {
        let result: LrclibClient.Result
        func synced(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> LrclibClient.Result { result }
    }

    private let song = Song(videoId: "v1", title: "t", artists: [ArtistRef(name: "a", browseId: nil)], album: nil, durationSeconds: 100, thumbnailUrl: nil)

    func testServicePrefersSyncedLyrics() async throws {
        let lines = [LyricLine(startMs: 0, text: "x")]
        let service = LyricsService(music: FakeMusic(.ok(SongLyrics.Plain(text: "p", source: "s"))), lrclib: FakeLrclib(result: .found(lines)))
        let result = try await service.lyrics(song: song)
        XCTAssertEqual(.found(.synced(SongLyrics.Synced(lines: lines, source: LyricsService.lrclibSource))), result)
    }

    func testServiceFallsBackToPlainAndCaches() async throws {
        let music = FakeMusic(.ok(SongLyrics.Plain(text: "p", source: "Source: X")))
        let service = LyricsService(music: music, lrclib: FakeLrclib(result: .notFound))
        let first = try await service.lyrics(song: song)
        XCTAssertEqual(.found(.plain(SongLyrics.Plain(text: "p", source: "Source: X"))), first)
        let second = try await service.lyrics(song: song)
        XCTAssertEqual(first, second)
        XCTAssertEqual(1, music.calls)
    }

    func testServiceNoneAndFailures() async throws {
        let none = try await LyricsService(music: FakeMusic(.ok(nil)), lrclib: FakeLrclib(result: .notFound)).lyrics(song: song)
        XCTAssertEqual(LyricsService.Result.none, none)
        let lrcFailed = try await LyricsService(music: FakeMusic(.ok(nil)), lrclib: FakeLrclib(result: .failed)).lyrics(song: song)
        XCTAssertEqual(.failed, lrcFailed)
        // 失敗は覚えない
        let music = FakeMusic(.failed(.network))
        let service = LyricsService(music: music, lrclib: FakeLrclib(result: .notFound))
        let failed = try await service.lyrics(song: song)
        XCTAssertEqual(.failed, failed)
        _ = try await service.lyrics(song: song)
        XCTAssertEqual(2, music.calls)
    }

    // MARK: YouTube Music の時刻付き（Android 版の LyricsRepositoryTest と同じ確かめ方）

    func testTimedYouTubeLyricsAreReadAndUntimedLinesAreNot() throws {
        let synced = try XCTUnwrap(YouTubeTimedLyricsClient.parse(Fixtures.json("browse_lyrics_timed")))
        XCTAssertEqual([LyricLine(startMs: 1530, text: "一行目"), LyricLine(startMs: 9040, text: "二行目"), LyricLine(startMs: 17000, text: "")], synced.lines)
        XCTAssertEqual("提供元: Musixmatch", synced.source)
        // MV の歌詞（時刻の無い行だけ）と、古い形（時刻なしの棚）は時刻付きにしない
        XCTAssertNil(YouTubeTimedLyricsClient.parse(Fixtures.parse(Fixtures.text("browse_lyrics_timed").replacingOccurrences(of: "\"cueRange\"", with: "\"noCueRange\""))))
        XCTAssertNil(YouTubeTimedLyricsClient.parse(Fixtures.json("browse_lyrics")))
    }

    /// LRCLIB へ尋ねた回数を数える。
    private final class CountingLrclib: SyncedLyricsSource, @unchecked Sendable {
        var calls = 0
        func synced(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> LrclibClient.Result {
            calls += 1
            return .notFound
        }
    }

    private func timedClient(status: Int) -> YouTubeTimedLyricsClient {
        let session = StubURLProtocol.session { _ in
            status == 200 ? (200, [:], Data(Fixtures.text("browse_lyrics_timed").utf8)) : (status, [:], Data())
        }
        return YouTubeTimedLyricsClient(session: session, hl: "ja", gl: "JP")
    }

    /// URLProtocol に渡ると本文が httpBodyStream に移るので、そこから読む。
    private func bodyOf(_ request: URLRequest) -> String {
        if let body = request.httpBody { return String(decoding: body, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return String(decoding: data, as: UTF8.self)
    }

    func testTimedYouTubeLyricsComeFirstWithoutAskingLrclib() async throws {
        let lrclib = CountingLrclib()
        let service = LyricsService(music: FakeMusic(.ok(SongLyrics.Plain(text: "p", source: "s"))), lrclib: lrclib, timed: timedClient(status: 200))
        let result = try await service.lyrics(song: song)
        guard case .found(.synced(let synced)) = result else { return XCTFail("時刻付きにならない: \(result)") }
        XCTAssertEqual("提供元: Musixmatch", synced.source)
        // 歌詞のタブの ID だけを ANDROID_MUSIC として送る。LRCLIB には聞かない
        let requests = StubURLProtocol.recorded
        XCTAssertEqual(1, requests.count)
        let body = bodyOf(requests[0])
        XCTAssertTrue(body.contains("MPLYt_LfJd6hctedK-1") && body.contains("ANDROID_MUSIC"), body)
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(0, lrclib.calls)
    }

    func testPlainLyricsShownAfterAFailureAreNotRemembered() async throws {
        let service = LyricsService(music: FakeMusic(.ok(SongLyrics.Plain(text: "p", source: "s"))), lrclib: CountingLrclib(), timed: timedClient(status: 503))
        let first = try await service.lyrics(song: song)
        XCTAssertEqual(.found(.plain(SongLyrics.Plain(text: "p", source: "s"))), first)
        _ = try await service.lyrics(song: song)
        XCTAssertEqual(2, StubURLProtocol.recorded.count, "時刻付きの失敗のあとの時刻なしを覚えてしまった")
    }
}
