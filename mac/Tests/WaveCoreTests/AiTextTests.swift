import XCTest
@testable import WaveCore

/// Android の AiTextTest・Windows の AiTextTests と同じ。
final class AiTextTests: XCTestCase {
    func testSyncedLyricsGetTimestampsAndSkipBlankLines() {
        let lyrics = SongLyrics.synced(.init(lines: [LyricLine(startMs: 12_340, text: "first"), LyricLine(startMs: 60_000, text: " "),
                                                     LyricLine(startMs: 83_999, text: " second ")], source: nil))
        XCTAssertEqual("[00:12] first\n[01:23] second", AiText.lyrics(lyrics))
        XCTAssertEqual("a\n\nb", AiText.lyrics(.plain(.init(text: "a\n\nb", source: nil))))
        XCTAssertEqual("61:05", AiText.stamp(3_665_900))
    }

    func testFindsTimestampsInAnswer() {
        let text = "In [01:23]「x」 and **[2:05]** but not [1:75] or [abc]"
        let found = AiText.timestamps(text)
        XCTAssertEqual([83_000, 125_000], found.map(\.positionMs))
        XCTAssertEqual(["01:23", "2:05"], found.map(\.label))
        XCTAssertEqual("[01:23]", String(text[found[0].range]))
    }

    private struct Entry: AiHistoryEntry {
        var id: String
        var updatedAtMs: Int64
    }

    func testHistoryKeepsNewestFirstAndCaps() {
        var list: [Entry] = []
        for i in 0..<(AiHistory.max + 5) { list = AiHistory.upsert(list, Entry(id: "c\(i)", updatedAtMs: Int64(i))) }
        XCTAssertEqual(AiHistory.max, list.count)
        XCTAssertEqual("c\(AiHistory.max + 4)", list[0].id)
        list = AiHistory.upsert(list, Entry(id: "c10", updatedAtMs: 1_000))
        XCTAssertEqual("c10", list[0].id)
        XCTAssertEqual(AiHistory.max, list.count)
    }

    func testTitle() {
        XCTAssertEqual("what does it mean?", AiHistory.title(firstUserText: "  what\n does it   mean? "))
        XCTAssertEqual("", AiHistory.title(firstUserText: nil))
        XCTAssertEqual(AiHistory.titleMax, AiHistory.title(firstUserText: String(repeating: "あ", count: 200)).count)
    }
}
