import XCTest
@testable import WaveCore

/// 実際に YouTube へ通信する確かめ。環境変数 WAVE_LIVE=1 のときだけ走る（ログインはしない）。
final class LiveStreamTests: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["WAVE_LIVE"] == "1", "WAVE_LIVE=1 のときだけ")
    }

    private static let now: @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }

    func testResolveAndReadAPublicSongAnonymously() async throws {
        let client = InnerTubeClient()
        let cipher = PlayerCipherProvider(session: client.session, nowMs: LiveStreamTests.now)
        let ready = try await cipher.get()
        XCTAssertNotNil(ready, "解読の準備ができない")
        print("live: player=\(ready?.playerHash ?? "-") sts=\(ready?.signatureTimestamp ?? 0)")
        let resolver = StreamResolver(client: client, cipher: cipher, nowMs: LiveStreamTests.now)
        let result = try await resolver.resolve(videoId: "lYBUbBu4W08")
        print("live: failure=\(String(describing: result.failure)) stream=\(result.stream.map(String.init(describing:)) ?? "-")")
        let stream = try XCTUnwrap(result.stream)
        XCTAssertTrue(StreamResolver.isAllowedMediaUrl(stream.revealUrl()))
        XCTAssertNotNil(stream.dash)
        let reader = try await RangeStream.open(session: client.session, stream: stream)
        let head = try await reader.read(count: 4096)
        print("live: length=\(reader.length) head=\(head.count)")
        XCTAssertEqual(4096, head.count)
        // 断片化 MP4 の先頭は ftyp の箱
        XCTAssertEqual("ftyp", String(decoding: head[4..<8], as: UTF8.self))
        // 終わりのほう。ログインしていないと googlevideo は先頭の一部しか返さない（403）ので、結果は記録だけにする
        do {
            let tail = try await reader.readRange(start: reader.length - 1024, end: reader.length - 1)
            print("live: tail=\(tail.count)")
        } catch let e as StreamRejectedError {
            print("live: tail rejected status=\(e.status)（ログインしていないため）")
        }
    }

    func testLrclibFindsSyncedLyrics() async throws {
        let client = LrclibClient(session: InnerTubeClient.createSession(), userAgent: "wave-mac/test")
        let result = try await client.synced(title: "夜に駆ける", artist: "YOASOBI", album: nil, durationSeconds: 261)
        guard case .found(let lines) = result else { return XCTFail("\(result)") }
        print("live: lrclib lines=\(lines.count)")
        XCTAssertGreaterThan(lines.count, 10)
    }
}
