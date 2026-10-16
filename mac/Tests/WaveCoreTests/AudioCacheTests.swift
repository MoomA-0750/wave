import XCTest
@testable import WaveCore

final class AudioCacheTests: XCTestCase {
    private var directory: URL!

    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("wave-audio-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: directory) }

    private func stream(_ videoId: String, length: Int64, itag: Int = 140, url: String = "https://x.googlevideo.com/videoplayback?id=1") -> ResolvedStream {
        ResolvedStream(videoId: videoId, itag: itag, mimeType: "audio/mp4", contentLength: length, url: url, expiresAtMs: 0, statsUrl: nil, generation: 0,
                       dash: DashInfo(initStart: 0, initEnd: 9, indexStart: 10, indexEnd: 19, bitrate: 1, sampleRate: 44100, durationMs: 1000, codecs: "mp4a.40.2"))
    }

    /// Range の要求に、0..<size の各バイトが (i % 251) の本文で答える。
    private func server(size: Int) -> URLSession {
        let body = Data((0..<size).map { UInt8($0 % 251) })
        return StubURLProtocol.session { request in
            let range = request.value(forHTTPHeaderField: "Range")!.dropFirst("bytes=".count).split(separator: "-").map { Int($0)! }
            let end = min(range[1], size - 1)
            return (206, ["Content-Range": "bytes \(range[0])-\(end)/\(size)"], body.subdata(in: range[0]..<(end + 1)))
        }
    }

    private func expected(_ range: ClosedRange<Int>) -> [UInt8] { range.map { UInt8($0 % 251) } }

    func testBytesAreKeptFromTheStartAndReadBackWithoutTheNetwork() async throws {
        let cache = AudioCache(directory: directory, nowMs: { 0 })
        let reader = try await RangeStream.open(session: server(size: 400_000), stream: stream("aaaaaaaaaaa", length: 400_000), cache: cache)
        // 目次を頭より先に読んでも、間を埋めて頭から続けて残す
        let index = try await reader.readRange(start: 10, end: 19)
        XCTAssertEqual(expected(10...19), [UInt8](index))
        XCTAssertEqual(20, cache.entry("aaaaaaaaaaa")?.cached)
        _ = try await reader.readRange(start: 20, end: 1999)
        XCTAssertEqual(2000, cache.entry("aaaaaaaaaaa")?.cached)
        // 間が大きく空くものは残さない
        _ = try await reader.readRange(start: 300_000, end: 300_099)
        XCTAssertEqual(2000, cache.entry("aaaaaaaaaaa")?.cached)

        // 開き直すと、残した分は URL を調べずに読める
        let entry = try XCTUnwrap(cache.entry("aaaaaaaaaaa"))
        XCTAssertEqual(140, entry.format.itag)
        XCTAssertEqual(19, entry.format.dash?.indexEnd)
        let resolved = Counter()
        let cached = RangeStream.openCached(session: server(size: 400_000), format: entry.format, cache: cache) {
            resolved.increment()
            return self.stream("aaaaaaaaaaa", length: 400_000)
        }
        let kept = try await cached.readRange(start: 100, end: 199)
        XCTAssertEqual(expected(100...199), [UInt8](kept))
        XCTAssertEqual(0, resolved.value)
        XCTAssertEqual(0, StubURLProtocol.recorded.count)
        // 残っていない続きは、そこで URL を調べて取る（残した分と合わせて返す）
        let joined = try await cached.readRange(start: 1990, end: 2009)
        XCTAssertEqual(expected(1990...2009), [UInt8](joined))
        XCTAssertEqual(1, resolved.value)
        XCTAssertEqual(2010, cache.entry("aaaaaaaaaaa")?.cached)
    }

    func testAnotherFormatOfTheSameSongIsNotMixed() async throws {
        let cache = AudioCache(directory: directory, nowMs: { 0 })
        let reader = try await RangeStream.open(session: server(size: 3000), stream: stream("bbbbbbbbbbb", length: 3000), cache: cache)
        _ = try await reader.readRange(start: 0, end: 999)
        // 別の形式で開き直すと、前の分は捨てる
        cache.begin(stream("bbbbbbbbbbb", length: 4000, itag: 141))
        XCTAssertEqual(0, cache.entry("bbbbbbbbbbb")?.cached)
        XCTAssertEqual(141, cache.entry("bbbbbbbbbbb")?.format.itag)
        // 調べた URL の形式が残した形式と違えば、使わない
        let entry = try XCTUnwrap(cache.entry("bbbbbbbbbbb"))
        let cached = RangeStream.openCached(session: server(size: 3000), format: entry.format, cache: cache) { self.stream("bbbbbbbbbbb", length: 3000) }
        do {
            _ = try await cached.readRange(start: 0, end: 9)
            XCTFail("違う形式の URL を使った")
        } catch {
            XCTAssertTrue(error is StreamRejectedError)
        }
    }

    func testOldestSongsAreRemovedOverTheLimitAndClearRemovesAll() async throws {
        let clock = Counter()
        let cache = AudioCache(directory: directory, maxBytes: 2500, nowMs: { Int64(clock.value) })
        for (i, id) in ["ccccccccccc", "ddddddddddd", "eeeeeeeeeee"].enumerated() {
            clock.set(i)
            let reader = try await RangeStream.open(session: server(size: 1000), stream: stream(id, length: 1000), cache: cache)
            _ = try await reader.readRange(start: 0, end: 999)
        }
        // 3曲目を始めるとき、一番古い1曲目を消す
        XCTAssertNil(cache.entry("ccccccccccc"))
        XCTAssertEqual(1000, cache.entry("ddddddddddd")?.cached)
        XCTAssertEqual(1000, cache.entry("eeeeeeeeeee")?.cached)
        // 作り直しても残している
        XCTAssertEqual(1000, AudioCache(directory: directory, nowMs: { 0 }).entry("eeeeeeeeeee")?.cached)
        cache.clear()
        XCTAssertNil(cache.entry("eeeeeeeeeee"))
        XCTAssertEqual(0, cache.totalBytes)
    }

    func testFilesHoldNoUrl() async throws {
        let cache = AudioCache(directory: directory, nowMs: { 0 })
        let reader = try await RangeStream.open(session: server(size: 100), stream: stream("fffffffffff", length: 100, url: "https://x.googlevideo.com/videoplayback?sig=SECRET"), cache: cache)
        _ = try await reader.readRange(start: 0, end: 99)
        for name in try FileManager.default.contentsOfDirectory(atPath: directory.path) where name.hasSuffix(".json") {
            let text = try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
            XCTAssertFalse(text.contains("SECRET"))
            XCTAssertFalse(text.contains("googlevideo"))
        }
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func increment() { lock.withLock { count += 1 } }
    func set(_ v: Int) { lock.withLock { count = v } }
}
