import XCTest

@testable import WaveCore

/// 中継の接続の見張り（Android の RelayWatchTest・Windows の RelayWatchTests と同じ場面）。
final class RelayWatchTests: XCTestCase {
    func testASocketWithoutWelcomeIsGivenUpAfterFifteenSeconds() {
        var watch = RelayWatch()
        watch.opened(0)
        XCTAssertEqual(.none, watch.tick(14_999))
        // challenge だけ届いても welcome でなければ待ち続けない
        watch.received(5_000)
        XCTAssertEqual(.dead, watch.tick(15_000))
    }

    func testAnIdleSocketIsProbedAndDroppedWhenNothingComesBack() {
        var watch = RelayWatch()
        watch.opened(0)
        watch.received(1_000, welcome: true)
        XCTAssertEqual(.none, watch.tick(20_999))
        XCTAssertEqual(.probe, watch.tick(21_000))
        XCTAssertEqual(.none, watch.tick(28_999))
        XCTAssertEqual(.dead, watch.tick(29_000))
    }

    func testAnyReplyKeepsTheSocket() {
        var watch = RelayWatch()
        watch.opened(0)
        watch.received(0, welcome: true)
        XCTAssertEqual(.probe, watch.tick(20_000))
        watch.received(20_300)
        XCTAssertEqual(.none, watch.tick(29_000))
        XCTAssertEqual(.probe, watch.tick(40_300))
    }

    func testWakingProbesAtOnceOnlyWhenConnected() {
        var watch = RelayWatch()
        watch.opened(0)
        XCTAssertEqual(.none, watch.wake(100))
        watch.received(200, welcome: true)
        XCTAssertEqual(.probe, watch.wake(300))
        XCTAssertEqual(.none, watch.wake(400))
        XCTAssertEqual(.dead, watch.tick(8_300))
    }

    func testReconnectWaitsStartAtOneSecondAndStopAtThirty() {
        XCTAssertEqual([1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000], (0...6).map(RelayWatch.reconnectDelayMs))
    }

    func testTheProbeIsAChainRequestTheRelayAnswersWithNothing() {
        guard case .chainGet(let get)? = RelayV2.decode(RelayWatch.probe) else { return XCTFail("chain_get ではない") }
        XCTAssertEqual(2_147_483_647, get.fromSeq)
    }
}
