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

    private final class FakeMusic: PlainLyricsSource, @unchecked Sendable {
        var result: ApiResult<SongLyrics.Plain?>
        var calls = 0
        init(_ result: ApiResult<SongLyrics.Plain?>) { self.result = result }
        func plainLyrics(videoId: String) async throws -> ApiResult<SongLyrics.Plain?> {
            calls += 1
            return result
        }
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
}
