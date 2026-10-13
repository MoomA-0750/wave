import XCTest
@testable import WaveCore

/// 歌詞から曲を探す（Android の LyricsSearchTest と同じ。決定 2026-10-14）。
final class LyricsSearchTests: XCTestCase {
    private func song(_ id: String, _ title: String, artist: String = "A", kind: String? = Song.kindSong) -> Song {
        Song(videoId: id, title: title, artists: [ArtistRef(name: artist, browseId: nil)], album: nil, durationSeconds: 200, thumbnailUrl: nil, kind: kind)
    }

    func testUnfilteredSearchPutsTheTopCardFirstAndSkipsArtists() {
        // 歌詞の一節「沈むように溶けてゆくように」で匿名に取った応答（棚は itemSectionRenderer）
        let songs = MusicParsers.searchTopSongs(Fixtures.json("search_all_lyrics"))
        XCTAssertEqual("x8VYWazR5mE", songs.first?.videoId)
        XCTAssertEqual("夜に駆ける", songs.first?.title)
        XCTAssertEqual(Song.kindMusicVideo, songs.first?.kind)
        XCTAssertEqual(["YOASOBI"], songs.first?.artists.map(\.name))
        XCTAssertEqual(4 * 60 + 36, songs.first?.durationSeconds)
        XCTAssertEqual("by4SYYWlhEs", songs.count > 1 ? songs[1].videoId : nil)
        XCTAssertGreaterThanOrEqual(songs.count, 4)
        XCTAssertEqual(songs.count, Set(songs.map(\.videoId)).count)
    }

    func testNormalizeIgnoresCaseWidthKanaAndPunctuation() {
        XCTAssertEqual(LyricsMatcher.normalize("I'm in love with the shape of you"), LyricsMatcher.normalize("im in LOVE with　the shape of you!"))
        XCTAssertEqual(LyricsMatcher.normalize("ＡＢＣ　カタカナ、です。"), LyricsMatcher.normalize("abc かたかなです"))
        XCTAssertEqual("", LyricsMatcher.normalize(" 、。!? "))
    }

    func testFindsTheLineEvenWhenThePhraseSpansTwoLines() {
        let lyrics = SongLyrics.synced(.init(lines: [
            LyricLine(startMs: 1_000, text: "沈むように"), LyricLine(startMs: 3_000, text: "溶けてゆくように"), LyricLine(startMs: 6_000, text: "二人だけの空が"),
        ], source: nil))
        XCTAssertEqual(LyricsMatch(line: "沈むように", startMs: 1_000, exact: true), LyricsMatcher.find(lyrics, "沈むように溶けてゆく"))
        XCTAssertEqual(LyricsMatch(line: "二人だけの空が", startMs: 6_000, exact: true), LyricsMatcher.find(lyrics, "二人だけの"))
        let plain = SongLyrics.plain(.init(text: "Hello, it's me\n\nI was wondering if after all these years", source: nil))
        XCTAssertEqual(LyricsMatch(line: "I was wondering if after all these years", startMs: nil, exact: true), LyricsMatcher.find(plain, "wondering if after"))
    }

    func testASlightlyWrongPhraseIsAnApproximateMatchAndUnrelatedTextIsNone() {
        let lyrics = SongLyrics.plain(.init(text: "to tell you I'm sorry for everything that I've done", source: nil))
        let near = LyricsMatcher.find(lyrics, "to tell you sorry for everything i've done")
        XCTAssertEqual(false, near?.exact)
        XCTAssertNil(LyricsMatcher.find(lyrics, "never gonna give you up"))
        // 短すぎる言葉は大体の一致を取らない（何にでも合ってしまう）
        XCTAssertNil(LyricsMatcher.find(lyrics, "toz"))
    }

    func testSongsWithTheLyricsComeFirstAndTheSongReplacesItsVideo() async throws {
        let video = song("v1", "夜に駆ける", artist: "YOASOBI", kind: Song.kindMusicVideo)
        let other = song("o1", "別の曲", artist: "B")
        let atv = song("a1", "夜に駆ける", artist: "YOASOBI")
        let search = LyricsSearch(
            candidates: { _ in .ok([video, other, atv]) },
            lyrics: { $0.videoId == "a1" ? .found(.plain(.init(text: "沈むように溶けてゆくように", source: nil))) : .none })
        let hits = try await search.search("溶けてゆくように").value ?? []
        XCTAssertEqual(["a1", "o1"], hits.map(\.song.videoId))
        XCTAssertEqual("沈むように溶けてゆくように", hits.first?.match?.line)
        XCTAssertNil(hits.count > 1 ? hits[1].match : LyricsMatch(line: "", startMs: nil, exact: true))
    }

    func testUnmatchedUserVideosGoBelowUnmatchedSongs() {
        let cover = LyricsHit(song: song("u1", "歌ってみた", kind: Song.kindUserVideo), match: nil)
        let plain = LyricsHit(song: song("a1", "曲"), match: nil)
        let matched = LyricsHit(song: song("u2", "歌詞動画", kind: Song.kindUserVideo), match: LyricsMatch(line: "行", startMs: nil, exact: false))
        XCTAssertEqual([matched, plain, cover], LyricsSearch.rank([cover, plain, matched]))
    }

    func testFailedSearchIsPassedOnAndBlankQueryIsEmpty() async throws {
        let asked = Asked()
        let search = LyricsSearch(
            candidates: { _ in
                asked.mark()
                return .failed(.network)
            },
            lyrics: { _ in .none })
        let blank = try await search.search(" 。")
        XCTAssertTrue(blank.isOk)
        XCTAssertEqual([], blank.value)
        XCTAssertFalse(asked.value)
        let failed = try await search.search("夜に駆ける")
        XCTAssertEqual(.network, failed.failure)
    }
}

/// 候補を尋ねたかどうか（並行に呼ばれる閉包から書く）。
private final class Asked: @unchecked Sendable {
    private let lock = NSLock()
    private var asked = false
    func mark() { lock.withLock { asked = true } }
    var value: Bool { lock.withLock { asked } }
}
