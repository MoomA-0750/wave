import Foundation
import XCTest
@testable import WaveCore

/// Android の AiAgentSpecTest・Windows の AiAgentSpecTests と同じ。
final class AiAgentSpecTests: XCTestCase {
    private let spec: AiAgentSpec = {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../ai/agent.json").standardizedFileURL
        return try! AiAgentSpec(json: try! JSON.parse(try! Data(contentsOf: url)))
    }()

    func testBodyCarriesTheSystemPromptAndOnlyWavesTools() {
        let body = spec.body(task: "似た曲を探して", history: [], song: nil, lyrics: nil)
        XCTAssertTrue(body["system"]?.str?.hasPrefix("あなたは音楽プレイヤー wave") ?? false)
        XCTAssertEqual(["now_playing", "get_queue", "search_songs", "liked_songs", "show_songs", "play_songs", "add_to_queue", "list_playlists",
                        "create_playlist", "add_to_playlist"], body["tools"]?.arr?.compactMap { $0["name"]?.str })
        XCTAssertEqual("似た曲を探して", body["prompt"]?.str)
    }

    func testPromptPutsLyricsAndTheConversationBeforeTheTask() {
        let prompt = spec.prompt(task: "続けて", history: [("user", "意味は？"), ("assistant", "別れの歌です")], song: nil, lyrics: "[00:12] 行")
        XCTAssertEqual("<lyrics>\n[00:12] 行\n</lyrics>\n\n<conversation>\n利用者: 意味は？\nアシスタント: 別れの歌です\n</conversation>\n\n続けて", prompt)
    }

    func testLongLyricsAreCut() {
        let prompt = spec.prompt(task: "x", history: [], song: nil, lyrics: String(repeating: "あ", count: 30_000))
        XCTAssertEqual(20_000, prompt.filter { $0 == "あ" }.count)
    }
}
