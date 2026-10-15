import XCTest
@testable import WaveCore

/// ライブ配信の検索・HLS の解決・Lo-Fi のタイル（匿名で取得した公開データ。Kotlin の LiveParsersTest と同じ確かめ）。
final class LiveParsersTests: XCTestCase {
    private func masterText() throws -> String {
        try String(contentsOf: Fixtures.directory.appendingPathComponent("hls_master.m3u8"), encoding: .utf8)
    }

    func testWebLiveSearchKeepsOnlyLiveVideos() throws {
        let page = LiveParsers.webSearch(Fixtures.json("search_live_web"))
        XCTAssertEqual(["a_1ezVmZzig", "2BG9FPatcEs", "Jo5fOHMS9WQ"], page.songs.map(\.videoId))
        let first = try XCTUnwrap(page.songs.first)
        XCTAssertTrue(first.isLive)
        XCTAssertNil(first.durationSeconds)
        XCTAssertEqual(["The Japanese Town"], first.artists.map(\.name))
        XCTAssertTrue(first.title.hasPrefix("1980s & 90s"))
        XCTAssertTrue((first.thumbnailUrl ?? "").hasPrefix("https://i.ytimg.com/"))
        XCTAssertNotNil(page.continuation)
    }

    func testMusicSearchMarksLiveBadgeRows() {
        let songs = MusicParsers.searchSongs(Fixtures.json("search_videos_live")).songs
        XCTAssertEqual(["rFZHOHl-L8A", "JD-kMIpDfnY", "ffGvaOKK4eM"], songs.map(\.videoId))
        XCTAssertEqual([true, true, false], songs.map(\.isLive))
        XCTAssertNil(songs[0].durationSeconds)
        XCTAssertEqual(["Lofi Girl"], songs[0].artists.map(\.name))
        XCTAssertEqual(67 * 60 + 10, songs[2].durationSeconds)
    }

    func testPlayerResponseGivesManifestOnlyWhenPlayable() throws {
        let player = Fixtures.json("player_live_android")
        XCTAssertTrue(LiveParsers.isLive(player))
        let url = try XCTUnwrap(LiveParsers.hlsManifestUrl(player))
        XCTAssertTrue(url.hasPrefix("https://manifest.googlevideo.com/api/manifest/hls_variant/"))
        XCTAssertEqual(21540, LiveParsers.expiresInSeconds(player))
        var object = try XCTUnwrap(player.obj)
        object["playabilityStatus"] = .obj(["status": "UNPLAYABLE"])
        XCTAssertNil(LiveParsers.hlsManifestUrl(.object(object)))
        XCTAssertNil(LiveParsers.hlsManifestUrl(.obj([:])))
        XCTAssertFalse(LiveParsers.isLive(.obj([:])))
    }

    func testMasterPlaylistChoosesSmallestAacLcVariant() throws {
        let masterUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/file/index.m3u8"
        let variants = LiveParsers.variants(try masterText(), masterUrl: masterUrl)
        XCTAssertEqual(6, variants.count)
        XCTAssertEqual([290288, 546239, 1209862, 1568726, 2969452, 5420722], variants.map(\.bandwidth))
        let chosen = try XCTUnwrap(LiveParsers.chooseAudioVariant(variants))
        XCTAssertEqual(1209862, chosen.bandwidth)
        XCTAssertTrue(chosen.url.contains("/itag/93/"))
        XCTAssertTrue(isAllowedLiveManifestUrl(chosen.url))
    }

    func testVideoVariantIsTheBest720pOrLower() throws {
        let masterUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/file/index.m3u8"
        let variants = LiveParsers.variants(try masterText(), masterUrl: masterUrl)
        XCTAssertEqual([144, 240, 360, 480, 720, 1080], variants.map(\.height))
        let chosen = try XCTUnwrap(LiveParsers.chooseVideoVariant(variants))
        XCTAssertEqual(720, chosen.height)
        XCTAssertEqual(2969452, chosen.bandwidth)
        // 高さが書かれていなければ、音のための1本と同じ
        let bare = variants.map { HlsVariant(bandwidth: $0.bandwidth, codecs: $0.codecs, url: $0.url) }
        XCTAssertEqual(LiveParsers.chooseAudioVariant(bare), LiveParsers.chooseVideoVariant(bare))
    }

    func testVariantsResolveRelativeUrisAndFallBackToSmallest() {
        let master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900,CODECS="mp4a.40.5,avc1.42C00B"
            low/index.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.5,avc1.4D4015",BANDWIDTH=500
            /abs/index.m3u8
            """
        let variants = LiveParsers.variants(master, masterUrl: "https://manifest.googlevideo.com/api/manifest/hls_variant/master.m3u8?x=1")
        XCTAssertEqual(
            ["https://manifest.googlevideo.com/api/manifest/hls_variant/low/index.m3u8", "https://manifest.googlevideo.com/abs/index.m3u8"],
            variants.map(\.url))
        XCTAssertEqual(500, LiveParsers.chooseAudioVariant(variants)?.bandwidth)
        XCTAssertNil(LiveParsers.chooseAudioVariant([]))
    }

    func testLiveUrlPolicies() {
        XCTAssertTrue(isAllowedLiveManifestUrl("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/x"))
        XCTAssertFalse(isAllowedLiveManifestUrl("http://manifest.googlevideo.com/api/manifest/hls_playlist/x"))
        XCTAssertFalse(isAllowedLiveManifestUrl("https://evil.example.com/api/manifest/hls_playlist/x"))
        XCTAssertFalse(isAllowedLiveManifestUrl("https://manifest.googlevideo.com/videoplayback"))
        XCTAssertTrue(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/videoplayback/id/x/itag/93/sq/1/file/seg.ts"))
        XCTAssertTrue(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/videoplayback?id=1"))
        XCTAssertFalse(isAllowedLiveSegmentUrl("https://rr2---sn-x.googlevideo.com/other"))
        XCTAssertFalse(isAllowedLiveSegmentUrl("https://googlevideo.com.evil.example/videoplayback/x"))
    }

    func testFeaturedPlaylistsReadAndFilterLofi() {
        let page = MusicParsers.searchPlaylists(Fixtures.json("search_playlists_lofi"))
        XCTAssertEqual(["Lofi Loft", "Cozy Jazz", "J-Lofi", "Living Room Lofi"], page.items.map(\.title))
        XCTAssertTrue(page.items.allSatisfy { $0.playlistId.hasPrefix("RDCLAK5uy_") })
        XCTAssertEqual(["Lofi Loft", "J-Lofi", "Living Room Lofi"], LofiGenre.featured(page.items).map(\.title))
    }

    func testLofiTileGoesFirstInGenres() throws {
        let sections = MusicParsers.moods(Fixtures.json("browse_moods"))
        let added = LofiGenre.addTo(sections)
        let genres = try XCTUnwrap(added.first { $0.title == "Genres" })
        XCTAssertEqual(LofiGenre.category, genres.categories.first)
        XCTAssertEqual(sections.first { $0.title == "Genres" }?.categories, Array(genres.categories.dropFirst()))
        XCTAssertEqual(sections.first?.categories, added.first?.categories)
        // 二度足さない
        XCTAssertEqual(added.map(\.categories), LofiGenre.addTo(added).map(\.categories))
        // 見出しが分からなければ最後の格子に足す
        let unknown = sections.map { MoodSection(title: "x", categories: $0.categories) }
        XCTAssertEqual(LofiGenre.params, LofiGenre.addTo(unknown).last?.categories.first?.params)
        XCTAssertTrue(LofiGenre.addTo([]).isEmpty)
    }

    func testSongDefaultsToNotLive() {
        let song = Song(videoId: "x", title: "t", artists: [], album: nil, durationSeconds: nil, thumbnailUrl: nil)
        XCTAssertFalse(song.isLive)
    }
}
