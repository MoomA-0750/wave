import XCTest
@testable import WaveCore

/// 拒否が続く曲を少しの間解決し直さない（Android の ResolvedStreamCache.recentlyRejected と同じ 2 分）。
final class RejectionCooldownTests: XCTestCase {
    func testSkipsForTwoMinutesThenForgets() {
        var c = RejectionCooldown()
        XCTAssertFalse(c.recentlyRejected("a", nowMs: 0))
        c.markRejected("a", nowMs: 1_000)
        XCTAssertTrue(c.recentlyRejected("a", nowMs: 1_000))
        XCTAssertTrue(c.recentlyRejected("a", nowMs: 120_999))
        XCTAssertFalse(c.recentlyRejected("b", nowMs: 2_000))
        XCTAssertFalse(c.recentlyRejected("a", nowMs: 121_000))
        // 一度たてば忘れる
        XCTAssertFalse(c.recentlyRejected("a", nowMs: 2_000))
    }

    func testClearForgetsAll() {
        var c = RejectionCooldown()
        c.markRejected("a", nowMs: 0)
        c.clear()
        XCTAssertFalse(c.recentlyRejected("a", nowMs: 1))
    }
}
