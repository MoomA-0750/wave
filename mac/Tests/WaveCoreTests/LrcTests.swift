import XCTest
@testable import WaveCore

/// Windows の LyricsTests のうち Lyrics.swift（Lrc・LyricsTiming）の分。LrclibClient の分は Lyrics の残りと一緒に移す。
final class LrcTests: XCTestCase {
    func testLrcLinesAreSortedAndOffsetApplied() {
        let lines = Lrc.parse("[ar:x]\n[offset:+500]\n[00:02.00][00:10.5]サビ\n[00:01.905]はじめ\nno time")
        XCTAssertEqual([1405, 1500, 10000], lines.map(\.startMs))
        XCTAssertEqual("はじめ", lines[0].text)
    }

    func testCurrentIndexLeadsSlightly() {
        let lines = [LyricLine(startMs: 1000, text: "a"), LyricLine(startMs: 2000, text: "b")]
        XCTAssertEqual(-1, LyricsTiming.currentIndex(lines, positionMs: 500))
        XCTAssertEqual(0, LyricsTiming.currentIndex(lines, positionMs: 900))
        XCTAssertEqual(1, LyricsTiming.currentIndex(lines, positionMs: 5000))
    }
}
