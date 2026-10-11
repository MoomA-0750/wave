import Foundation
import XCTest
@testable import WaveCore

/// Android 版の MusicParsersTest・SearchParsersTest・HomeParsersTest・ArtistAlbumParsersTest と同じ確かめ方（Windows の MusicParsersTests を移したもの）。
final class MusicParsersTests: XCTestCase {
    func testSearchSongsFirstPage() throws {
        let page = MusicParsers.searchSongs(Fixtures.json("search_songs"))
        XCTAssertEqual(3, page.songs.count)
        let song = page.songs[0]
        XCTAssertFalse(song.videoId.trimmingCharacters(in: .whitespaces).isEmpty)
        XCTAssertFalse(song.artists.isEmpty)
        XCTAssertNotNil(song.album)
        XCTAssertNotNil(song.durationSeconds)
        XCTAssertTrue(try XCTUnwrap(song.thumbnailUrl).hasPrefix("https://"))
        XCTAssertNotNil(page.continuation)
    }

    func testSearchSongsContinuationPage() {
        let page = MusicParsers.searchSongs(Fixtures.json("search_songs_continuation"))
        XCTAssertEqual(3, page.songs.count)
        XCTAssertTrue(page.songs.allSatisfy { !$0.artists.isEmpty && $0.durationSeconds != nil })
        XCTAssertNotNil(page.continuation)
    }

    func testUpNextPages() throws {
        let first = MusicParsers.upNext(Fixtures.json("next_radio"))
        XCTAssertEqual(4, first.songs.count)
        XCTAssertTrue(first.songs.allSatisfy { !$0.artists.isEmpty && $0.durationSeconds != nil })
        XCTAssertTrue(try XCTUnwrap(first.playlistId).hasPrefix("RDAMVM"))
        XCTAssertNotNil(first.continuation)
        let next = MusicParsers.upNext(Fixtures.json("next_radio_continuation"))
        XCTAssertEqual(4, next.songs.count)
        XCTAssertNotEqual(first.songs.map(\.videoId), next.songs.map(\.videoId))
    }

    func testUnknownShapesReturnEmpty() {
        let empty = JSON.obj([:])
        XCTAssertTrue(MusicParsers.searchSongs(empty).songs.isEmpty)
        XCTAssertTrue(MusicParsers.upNext(empty).songs.isEmpty)
        XCTAssertTrue(MusicParsers.searchSuggestions(empty).isEmpty)
        XCTAssertTrue(MusicParsers.homePage(empty).shelves.isEmpty)
        let broken = Fixtures.parse(#"{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"no id"}]}}}]}"#)
        XCTAssertNil(MusicParsers.responsiveListItemSong(broken))
    }

    func testDurations() {
        XCTAssertEqual(262, MusicParsers.parseDuration("4:22"))
        XCTAssertEqual(3723, MusicParsers.parseDuration("1:02:03"))
        XCTAssertNil(MusicParsers.parseDuration("1.1B plays"))
        XCTAssertNil(MusicParsers.parseDuration("2021"))
    }

    func testSuggestionsSplitQueriesAndRowsByKind() {
        let s = MusicParsers.searchSuggestions(Fixtures.json("search_suggestions"))
        XCTAssertEqual("yoasobi", s.queries[0])
        XCTAssertEqual(6, s.queries.count)
        XCTAssertEqual(["UCI6B8NkZKqlFWoiC_xE-hzA"], s.artists.map(\.browseId))
        XCTAssertEqual(4, s.songs.count)
        XCTAssertEqual("夜に駆ける", s.songs[0].title)
        XCTAssertTrue(s.albums.isEmpty)
    }

    func testAlbumAndArtistSearch() {
        let albums = MusicParsers.searchAlbums(Fixtures.json("search_albums"))
        XCTAssertGreaterThanOrEqual(albums.items.count, 10)
        XCTAssertEqual("MPREb_hqiB0KumHYT", albums.items[0].browseId)
        XCTAssertEqual("EP • YOASOBI • 2021", albums.items[0].subtitle)
        XCTAssertNotNil(albums.continuation)
        let artists = MusicParsers.searchArtists(Fixtures.json("search_artists"))
        XCTAssertEqual(["YOASOBI", "Lilas", "Ayase"], artists.items.prefix(3).map(\.name))
        XCTAssertNil(artists.continuation)
    }

    func testHomeShelvesAndChips() {
        let page = MusicParsers.homePage(Fixtures.json("browse_home"))
        XCTAssertEqual(2, page.shelves.count)
        for shelf in page.shelves {
            XCTAssertEqual(10, shelf.items.count)
            XCTAssertTrue(shelf.items.allSatisfy { if case .playlist = $0 { return true } else { return false } })
        }
        guard case .playlist(let p) = page.shelves[0].items[0] else { return XCTFail("プレイリストではない") }
        XCTAssertFalse(p.playlistId.hasPrefix("VL"))
        XCTAssertNotNil(page.continuation)
        XCTAssertEqual(10, page.chips.count)
        XCTAssertEqual("Sleep", page.chips[0].title)
    }

    func testNewReleasesMoodsAndMoodCategory() {
        let albums = MusicParsers.newReleases(Fixtures.json("browse_new_releases"))
        XCTAssertEqual(6, albums.count)
        XCTAssertEqual("MPREb_zjXWoD4kzS1", albums[0].browseId)
        let moods = MusicParsers.moods(Fixtures.json("browse_moods"))
        XCTAssertEqual(["Moods & moments", "Genres"], moods.map(\.title))
        XCTAssertEqual([11, 27], moods.map(\.categories.count))
        XCTAssertEqual(4_288_988_671, moods[0].categories[0].color)
        let category = MusicParsers.homePage(Fixtures.json("browse_mood_category"))
        XCTAssertEqual(2, category.shelves.count)
        XCTAssertTrue(category.shelves.allSatisfy { $0.items.count == 4 })
    }

    func testLibraryArtistRowsOpenTheChannelAndContinuationsAreRead() {
        let shelf = Fixtures.parse(#"{"continuationContents":{"musicShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"n"}]}}}],"navigationEndpoint":{"browseEndpoint":{"browseId":"MPLAUCy","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_LIBRARY_ARTIST"}}}}}}],"continuations":[{"nextContinuationData":{"continuation":"t2"}}]}}}"#)
        let cards = MusicParsers.libraryCards(shelf)
        XCTAssertEqual(1, cards.count)
        guard case .artist(let artist) = cards.first else { return XCTFail("アーティストではない") }
        XCTAssertEqual("UCy", artist.browseId)
        XCTAssertEqual("t2", MusicParsers.libraryContinuation(shelf))
    }

    func testAlbumRowsAreFilledFromTheHeader() throws {
        let page = try XCTUnwrap(MusicParsers.albumPage(Fixtures.json("browse_album"), browseId: "MPREb_test"))
        XCTAssertEqual(4, page.songs.count)
        XCTAssertTrue(try XCTUnwrap(page.playlistId).hasPrefix("OLAK5uy_"))
        XCTAssertEqual(1, page.artists.count)
        XCTAssertTrue(page.artists[0].browseId?.hasPrefix("UC") == true)
        for s in page.songs {
            XCTAssertNotNil(s.durationSeconds)
            XCTAssertEqual(page.artists, s.artists)
            XCTAssertEqual(AlbumRef(title: page.title, browseId: "MPREb_test"), s.album)
            XCTAssertEqual(page.thumbnailUrl, s.thumbnailUrl)
        }
        let greyed = Fixtures.text("browse_album").replacingFirst(
            "\"musicResponsiveListItemRenderer\":{",
            with: "\"musicResponsiveListItemRenderer\":{\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\",")
        XCTAssertEqual(3, MusicParsers.albumPage(Fixtures.parse(greyed), browseId: "MPREb_test")?.songs.count)
    }

    func testArtistPage() throws {
        let page = try XCTUnwrap(MusicParsers.artistPage(Fixtures.json("browse_artist")))
        XCTAssertEqual(5, page.topSongs.count)
        XCTAssertTrue(try XCTUnwrap(page.allSongsPlaylistId).hasPrefix("OLAK5uy_"))
        XCTAssertEqual(2, page.albumShelves.count)
        XCTAssertTrue(page.albumShelves.flatMap(\.albums).allSatisfy { $0.browseId.hasPrefix("MPREb_") })
        XCTAssertNil(MusicParsers.artistPage(Fixtures.json("browse_album")))
        XCTAssertNil(MusicParsers.albumPage(Fixtures.json("search_songs"), browseId: "MPREb_x"))
    }

    func testLikeStatusIsReadOnlyForTheRequestedSong() throws {
        let text = Fixtures.text("next_radio")
        let compact = text.replacingOccurrences(of: " ", with: "")
        let regex = try NSRegularExpression(pattern: #""target":\{"videoId":"([^"]+)""#)
        let match = try XCTUnwrap(regex.firstMatch(in: compact, range: NSRange(compact.startIndex..., in: compact)))
        let videoId = String(compact[Range(match.range(at: 1), in: compact)!])
        XCTAssertEqual(.indifferent, MusicParsers.songInfo(Fixtures.json("next_radio"), videoId: videoId).like)
        XCTAssertNil(MusicParsers.songInfo(Fixtures.json("next_radio"), videoId: "other").like)
        XCTAssertEqual(.like, MusicParsers.songInfo(Fixtures.parse(text.replacingOccurrences(of: "\"INDIFFERENT\"", with: "\"LIKE\"")), videoId: videoId).like)
    }

    func testPlaylistPagesAndSignInPrompt() {
        let page = MusicParsers.playlistPage(Fixtures.json("browse_playlist"))
        XCTAssertFalse(page.songs.isEmpty)
        XCTAssertNotNil(page.title)
        XCTAssertEqual(page.songs.count, page.setVideoIds.count)
        let next = MusicParsers.playlistPage(Fixtures.json("browse_playlist_continuation"))
        XCTAssertFalse(next.songs.isEmpty)
        XCTAssertTrue(MusicParsers.isSignInPrompt(Fixtures.json("browse_library_logged_out")))
        XCTAssertFalse(MusicParsers.isSignInPrompt(Fixtures.json("browse_playlist")))
    }

    func testPlainLyrics() throws {
        let lyrics = try XCTUnwrap(MusicParsers.plainLyrics(Fixtures.json("browse_lyrics")))
        XCTAssertFalse(lyrics.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    func testArtistPostsAreReadAndLiveAnnouncementsAreKept() {
        let root = Fixtures.parse(#"""
            {"contents":{"tabs":[{"tabRenderer":{"content":{"items":[
            {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx1","contentText":{"runs":[{"text":"台北ドーム2DAYS開催決定！\n"},{"text":"チケット販売は9月16日から"}]},
              "publishedTimeText":{"runs":[{"text":"2 日前"}]},
              "backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"//yt3.ggpht.com/small"},{"url":"//yt3.ggpht.com/large"}]}}}}}}},
            {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"Ugkx2","contentText":{"runs":[{"text":"新曲 10/14 配信リリース決定！"}]}}}}}
            ]}}}]}}
            """#)
        let posts = MusicParsers.artistPosts(root)
        XCTAssertEqual(["Ugkx1", "Ugkx2"], posts.map(\.postId))
        XCTAssertEqual("台北ドーム2DAYS開催決定！\nチケット販売は9月16日から", posts[0].text)
        XCTAssertEqual("2 日前", posts[0].publishedText)
        XCTAssertEqual("https://yt3.ggpht.com/large", posts[0].imageUrl)
        XCTAssertEqual("https://www.youtube.com/post/Ugkx1", posts[0].url)
        XCTAssertEqual(["Ugkx1"], LiveNews.of(posts).map(\.postId))
        XCTAssertTrue(LiveNews.isLiveRelated("ASIA DOME TOUR 2026"))
        // 1年以上前の告知は出さない
        var old = posts[0]
        old.publishedText = "2 年前"
        XCTAssertTrue(LiveNews.of([old]).isEmpty)
        XCTAssertTrue(LiveNews.isRecent("11 か月前"))
        XCTAssertFalse(LiveNews.isRecent("1 year ago (edited)"))
        XCTAssertTrue(LiveNews.isRecent(nil))
    }
}
