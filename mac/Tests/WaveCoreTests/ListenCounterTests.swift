import XCTest
@testable import WaveCore

/// 再生履歴を送るまでの聴いた時間（Android の ListenCounterTest と同じ判断）。
final class ListenCounterTests: XCTestCase {
    func testThresholdIs30SecondsOrHalfOfAShortSong() {
        XCTAssertEqual(30_000, ListenCounter.threshold(durationMs: nil))
        XCTAssertEqual(30_000, ListenCounter.threshold(durationMs: 0))
        XCTAssertEqual(30_000, ListenCounter.threshold(durationMs: 200_000))
        XCTAssertEqual(30_000, ListenCounter.threshold(durationMs: 30_000))
        XCTAssertEqual(10_000, ListenCounter.threshold(durationMs: 20_000))
        // 半分が 5 秒より短ければ 5 秒
        XCTAssertEqual(5_000, ListenCounter.threshold(durationMs: 6_000))
    }

    func testCountsOnlyWhilePlaying() {
        var c = ListenCounter()
        c.start(1_000)
        c.stop(11_000)
        XCTAssertEqual(10_000, c.listenedMs(50_000))
        c.start(60_000)
        XCTAssertEqual(15_000, c.listenedMs(65_000))
        XCTAssertTrue(c.running)
    }

    func testStartTwiceDoesNotRestart() {
        var c = ListenCounter()
        c.start(0)
        c.start(5_000)
        XCTAssertEqual(10_000, c.listenedMs(10_000))
    }

    func testSeekingAloneDoesNotSend() {
        // 位置を 3 分先へ飛ばしても、聴いた時間は増えない（位置を見ない）
        var c = ListenCounter()
        c.start(0)
        XCTAssertFalse(c.shouldSend(nowMs: 2_000, durationMs: 240_000, alreadySent: false))
    }

    func testSendsOnceAfterThreshold() {
        var c = ListenCounter()
        c.start(0)
        XCTAssertFalse(c.shouldSend(nowMs: 29_999, durationMs: 240_000, alreadySent: false))
        XCTAssertTrue(c.shouldSend(nowMs: 30_000, durationMs: 240_000, alreadySent: false))
        XCTAssertFalse(c.shouldSend(nowMs: 40_000, durationMs: 240_000, alreadySent: true))
        // 短い曲は半分
        var short = ListenCounter()
        short.start(0)
        XCTAssertTrue(short.shouldSend(nowMs: 10_000, durationMs: 20_000, alreadySent: false))
    }

    func testCarriesListenedTimeFromAnotherDevice() {
        var c = ListenCounter(listenedMs: 25_000)
        XCTAssertFalse(c.shouldSend(nowMs: 0, durationMs: 240_000, alreadySent: false))
        c.start(0)
        XCTAssertTrue(c.shouldSend(nowMs: 5_000, durationMs: 240_000, alreadySent: false))
        XCTAssertEqual(0, ListenCounter(listenedMs: -5).listenedMs(0))
    }

    func testResetReplacesListenedTime() {
        var c = ListenCounter()
        c.start(0)
        c.reset(listenedMs: 0, playing: true, nowMs: 50_000)
        XCTAssertEqual(1_000, c.listenedMs(51_000))
        c.reset(listenedMs: 7_000, playing: false, nowMs: 60_000)
        XCTAssertEqual(7_000, c.listenedMs(90_000))
    }

    func testSaturates() {
        var c = ListenCounter(listenedMs: Int64.max - 1)
        c.start(0)
        c.stop(10)
        XCTAssertEqual(Int64.max, c.listenedMs(10))
    }
}
