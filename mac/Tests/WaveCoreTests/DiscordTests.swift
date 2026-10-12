import Foundation
import XCTest
@testable import WaveCore

#if canImport(Darwin)
    import Darwin
#endif

final class DiscordTests: XCTestCase {
    private func track(_ position: Double = 30, playing: Bool = true, id: String = "dQw4w9WgXcQ") -> PresenceTrack {
        PresenceTrack(videoId: id, title: "曲名", artists: "歌手A, 歌手B", largeText: "アルバム",
                      thumbnailUrl: "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", positionSeconds: position, durationSeconds: 200, playing: playing)
    }

    private func object(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }

    func testFramesAreOpLengthAndUtf8Json() throws {
        let frame = DiscordIpc.encode(op: DiscordIpc.opFrame, json: "{\"a\":\"あ\"}")
        XCTAssertEqual([1, 0, 0, 0, 11, 0, 0, 0], [UInt8](frame.prefix(8)))
        var two = frame
        two.append(DiscordIpc.encode(op: DiscordIpc.opHandshake, json: "{}"))
        let first = try XCTUnwrap(try DiscordIpc.decode(two))
        XCTAssertEqual(first.op, 1)
        XCTAssertEqual(first.json, "{\"a\":\"あ\"}")
        XCTAssertEqual(first.consumed, 19)
        let second = try XCTUnwrap(try DiscordIpc.decode(two.subdata(in: first.consumed..<two.count)))
        XCTAssertEqual(second.op, 0)
        XCTAssertEqual(second.json, "{}")
        // 足りなければ待つ・壊れた長さは断る
        XCTAssertNil(try DiscordIpc.decode(frame.prefix(10)))
        XCTAssertThrowsError(try DiscordIpc.decode(Data([1, 0, 0, 0, 255, 255, 255, 127])))
    }

    /// Windows（C#）の DiscordIpc.Encode と同じバイト列。
    func testFramesMatchWindows() {
        XCTAssertEqual([UInt8](DiscordIpc.encode(op: 0, json: "{\"v\":1,\"client_id\":\"1\"}")),
                       [0, 0, 0, 0, 23, 0, 0, 0] + Array("{\"v\":1,\"client_id\":\"1\"}".utf8))
    }

    func testHandshakeCarriesVersionAndClientId() {
        let o = object(DiscordIpc.handshake(clientId: "1234567890"))
        XCTAssertEqual(o["v"] as? Int, 1)
        XCTAssertEqual(o["client_id"] as? String, "1234567890")
    }

    func testPlayingSongIsAListeningActivityWithTimesImageAndButton() throws {
        let o = object(DiscordIpc.setActivity(pid: 42, track: track(), nowUnixMs: 1_000_000_500, nonce: "7"))
        XCTAssertEqual(o["cmd"] as? String, "SET_ACTIVITY")
        XCTAssertEqual(o["nonce"] as? String, "7")
        let args = try XCTUnwrap(o["args"] as? [String: Any])
        XCTAssertEqual(args["pid"] as? Int, 42)
        let a = try XCTUnwrap(args["activity"] as? [String: Any])
        XCTAssertEqual(a["type"] as? Int, 2)
        XCTAssertEqual(a["details"] as? String, "曲名")
        XCTAssertEqual(a["state"] as? String, "歌手A, 歌手B")
        let times = try XCTUnwrap(a["timestamps"] as? [String: Any])
        XCTAssertEqual((times["start"] as? NSNumber)?.int64Value, 999_970_000)
        XCTAssertEqual((times["end"] as? NSNumber)?.int64Value, 1_000_170_000)
        let assets = try XCTUnwrap(a["assets"] as? [String: Any])
        XCTAssertEqual(assets["large_image"] as? String, "https://lh3.googleusercontent.com/abc=w512-h512-l90-rj")
        XCTAssertEqual(assets["large_text"] as? String, "アルバム")
        let buttons = try XCTUnwrap(a["buttons"] as? [[String: Any]])
        XCTAssertEqual(buttons.count, 1)
        XCTAssertEqual(buttons[0]["label"] as? String, "YouTube Music で開く")
        XCTAssertEqual(buttons[0]["url"] as? String, "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
    }

    func testPausedOrNothingClearsTheActivity() {
        XCTAssertNil((object(DiscordIpc.setActivity(pid: 1, track: track(playing: false), nowUnixMs: 0, nonce: "1"))["args"] as? [String: Any])?["activity"])
        XCTAssertNil((object(DiscordIpc.setActivity(pid: 1, track: nil, nowUnixMs: 0, nonce: "1"))["args"] as? [String: Any])?["activity"])
    }

    func testImagesMustBeShortHttps() {
        XCTAssertEqual(DiscordIpc.imageUrl("https://i.ytimg.com/vi/x/hqdefault.jpg"), "https://i.ytimg.com/vi/x/hqdefault.jpg")
        XCTAssertEqual(DiscordIpc.imageUrl("//x/y.jpg"), "https://x/y.jpg")
        XCTAssertNil(DiscordIpc.imageUrl("http://x/y.jpg"))
        XCTAssertNil(DiscordIpc.imageUrl("https://x/" + String(repeating: "a", count: 300)))
        XCTAssertNil(DiscordIpc.imageUrl(nil))
    }

    func testShortAndLongTextsFitDiscordLimits() {
        var t = track()
        t.title = "A"
        t.artists = String(repeating: "x", count: 200)
        let a = DiscordIpc.activity(t, nowUnixMs: 0)
        XCTAssertEqual(a["details"] as? String, "A ")
        XCTAssertEqual((a["state"] as? String)?.count, 128)
    }

    func testPlannerSendsOnChangesAndThrottlesSeeks() {
        var p = PresencePlanner()
        let unix: Int64 = 1_000_000_000
        XCTAssertTrue(p.shouldSend(track(), nowMs: 0, nowUnixMs: unix))
        p.sent(track(), nowMs: 0, nowUnixMs: unix)
        // 同じ曲が進んでいるだけなら送らない
        XCTAssertFalse(p.shouldSend(track(40), nowMs: 10_000, nowUnixMs: unix + 10_000))
        // シーク: 15 秒たつまで待つ
        XCTAssertFalse(p.shouldSend(track(100), nowMs: 5_000, nowUnixMs: unix + 5_000))
        XCTAssertTrue(p.shouldSend(track(100), nowMs: 15_000, nowUnixMs: unix + 15_000))
        // 一時停止・曲の変更はすぐ（ただし前から 2 秒は空ける）
        XCTAssertFalse(p.shouldSend(track(playing: false), nowMs: 1_000, nowUnixMs: unix + 1_000))
        XCTAssertTrue(p.shouldSend(track(playing: false), nowMs: 2_000, nowUnixMs: unix + 2_000))
        XCTAssertTrue(p.shouldSend(track(id: "other"), nowMs: 2_000, nowUnixMs: unix + 2_000))
        p.sent(track(playing: false), nowMs: 2_000, nowUnixMs: unix + 2_000)
        XCTAssertFalse(p.shouldSend(nil, nowMs: 60_000, nowUnixMs: unix + 60_000))
        p.reset()
        XCTAssertTrue(p.shouldSend(nil, nowMs: 60_000, nowUnixMs: unix + 60_000))
    }

    /// Discord の代わりの unix ソケットに、手合わせと SET_ACTIVITY が届く。
    func testTalksToAFakeDiscordSocket() throws {
        let dir = "/tmp"
        let base = "wave-test-\(UUID().uuidString.prefix(8))-"
        let path = dir + "/" + base + "0"
        let server = socket(AF_UNIX, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(server, 0)
        defer {
            close(server)
            unlink(path)
        }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            let bytes = Array(path.utf8)
            raw.copyBytes(from: bytes)
            raw[bytes.count] = 0
        }
        let bound = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(server, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        XCTAssertEqual(bound, 0)
        XCTAssertEqual(listen(server, 1), 0)

        let presence = DiscordPresence(connect: { DiscordIpc.connectSocket(baseName: base, dirs: [dir]) }, pid: 4321)
        defer { presence.stop() }
        presence.update(clientId: "1111", track: track())

        let peer = accept(server, nil, nil)
        XCTAssertGreaterThanOrEqual(peer, 0)
        defer { close(peer) }
        var timeout = timeval(tv_sec: 10, tv_usec: 0)
        setsockopt(peer, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        let hello = try XCTUnwrap(DiscordIpc.readFrame(peer))
        XCTAssertEqual(hello.op, DiscordIpc.opHandshake)
        XCTAssertEqual(object(hello.json)["client_id"] as? String, "1111")
        XCTAssertTrue(DiscordIpc.writeFrame(peer, op: DiscordIpc.opFrame, json: "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\",\"data\":{\"v\":1}}"))

        let set = try XCTUnwrap(DiscordIpc.readFrame(peer))
        XCTAssertEqual(set.op, DiscordIpc.opFrame)
        let args = try XCTUnwrap(object(set.json)["args"] as? [String: Any])
        XCTAssertEqual(args["pid"] as? Int, 4321)
        XCTAssertEqual((args["activity"] as? [String: Any])?["details"] as? String, "曲名")
        XCTAssertEqual(presence.status, .connected)

        // 一時停止で消す
        presence.update(clientId: "1111", track: track(playing: false))
        let clear = try XCTUnwrap(DiscordIpc.readFrame(peer))
        XCTAssertNil((object(clear.json)["args"] as? [String: Any])?["activity"])

        // 切ると、消してから閉じる
        presence.update(clientId: nil, track: nil)
        let last = try XCTUnwrap(DiscordIpc.readFrame(peer))
        XCTAssertNil((object(last.json)["args"] as? [String: Any])?["activity"])
        XCTAssertNil(DiscordIpc.readFrame(peer))
    }
}
