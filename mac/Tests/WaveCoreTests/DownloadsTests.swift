import XCTest
@testable import WaveCore

/// ダウンロードとスマートダウンロード（ADR-0016）の、通信に依らない決まり。
final class DownloadsTests: XCTestCase {
    private func song(_ id: String, artist: String = "A", artistId: String? = nil, live: Bool = false) -> Song {
        Song(videoId: id, title: "t" + id, artists: [ArtistRef(name: artist, browseId: artistId)], album: nil, durationSeconds: 200, thumbnailUrl: nil, isLive: live)
    }

    private func ids(_ songs: [Song]) -> [String] { songs.map(\.videoId) }

    // MARK: 目録

    func testFileStaysWhileAnyOwnerRemains() {
        var m = DownloadManifest()
        m.addSingle(DownloadSong(song("s1")))
        m.setCollection(.playlist, id: "PL1", title: "p", thumbnailUrl: nil, songs: [DownloadSong(song("s1")), DownloadSong(song("s2"))], nowMs: 1)
        m.setSmart([(DownloadSong(song("s2")), .frequent), (DownloadSong(song("s3")), .discover)], nowMs: 2)
        XCTAssertEqual(["s1", "s2", "s3"], m.wanted)
        XCTAssertEqual(2, m.owners("s1"))
        XCTAssertEqual(2, m.owners("s2"))
        // 1曲ずつの印を外しても、プレイリストに入っているので残る
        XCTAssertEqual([], m.removeSingle("s1"))
        XCTAssertTrue(m.isOwned("s1"))
        // プレイリストを外すと、ほかの持ち主のいない s1 だけを消す（s2 はスマートが持つ）
        XCTAssertEqual(["s1"], m.removeCollection("PL1"))
        XCTAssertEqual(["s2", "s3"], m.wanted)
        // スマートの選び直しで外れた曲は消すが、手で取った曲は消さない
        m.addSingle(DownloadSong(song("s3")))
        XCTAssertEqual(["s2"], m.setSmart([(DownloadSong(song("s4")), .recommended)], nowMs: 3))
        XCTAssertEqual(["s3", "s4"], m.wanted)
        XCTAssertTrue(m.isManual("s3"))
        XCTAssertFalse(m.isManual("s4"))
        XCTAssertEqual(["s4"], m.clearSmart())
    }

    func testCollectionSyncAddsNewAndDropsRemoved() {
        var m = DownloadManifest()
        m.setCollection(.album, id: "MPRE1", title: "a", thumbnailUrl: "x", songs: ["1", "2", "3"].map { DownloadSong(song($0)) }, nowMs: 5)
        let diff = m.setCollection(.album, id: "MPRE1", title: "", thumbnailUrl: nil, songs: ["2", "3", "4", "4"].map { DownloadSong(song($0)) }, nowMs: 9)
        XCTAssertEqual(CollectionDiff(added: ["4"], removed: ["1"]), diff)
        XCTAssertEqual(["2", "3", "4"], m.collection("MPRE1")?.trackIds)
        // 空の題名・画像では前のものを残し、足した時刻は変えない
        XCTAssertEqual("a", m.collection("MPRE1")?.title)
        XCTAssertEqual("x", m.collection("MPRE1")?.thumbnailUrl)
        XCTAssertEqual(5, m.collection("MPRE1")?.addedAtMs)
        XCTAssertEqual(["1"], m.prune())
    }

    func testManifestRoundTripsAndReadsOlderFiles() throws {
        var m = DownloadManifest()
        m.addSingle(DownloadSong(song("ext:https://example.com/a.mp3"), episode: true))
        m.setCollection(.liked, id: "LM", title: "Liked", thumbnailUrl: nil, songs: [DownloadSong(song("s1"))], nowMs: 1)
        let back = try XCTUnwrap(DownloadManifest.decode(try XCTUnwrap(m.encode())))
        XCTAssertEqual(m, back)
        XCTAssertTrue(back.songs["ext:https://example.com/a.mp3"]?.episode == true)
        // 項目の足りない（前の版の）ファイル
        let old = try XCTUnwrap(DownloadManifest.decode(Data(#"{"singles":["s9"]}"#.utf8)))
        XCTAssertEqual(["s9"], old.singles)
        XCTAssertTrue(old.collections.isEmpty)
        XCTAssertTrue(old.smartDue(nowMs: 1))
    }

    // MARK: スマートダウンロード

    func testPickTakesOneFromEachCategoryInTurn() {
        let picked = SmartDownloads.pick([
            .frequent: [song("f1", artist: "a1"), song("f2", artist: "a2"), song("f3", artist: "a3")],
            .discover: [song("d1", artist: "b1"), song("d2", artist: "b2")],
            .recommended: [song("r1", artist: "c1"), song("r2", artist: "c2")],
        ], count: 5)
        XCTAssertEqual(["f1", "d1", "r1", "f2", "d2"], picked.map(\.song.videoId))
        XCTAssertEqual([.frequent, .discover, .recommended, .frequent, .discover], picked.map(\.category))
    }

    func testPickCapsArtistsDedupesAndFillsFromOtherCategories() {
        let picked = SmartDownloads.pick([
            .frequent: [song("f1", artist: "X", artistId: "UCx"), song("f2", artist: "X", artistId: "UCx"), song("f3", artist: "X", artistId: "UCx"),
                        song("f4", artist: "y"), song("f5", artist: "z"), song("f6", artist: "w")],
            // 「いつも聴いている」に先に入った曲は、そちらの分類に入れる
            .discover: [song("f1", artist: "X", artistId: "UCx"), song("live", artist: "q", live: true)],
            .recommended: [],
        ], count: 10)
        XCTAssertEqual(["f1", "f2", "f4", "f5", "f6"], picked.map(\.song.videoId))
        XCTAssertTrue(picked.allSatisfy { $0.category == .frequent })
    }

    func testFrequentRanksByCountThenRecency() {
        var counts = PlayCounts()
        counts.record(DownloadSong(song("a")), nowMs: 1000)
        counts.record(DownloadSong(song("b")), nowMs: 2000)
        counts.record(DownloadSong(song("b")), nowMs: 3000)
        counts.record(DownloadSong(song("c")), nowMs: 9_000_000)
        // 履歴にだけ出る h は1回。履歴の上ほど新しい
        let ranked = SmartDownloads.frequent(counts: counts, history: [song("h"), song("a"), song("live", live: true)], nowMs: 10_000_000)
        XCTAssertEqual(["b", "h", "c", "a"], ids(ranked))
    }

    func testPlayCountsForgetTheOldestBeyondTheLimit() {
        var counts = PlayCounts()
        for i in 0..<(PlayCounts.maxSongs + 3) { counts.record(DownloadSong(song("v\(i)")), nowMs: Int64(i)) }
        XCTAssertEqual(PlayCounts.maxSongs, counts.entries.count)
        XCTAssertNil(counts.entries["v0"])
        XCTAssertNil(counts.entries["v2"])
        XCTAssertNotNil(counts.entries["v3"])
    }

    func testDiscoverSkipsKnownSongsAndAlternatesSeeds() {
        let radios = [[song("k1"), song("n1"), song("n2")], [song("m1"), song("n1"), song("m2")]]
        XCTAssertEqual(["n1", "m1", "n2", "m2"], ids(SmartDownloads.discover(radios: radios, known: ["k1"])))
    }

    func testRecommendedUsesSongShelvesAndFirstMix() {
        let mix = PlaylistSummary(playlistId: "RDTMAK1", title: "Mix", subtitle: nil, thumbnailUrl: nil)
        let shelves = [
            HomeShelf(title: "albums", strapline: nil, items: [.album(AlbumSummary(browseId: "MP", title: "a", subtitle: nil, thumbnailUrl: nil)), .song(song("x"))]),
            HomeShelf(title: "quick picks", strapline: nil, items: [.song(song("q1")), .song(song("q2")), .song(song("q1"))]),
            HomeShelf(title: "mixes", strapline: nil, items: [.playlist(PlaylistSummary(playlistId: "PLz", title: "z", subtitle: nil, thumbnailUrl: nil)), .playlist(mix)]),
        ]
        XCTAssertEqual(["q1", "q2"], ids(SmartDownloads.recommended(shelves: shelves)))
        XCTAssertEqual("RDTMAK1", SmartDownloads.firstMix(shelves: shelves)?.playlistId)
    }

    func testNetworkPolicy() {
        XCTAssertTrue(DownloadNetworkPolicy.unmetered.allows(connected: true, metered: false))
        XCTAssertFalse(DownloadNetworkPolicy.unmetered.allows(connected: true, metered: true))
        XCTAssertTrue(DownloadNetworkPolicy.any.allows(connected: true, metered: true))
        XCTAssertFalse(DownloadNetworkPolicy.any.allows(connected: false, metered: false))
    }

    // MARK: 置き場

    private var directory: URL!

    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("wave-downloads-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: directory) }

    private func stream(_ videoId: String, length: Int64) -> ResolvedStream {
        ResolvedStream(videoId: videoId, itag: 140, mimeType: "audio/mp4", contentLength: length, url: "https://x.googlevideo.com/videoplayback?id=1",
                       expiresAtMs: 0, statsUrl: nil, generation: 0,
                       dash: DashInfo(initStart: 0, initEnd: 9, indexStart: 10, indexEnd: 19, bitrate: 1, sampleRate: 44100, durationMs: 1000, codecs: "mp4a.40.2"))
    }

    func testWholeSongIsKeptAndPlayedBackWithoutTheNetwork() async throws {
        let size = 1_300_000
        let body = Data((0..<size).map { UInt8($0 % 251) })
        let session = StubURLProtocol.session { request in
            let range = request.value(forHTTPHeaderField: "Range")!.dropFirst("bytes=".count).split(separator: "-").map { Int($0)! }
            let end = min(range[1], size - 1)
            return (206, ["Content-Range": "bytes \(range[0])-\(end)/\(size)"], body.subdata(in: range[0]..<(end + 1)))
        }
        let store = DownloadStore(directory: directory, nowMs: { 7 })
        store.prepare(DownloadSong(song("ddddddddddd")))
        let reader = try await RangeStream.open(session: session, stream: stream("ddddddddddd", length: Int64(size)), cache: store)
        try await reader.warm(upTo: Int64(size))
        XCTAssertNil(store.completed("ddddddddddd"))
        XCTAssertTrue(store.finish("ddddddddddd"))
        let file = try XCTUnwrap(store.completed("ddddddddddd"))
        XCTAssertEqual("tddddddddddd", file.song.title)
        XCTAssertEqual(Int64(size), store.totalBytes)

        // 開き直した置き場から、URL を調べずに最後まで読める
        let reopened = DownloadStore(directory: directory, nowMs: { 8 })
        XCTAssertEqual(["ddddddddddd"], reopened.completedIds)
        let resolved = Counter()
        let local = RangeStream.openCached(session: session, format: file.format, cache: reopened) {
            resolved.increment()
            throw StreamRejectedError(status: 0)
        }
        let tail = try await local.readRange(start: Int64(size - 100), end: Int64(size - 1))
        XCTAssertEqual(Array(body.suffix(100)), [UInt8](tail))
        XCTAssertEqual(0, resolved.value)

        reopened.remove("ddddddddddd")
        XCTAssertEqual(0, reopened.totalBytes)
        XCTAssertTrue(reopened.completedIds.isEmpty)
    }

    func testEpisodesAreNamedByTheHashOfTheAudioUrl() throws {
        let id = Feed.audioVideoId("https://example.com/ep 1.mp3")
        let name = DownloadStore.fileName(id)
        XCTAssertEqual(64, name.count)
        XCTAssertTrue(name.allSatisfy { $0.isHexDigit })
        XCTAssertEqual("abcdefghijk", DownloadStore.fileName("abcdefghijk"))

        let store = DownloadStore(directory: directory, nowMs: { 1 })
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data(repeating: 1, count: 5000).write(to: temp)
        try store.storeWhole(DownloadSong(song(id), episode: true), mimeType: "audio/mpeg", from: temp)
        XCTAssertEqual(name + ".bin", store.bytesUrl(id).lastPathComponent)
        XCTAssertEqual(5000, store.completed(id)?.length)
        XCTAssertEqual(true, DownloadStore(directory: directory, nowMs: { 1 }).completed(id)?.song.episode)
        store.clear()
        XCTAssertNil(store.completed(id))
    }
}
