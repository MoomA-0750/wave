import XCTest
@testable import WaveCore

/// 一覧を流し終えたあとのラジオへの切り替え（Kotlin の QueueSession の switchToRadio・blockedByRepeat と同じ判断）。
final class QueueRadioTests: XCTestCase {
    private func song(_ id: String, live: Bool = false) -> Song {
        Song(videoId: id, title: id, artists: [], album: nil, durationSeconds: 200, thumbnailUrl: nil, isLive: live)
    }

    private lazy var list = ["a", "b", "c", "d", "e"].map { song($0) }

    private func seed(index: Int, queue: [Song]? = nil, radioSeed: String? = nil, continuation: String? = nil, repeatAll: Bool = false,
                      restPending: Bool = false) -> String?
    {
        QueueRadio.switchSeed(queue: queue ?? list, index: index, radioSeed: radioSeed, radioContinuation: continuation, repeatAll: repeatAll,
                              restPending: restPending)
    }

    func testSwitchesToLastSongRadioWhenTwoOrFewerRemain() {
        XCTAssertNil(seed(index: 0))
        XCTAssertNil(seed(index: 1))
        XCTAssertEqual("e", seed(index: 2))
        XCTAssertEqual("e", seed(index: 4))
        XCTAssertEqual("RDAMVMe", QueueRadio.playlistId("e"))
    }

    func testDoesNotSwitchWhenAlreadyRadio() {
        XCTAssertNil(seed(index: 4, radioSeed: "x"))
        XCTAssertNil(seed(index: 4, continuation: "token"))
    }

    func testDoesNotSwitchWhileRepeatingAllOrRestIsLoading() {
        XCTAssertNil(seed(index: 4, repeatAll: true))
        XCTAssertNil(seed(index: 4, restPending: true))
    }

    func testDoesNotSwitchFromEpisodesOrLive() {
        XCTAssertNil(seed(index: 1, queue: [song("a"), song(Feed.audioPrefix + "https://example.com/1.mp3")]))
        XCTAssertNil(seed(index: 1, queue: [song("a"), song("b", live: true)]))
        // 最後の曲だけを見る（途中に RSS があっても、最後が曲ならつなぐ）
        XCTAssertEqual("b", seed(index: 1, queue: [song(Feed.audioPrefix + "https://example.com/1.mp3"), song("b")]))
    }

    func testOutOfRangeIndexDoesNotSwitch() {
        XCTAssertNil(seed(index: -1))
        XCTAssertNil(seed(index: 5))
        XCTAssertNil(seed(index: 0, queue: []))
    }

    func testExportedPlaylistIsNotExhausted() {
        // 一覧は、受け取った端末が最後にラジオへ切り替えるので尽きていない
        XCTAssertFalse(QueueRadio.exhausted(radioSeed: nil, playlistSource: true, last: song("e"), truncated: false))
        XCTAssertFalse(QueueRadio.exhausted(radioSeed: "e", playlistSource: false, last: song("e"), truncated: false))
        XCTAssertFalse(QueueRadio.exhausted(radioSeed: nil, playlistSource: false, last: song("e"), truncated: true))
        // ラジオでも一覧でもない（ライブ配信の1曲など）・最後からラジオを作れない一覧は尽きている
        XCTAssertTrue(QueueRadio.exhausted(radioSeed: nil, playlistSource: false, last: song("e", live: true), truncated: false))
        XCTAssertTrue(QueueRadio.exhausted(radioSeed: nil, playlistSource: true, last: song(Feed.audioPrefix + "https://example.com/1.mp3"),
                                           truncated: false))
    }
}
