import XCTest
@testable import WaveCore

/// 日本語と英語の2つの応答（2026-11-07 に取って、使わない欄を落としたもの）から、元の表記を選ぶ。Android の OriginalTitlesTest と同じ fixture・同じ期待値。
final class OriginalTitlesTests: XCTestCase {
    private func merged(_ name: String, _ lang: String, known: [String: String] = [:]) -> OriginalTitles.Result {
        OriginalTitles.merge(Fixtures.json("bilingual_\(name)_\(lang)"), Fixtures.json("bilingual_\(name)_\(OriginalTitles.otherLanguage(lang))"),
                             primaryLanguage: lang, known: known)
    }

    private let taylor = "UCPC0L1d253x-KuMNwa05TpA"

    func testWesternSongsKeepTheirEnglishNamesInJapanese() {
        let songs = MusicParsers.searchSongs(merged("search_songs", "ja").json).songs
        let artists = songs.flatMap { $0.artists.map(\.name) }
        XCTAssertTrue(artists.contains("Taylor Swift"))
        XCTAssertFalse(artists.contains { $0.contains("テイラー") })
        XCTAssertTrue(songs.contains { $0.title == "All Too Well" })
        // 英語の画面では、もともと英語なので変えない
        XCTAssertEqual(MusicParsers.searchSongs(Fixtures.json("bilingual_search_songs_en")).songs, MusicParsers.searchSongs(merged("search_songs", "en").json).songs)
    }

    func testJapaneseSongsKeepTheirJapaneseNamesInEnglish() throws {
        let album = try XCTUnwrap(MusicParsers.albumPage(merged("album_yonezu", "en").json, browseId: "MPREb_NnSQrGZDyMk"))
        XCTAssertEqual(["米津玄師"], album.artists.map(\.name))
        let titles = album.songs.map(\.title)
        XCTAssertTrue(titles.contains("アイネクライネ"))
        XCTAssertTrue(titles.contains("花に嵐"))
        XCTAssertFalse(titles.contains { $0.contains(" - ") }, titles.description)
        // 日本語の画面では、アルバムの曲はそのまま
        XCTAssertEqual(
            try XCTUnwrap(MusicParsers.albumPage(Fixtures.json("bilingual_album_yonezu_ja"), browseId: "MPREb_NnSQrGZDyMk")).songs,
            try XCTUnwrap(MusicParsers.albumPage(merged("album_yonezu", "ja").json, browseId: "MPREb_NnSQrGZDyMk")).songs)
    }

    func testArtistPageUsesTheArtistsOwnNames() throws {
        let ja = merged("artist_taylor", "ja")
        let page = try XCTUnwrap(MusicParsers.artistPage(ja.json))
        XCTAssertEqual("Taylor Swift", page.name)
        let titles = page.topSongs.map(\.title)
        XCTAssertTrue(titles.contains("Shake It Off"), titles.description)
        XCTAssertFalse(titles.contains { $0.contains { ("ァ"..."ヶ").contains($0) } }, titles.description)
        XCTAssertEqual("en", ja.learned[taylor])
        // 英語の画面で、日本語の副題（「～気にしてなんかいられないっ！！」の漢字）を日本の曲と取り違えない
        let en = try XCTUnwrap(MusicParsers.artistPage(merged("artist_taylor", "en").json))
        XCTAssertEqual(try XCTUnwrap(MusicParsers.artistPage(Fixtures.json("bilingual_artist_taylor_en"))).topSongs, en.topSongs)
    }

    func testQueueUsesWhatWasLearnedAboutTheArtist() throws {
        // キューの動画の行はアーティスト名（チャンネル名）が同じで題しか違わないので、覚えたことが無ければ変えない
        let plain = try XCTUnwrap(MusicParsers.upNext(merged("next_taylor", "ja").json).songs.first)
        XCTAssertTrue(plain.title.hasPrefix("シェイク"))
        let learned = try XCTUnwrap(MusicParsers.upNext(merged("next_taylor", "ja", known: [taylor: "en"]).json).songs.first)
        XCTAssertEqual("Shake It Off", learned.title)
        XCTAssertEqual("Shake It Off", MusicParsers.upNext(merged("next_taylor", "en").json).songs.first?.title)
    }

    func testKatakanaJapaneseArtistsAndYouTubePlaylistsStay() {
        // サカナクション（カタカナ名の邦楽）は、曲名に日本の文字が残るので日本の曲と分かる。YouTube のプレイリスト名・棚の見出しは画面の言語のまま
        let en = MusicParsers.homePage(merged("home", "en").json)
        let plain = MusicParsers.homePage(Fixtures.json("bilingual_home_en"))
        XCTAssertEqual(plain.shelves.map(\.title), en.shelves.map(\.title))
        // YouTube のプレイリストの補足（並ぶアーティストの名前）は画面の言語のまま
        let items = String(describing: en.shelves.flatMap(\.items).filter { if case .song = $0 { true } else { false } })
        XCTAssertTrue(items.contains("サカナクション"))
        XCTAssertTrue(items.contains("夜の踊り子") && !items.contains("Yoru No Odoriko"))
        XCTAssertTrue(items.contains("米津玄師") && !items.contains("Kenshi Yonezu"))
        XCTAssertTrue(items.contains("Official髭男dism"))
        let ja = MusicParsers.homePage(merged("home", "ja").json)
        let jaPlain = MusicParsers.homePage(Fixtures.json("bilingual_home_ja"))
        XCTAssertEqual(jaPlain.shelves.map(\.title), ja.shelves.map(\.title))
        XCTAssertEqual(jaPlain.shelves.map(\.strapline), ja.shelves.map(\.strapline))
        XCTAssertEqual(jaPlain.shelves.map(\.items), ja.shelves.map(\.items))
        XCTAssertEqual(jaPlain.chips, ja.chips)
        XCTAssertEqual(jaPlain.continuation, ja.continuation)
    }

    func testOtherLanguageIsTheOppositeOfTheScreen() {
        XCTAssertEqual("en", OriginalTitles.otherLanguage("ja"))
        XCTAssertEqual("en", OriginalTitles.otherLanguage("ja-JP"))
        XCTAssertEqual("ja", OriginalTitles.otherLanguage("en-GB"))
    }
}

/// 設定の「曲名を元の表記で」: 入れたときだけ、検索を日本語と英語の2つで送って突き合わせる（Android の OriginalTitlesSessionTest と同じ）。
final class OriginalTitlesClientTests: XCTestCase {
    private func repository(originalTitles: Bool, englishFails: Bool = false) -> MusicRepository {
        let session = StubURLProtocol.session { request in
            let hl = request.value(forHTTPHeaderField: "Accept-Language") ?? ""
            guard request.url?.path.hasSuffix("/search") == true else { return (404, [:], Data()) }
            if hl == "en" {
                return englishFails ? (500, [:], Data()) : (200, [:], Data(Fixtures.text("bilingual_search_songs_en").utf8))
            }
            return (200, [:], Data(Fixtures.text("bilingual_search_songs_ja").utf8))
        }
        let client = InnerTubeClient(session: session, hl: "ja", gl: "JP")
        client.originalTitles = originalTitles
        return MusicRepository(client: client)
    }

    private var languages: [String] { StubURLProtocol.recorded.map { $0.value(forHTTPHeaderField: "Accept-Language") ?? "" } }

    private func artists(_ result: ApiResult<SongPage>) throws -> [String] {
        try XCTUnwrap(result.value).songs.flatMap { $0.artists.map(\.name) }
    }

    func testOffSendsOneRequestInTheScreenLanguage() async throws {
        let names = try artists(try await repository(originalTitles: false).searchSongs("Taylor Swift"))
        XCTAssertEqual(["ja"], languages)
        XCTAssertTrue(names.contains("テイラー・スウィフト"))
    }

    func testOnAsksInBothLanguagesAndKeepsTheOriginalNames() async throws {
        let names = try artists(try await repository(originalTitles: true).searchSongs("Taylor Swift"))
        XCTAssertEqual(["en", "ja"], languages.sorted())
        XCTAssertTrue(!names.isEmpty && names.allSatisfy { $0 == "Taylor Swift" }, names.description)
    }

    func testAFailedSecondLanguageFallsBackToTheScreenLanguage() async throws {
        let names = try artists(try await repository(originalTitles: true, englishFails: true).searchSongs("Taylor Swift"))
        XCTAssertTrue(names.contains("テイラー・スウィフト"))
    }

    func testSuggestionsAreNotAskedTwice() async throws {
        // 曲の一覧を返さない読み取り（検索の候補）は、入れていても画面の言語だけ
        let repository = repository(originalTitles: true)
        _ = try await repository.suggestions("Taylor")
        XCTAssertEqual(["ja"], languages)
    }
}
