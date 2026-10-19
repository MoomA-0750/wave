import XCTest
@testable import WaveCore

final class StreamTests: XCTestCase {
    func testOnlyGooglevideoPlaybackUrlsAreAllowed() {
        XCTAssertTrue(StreamResolver.isAllowedMediaUrl("https://rr1---sn-x.googlevideo.com/videoplayback?a=1"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("http://rr1---sn-x.googlevideo.com/videoplayback"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("https://evil.com/videoplayback"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("https://x.googlevideo.com.evil.com/videoplayback"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("https://x.googlevideo.com:8443/videoplayback"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("https://x.googlevideo.com/other"))
        // ここからは macOS 版で足した確かめ
        XCTAssertTrue(StreamResolver.isAllowedMediaUrl("https://X.GoogleVideo.com:443/videoplayback"))
        XCTAssertFalse(StreamResolver.isAllowedMediaUrl("https://user:pw@x.googlevideo.com/videoplayback"))
    }

    func testUnwantedParametersAreRemovedKeepingOrder() {
        XCTAssertEqual("https://h/videoplayback?a=1&b=%2F", StreamResolver.removeQueryParameters("https://h/videoplayback?pot=x&a=1&ump=1&b=%2F", names: ["pot", "ump"]))
        XCTAssertEqual("https://h/v", StreamResolver.removeQueryParameters("https://h/v?pot=1", names: ["pot"]))
    }

    private func format(_ text: String) -> JSON { try! JSON.parse(text) }

    func testAudioCandidatesPreferAacAndDefaultTrack() {
        func f(_ itag: Int, _ mime: String, _ extra: String = "") -> JSON {
            format(#"{"itag":\#(itag),"mimeType":"\#(mime)","url":"https://x.googlevideo.com/videoplayback"\#(extra)}"#)
        }
        let formats = [
            f(251, "audio/webm"), f(140, "audio/mp4"), f(141, "audio/mp4", #","isDrc":true"#), f(137, "video/mp4"),
            f(250, "audio/webm", #","audioTrack":{"audioIsDefault":false}"#),
        ]
        XCTAssertEqual([140, 251], StreamResolver.audioCandidates(formats).map { $0.path("itag").long! })
    }

    func testFaradayRowsAreReadByHashOrAliasAndValidated() {
        let text = #"{"schemaVersion":1,"players":{"aaaaaaaa":{"sig":"jL(5,5551,INPUT)","nClass":"RJ","sts":20726,"aliases":["bbbbbbbb"]},"cccccccc":{"sig":"alert(1)","nClass":"RJ","sts":1}}}"#
        XCTAssertEqual(PlayerCipherConfig(sigExpression: "jL(5,5551,INPUT)", nClass: "RJ", signatureTimestamp: 20726), PlayerCipherProvider.parseFaraday(text, hash: "aaaaaaaa"))
        XCTAssertEqual(20726, PlayerCipherProvider.parseFaraday(text, hash: "bbbbbbbb")?.signatureTimestamp)
        XCTAssertNil(PlayerCipherProvider.parseFaraday(text, hash: "cccccccc"))
        XCTAssertNil(PlayerCipherProvider.parseFaraday(text.replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":2"), hash: "aaaaaaaa"))
    }

    func testStatsUrlIsRewrittenToMusicHost() {
        let target = PlaybackStats.target("https://s.youtube.com/api/stats/playback?ns=yt&docid=x&list=PL1&cpn=old", cpn: "CPN", cmt: 12.5)
        XCTAssertEqual("https://music.youtube.com/api/stats/playback?ns=yt&docid=x&prettyPrint=false&ver=2&c=WEB_REMIX&cpn=CPN&cmt=12.5", target)
        XCTAssertNil(PlaybackStats.target("https://evil.com/api/stats/playback", cpn: "C", cmt: 0))
        XCTAssertNil(PlaybackStats.target("https://s.youtube.com/other", cpn: "C", cmt: 0))
        // 整数の位置は小数点を書かない（C# の InvariantCulture と同じ）
        XCTAssertTrue(PlaybackStats.target("https://s.youtube.com/api/stats/playback?ns=yt", cpn: "C", cmt: 30)!.hasSuffix("&cmt=30"))
    }

    func testCpnIsSixteenUrlSafeCharacters() {
        let cpn = PlaybackStats.newCpn()
        XCTAssertNotNil(cpn.wholeMatch(of: #/[A-Za-z0-9_-]{16}/#))
        XCTAssertNotEqual(cpn, PlaybackStats.newCpn())
    }

    func testVideoCandidatesAreH264UpTo720pHighestFirst() {
        func f(_ itag: Int, _ mime: String, _ height: Int) -> JSON {
            format(#"{"itag":\#(itag),"mimeType":"\#(mime)","height":\#(height),"url":"https://x.googlevideo.com/videoplayback"}"#)
        }
        let picked = StreamResolver.videoCandidates([
            f(137, "video/mp4; codecs=avc1.640028", 1080),
            f(136, "video/mp4; codecs=avc1.4d401f", 720),
            f(135, "video/mp4; codecs=avc1.4d401e", 480),
            f(247, "video/webm; codecs=vp9", 720),
            f(398, "video/mp4; codecs=av01.0.05M.08", 720),
            f(140, "audio/mp4; codecs=mp4a.40.2", 0),
        ])
        XCTAssertEqual([136, 135], picked.map { $0.path("itag").long! })
    }

    func testDashInfoIsReadFromTheFormat() {
        let dash = StreamResolver.dashOf(format(#"{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","initRange":{"start":"0","end":"631"},"indexRange":{"start":"632","end":"1023"},"bitrate":130000,"audioSampleRate":"44100","approxDurationMs":"215046"}"#))
        XCTAssertEqual(DashInfo(initStart: 0, initEnd: 631, indexStart: 632, indexEnd: 1023, bitrate: 130000, sampleRate: 44100, durationMs: 215046, codecs: "mp4a.40.2"), dash)
        XCTAssertNil(StreamResolver.dashOf(format(#"{"itag":140}"#)))
    }

    func testMusicVideoMapsSongTimeToVideoTime() {
        let video = MusicVideo(videoId: "mv", segments: [
            TimeSegment(primaryStartMs: 0, counterpartStartMs: 5000, durationMs: 10000),
            TimeSegment(primaryStartMs: 10000, counterpartStartMs: 17000, durationMs: 20000),
        ])
        XCTAssertEqual(5000, video.videoPositionOf(0))
        XCTAssertEqual(18000, video.videoPositionOf(11000))
        XCTAssertNil(video.videoPositionOf(40000))
        XCTAssertEqual(1234, MusicVideo(videoId: "self", segments: []).videoPositionOf(1234))
    }

    func testVideoSyncSeeksWhenFarAndNudgesSpeedWhenNear() {
        XCTAssertEqual(.hide, VideoSync.decide(targetMs: nil, videoMs: 0, playing: true, videoReady: true))
        XCTAssertEqual(.seek(toMs: 5000, play: true), VideoSync.decide(targetMs: 5000, videoMs: 3000, playing: true, videoReady: true))
        XCTAssertEqual(.run(speed: 1, play: true), VideoSync.decide(targetMs: 5000, videoMs: 5020, playing: true, videoReady: true))
        // 映像が 200ms 遅れていれば、少し速く
        guard case .run(let speed, true) = VideoSync.decide(targetMs: 5000, videoMs: 4800, playing: true, videoReady: true) else { return XCTFail() }
        XCTAssertEqual(1.1, speed, accuracy: 0.0005)
        XCTAssertEqual(.run(speed: 1, play: true), VideoSync.decide(targetMs: 5000, videoMs: 3000, playing: true, videoReady: false))
        XCTAssertEqual(.seek(toMs: 5000, play: false), VideoSync.decide(targetMs: 5000, videoMs: 4000, playing: false, videoReady: true))
    }

    // MARK: 解読器（JavaScriptCore）。本物のプレイヤーの形をまねた小さな JS で確かめる

    /// 署名は逆順、n は逆順に x を足す。jL と g.RJ は即時関数の中にしか無い（差し込みが中に入らないと届かない）。
    private static let fakePlayer = """
        var _yt_player = {};
        (function(g){
        var jL=function(a,b,s){return s.split("").reverse().join("");};
        g.RJ=function(u,x){this.u=u;};
        g.RJ.prototype.get=function(k){var m=/[?&]n=([^&]+)/.exec(this.u);return m?m[1].split("").reverse().join("")+"x":null;};
        })(_yt_player);
        """
    private static let fakeConfig = PlayerCipherConfig(sigExpression: "jL(5,5551,INPUT)", nClass: "RJ", signatureTimestamp: 20726)

    func testModifiedPlayerScriptInsertsExportsInsideTheIife() {
        let script = CipherSolver.buildModifiedPlayerScript(StreamTests.fakePlayer, config: StreamTests.fakeConfig)
        XCTAssertTrue(script.hasSuffix("; window._cipherSigFunc=function(sig){try{return jL(5,5551,sig);}catch(e){return null;}};"
            + "window._nTransformFunc=function(n){try{return (function(n){try{var u=new g.RJ('https://x.googlevideo.com/videoplayback?n='+n,true);"
            + "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(n);}catch(e){return n;}};  })(_yt_player);"))
        // 終わりの印が無ければ末尾に足す
        XCTAssertTrue(CipherSolver.buildModifiedPlayerScript("var a=1;", config: StreamTests.fakeConfig).hasPrefix("var a=1;\n; window._cipherSigFunc="))
    }

    func testCipherSolverRunsThePlayerInJavaScriptCore() throws {
        let solver = try CipherSolver.create(playerCode: StreamTests.fakePlayer, config: StreamTests.fakeConfig)
        XCTAssertEqual("cba", solver.solveSignature("abc"))
        XCTAssertEqual("ollehx", solver.solveN("hello"))
        XCTAssertNil(solver.solveN(String(repeating: "a", count: 64 * 1024 + 1)))
    }

    func testCipherSolverRejectsAPlayerWhoseNTransformDoesNotWork() {
        let broken = StreamTests.fakePlayer.replacingOccurrences(of: "+\"x\"", with: "+\"!\"")
        XCTAssertThrowsError(try CipherSolver.create(playerCode: broken, config: StreamTests.fakeConfig))
        XCTAssertThrowsError(try CipherSolver.create(playerCode: "throw new Error('x');", config: StreamTests.fakeConfig))
    }

    func testDecipherSolvesSignatureAndN() throws {
        let solver = try CipherSolver.create(playerCode: StreamTests.fakePlayer, config: StreamTests.fakeConfig)
        let cipher = "s=abc&sp=sig&url=https%3A%2F%2Fx.googlevideo.com%2Fvideoplayback%3Fn%3Dhello%26a%3D1"
        let f = format(#"{"itag":140,"mimeType":"audio/mp4","signatureCipher":"\#(cipher)"}"#)
        XCTAssertEqual("https://x.googlevideo.com/videoplayback?n=ollehx&a=1&sig=cba", StreamResolver.decipher(f, solver: solver))
        // 署名の無い URL は n だけ変える
        XCTAssertEqual("https://x.googlevideo.com/videoplayback?a=1&n=ollehx", StreamResolver.decipher(format(#"{"url":"https://x.googlevideo.com/videoplayback?a=1&n=hello"}"#), solver: solver))
        // 許していない先・おかしな sp は解かない
        XCTAssertNil(StreamResolver.decipher(format(#"{"signatureCipher":"s=abc&url=https%3A%2F%2Fevil.com%2Fvideoplayback"}"#), solver: solver))
        XCTAssertNil(StreamResolver.decipher(format(#"{"signatureCipher":"s=abc&sp=a%26b&url=https%3A%2F%2Fx.googlevideo.com%2Fvideoplayback"}"#), solver: solver))
    }

    func testResolvedStreamDoesNotPrintItsUrl() {
        let stream = ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: 10, url: "https://x.googlevideo.com/videoplayback?sig=SECRET",
                                    expiresAtMs: 0, statsUrl: "https://s.youtube.com/api/stats/playback?SECRET2", generation: 0)
        var dumped = ""
        dump(stream, to: &dumped)
        for text in [stream.description, stream.debugDescription, String(describing: stream), String(reflecting: stream), dumped] {
            XCTAssertFalse(text.contains("SECRET"), text)
        }
    }

    // MARK: RangeStream（HTTP は差し替える）

    private static let mediaUrl = "https://x.googlevideo.com/videoplayback?id=1"

    private func stream(length: Int64?) -> ResolvedStream {
        ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: length, url: StreamTests.mediaUrl, expiresAtMs: 0, statsUrl: nil, generation: 0)
    }

    /// Range の要求に、0..<size の各バイトが (i % 251) の本文で答える。
    private func rangeServer(size: Int, status: @escaping (URLRequest) -> Int = { _ in 206 }) -> URLSession {
        let body = Data((0..<size).map { UInt8($0 % 251) })
        return StubURLProtocol.session { request in
            let code = status(request)
            if code != 206 { return (code, [:], Data()) }
            let range = request.value(forHTTPHeaderField: "Range")!.dropFirst("bytes=".count).split(separator: "-").map { Int($0)! }
            let end = min(range[1], size - 1)
            return (206, ["Content-Range": "bytes \(range[0])-\(end)/\(size)"], body.subdata(in: range[0]..<(end + 1)))
        }
    }

    func testRangeStreamReadsAcrossChunksAndSeeks() async throws {
        let size = RangeStream.chunkSize + 1000
        let session = rangeServer(size: size)
        let reader = try await RangeStream.open(session: session, stream: stream(length: Int64(size)))
        XCTAssertEqual(Int64(size), reader.length)
        reader.seek(Int64(RangeStream.chunkSize - 10), origin: .begin)
        let data = try await reader.read(count: 30)
        XCTAssertEqual(30, data.count)
        XCTAssertEqual((RangeStream.chunkSize - 10..<RangeStream.chunkSize + 20).map { UInt8($0 % 251) }, [UInt8](data))
        XCTAssertEqual(Int64(RangeStream.chunkSize + 20), reader.position)
        // 終わりを越えては読まない
        let tail = try await reader.read(offset: Int64(size - 5), length: 100)
        XCTAssertEqual(5, tail.count)
        let empty = try await reader.read(count: 10)
        XCTAssertTrue(empty.isEmpty)
        XCTAssertEqual(Int64(size) - 3, reader.seek(-3, origin: .end))
        XCTAssertEqual(Int64(size) - 103, reader.seek(-100, origin: .current))
        XCTAssertEqual(0, reader.seek(-10, origin: .begin))
        let range = try await reader.readRange(start: 10, end: 19)
        XCTAssertEqual((10..<20).map { UInt8($0 % 251) }, [UInt8](range))
        let sent = StubURLProtocol.recorded
        XCTAssertTrue(sent.allSatisfy { $0.value(forHTTPHeaderField: "User-Agent") == InnerTubeClient.userAgent })
        XCTAssertTrue(sent.allSatisfy { $0.value(forHTTPHeaderField: "Cookie") == nil })
    }

    func testRangeStreamLearnsTheLengthFromContentRange() async throws {
        let reader = try await RangeStream.open(session: rangeServer(size: 1234), stream: stream(length: nil))
        XCTAssertEqual(1234, reader.length)
        XCTAssertEqual("bytes=0-0", StubURLProtocol.recorded.first?.value(forHTTPHeaderField: "Range"))
        XCTAssertNil(RangeStream.contentRangeLength("bytes 0-0/*"))
    }

    func testRangeStreamReportsRejectionAndCanContinueWithANewUrl() async throws {
        let session = rangeServer(size: 4000) { request in request.url!.absoluteString.contains("fresh") ? 206 : 403 }
        let reader = try await RangeStream.open(session: session, stream: stream(length: 4000))
        do {
            _ = try await reader.read(count: 10)
            XCTFail("拒否が知らされない")
        } catch let e as StreamRejectedError {
            XCTAssertEqual(403, e.status)
            XCTAssertFalse(String(describing: e).contains("googlevideo"))
        }
        // 拒否も2回まで送り直す（Windows 版と同じ）
        XCTAssertEqual(3, StubURLProtocol.recorded.count)
        let fresh = ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: 4000, url: "https://y.googlevideo.com/videoplayback?fresh=1",
                                   expiresAtMs: 0, statsUrl: nil, generation: 0)
        try reader.replaceSource(fresh)
        let data = try await reader.read(count: 10)
        XCTAssertEqual((0..<10).map { UInt8($0 % 251) }, [UInt8](data))
        // 長さの違う形式・許していない先には差し替えない
        XCTAssertThrowsError(try reader.replaceSource(ResolvedStream(videoId: "v", itag: 251, mimeType: "audio/webm", contentLength: 5000,
                                                                     url: "https://y.googlevideo.com/videoplayback", expiresAtMs: 0, statsUrl: nil, generation: 0)))
        XCTAssertThrowsError(try reader.replaceSource(ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: 4000,
                                                                     url: "https://evil.com/videoplayback", expiresAtMs: 0, statsUrl: nil, generation: 0)))
    }

    func testRangeStreamFollowsARedirectToAnotherGooglevideoServerOnce() async throws {
        let size = 4000
        let body = Data((0..<size).map { UInt8($0 % 251) })
        let session = StubURLProtocol.session { request in
            let url = request.url!.absoluteString
            if url.contains("evil") { return (206, [:], Data()) }
            if !url.contains("r2.googlevideo.com") { return (302, ["Location": "https://r2.googlevideo.com/videoplayback?id=1"], Data()) }
            let range = request.value(forHTTPHeaderField: "Range")!.dropFirst("bytes=".count).split(separator: "-").map { Int($0)! }
            let end = min(range[1], size - 1)
            return (206, ["Content-Range": "bytes \(range[0])-\(end)/\(size)"], body.subdata(in: range[0]..<(end + 1)))
        }
        let reader = try await RangeStream.open(session: session, stream: stream(length: Int64(size)))
        let data = try await reader.read(count: 10)
        XCTAssertEqual((0..<10).map { UInt8($0 % 251) }, [UInt8](data))
        // 2回目からは行き先から直接読む
        _ = try await reader.readRange(start: 100, end: 109)
        XCTAssertEqual(3, StubURLProtocol.recorded.count)
        XCTAssertTrue(StubURLProtocol.recorded.last!.url!.absoluteString.contains("r2.googlevideo.com"))

        // googlevideo の外への転送は追わない
        let outside = StubURLProtocol.session { _ in (302, ["Location": "https://evil.com/videoplayback"], Data()) }
        let refused = try await RangeStream.open(session: outside, stream: stream(length: Int64(size)))
        do {
            _ = try await refused.readRange(start: 0, end: 9)
            XCTFail("外への転送を追った")
        } catch let e as StreamRejectedError {
            XCTAssertEqual(302, e.status)
        }
        XCTAssertFalse(StubURLProtocol.recorded.contains { $0.url!.host == "evil.com" })
    }

    func testRangeStreamRefusesUrlsOutsideGooglevideo() async {
        let bad = ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: 10, url: "https://evil.com/videoplayback", expiresAtMs: 0, statsUrl: nil, generation: 0)
        do {
            _ = try await RangeStream.open(session: rangeServer(size: 10), stream: bad)
            XCTFail()
        } catch {
            XCTAssertEqual(0, (error as? StreamRejectedError)?.status)
        }
    }

    // MARK: 解決（/player の応答は差し替える）

    func testIsFreshUsesTheGivenClock() {
        let resolver = StreamResolver(client: InnerTubeClient(), cipher: PlayerCipherProvider(session: .shared, nowMs: { 0 }), nowMs: { 1000 })
        let s = ResolvedStream(videoId: "v", itag: 140, mimeType: "audio/mp4", contentLength: nil, url: "", expiresAtMs: 70_000, statsUrl: nil, generation: 0)
        XCTAssertTrue(resolver.isFresh(s, marginMs: 60_000))
        XCTAssertFalse(resolver.isFresh(s, marginMs: 69_000))
    }

    func testBadVideoIdIsRejectedBeforeAnyRequest() async throws {
        let resolver = StreamResolver(client: InnerTubeClient(), cipher: PlayerCipherProvider(session: .shared, nowMs: { 0 }), nowMs: { 0 })
        let result = try await resolver.resolve(videoId: "short")
        XCTAssertEqual(.parse, result.failure)
    }
}
