import Foundation

/// 中継（ai-bridge の /v1/run）へ送る頼み（Android の AiAgentSpec・Windows の Wave.Core.Ai.AiAgentSpec と同じ）。
/// システムプロンプトと道具の定義は ai/agent.json（Android・Windows と同じファイル。bundle.sh・ios-bundle.sh がアプリに入れる）。
/// 中継はアプリのことを知らないので、今の曲・歌詞・それまでのやりとりは頼みの文に入れる。
public struct AiAgentSpec: Sendable {
    public let system: String
    public let tools: [JSON]
    public let maxLyricsChars: Int

    public init(json: JSON) throws {
        guard let system = json["system"]?.str, let tools = json["tools"]?.arr else { throw URLError(.cannotParseResponse) }
        self.system = system
        self.tools = tools
        maxLyricsChars = json["max_lyrics_chars"]?.int ?? 20_000
    }

    /// /v1/run の本文。history は (role, content) で、role は "user" か "assistant"。
    public func body(task: String, history: [(String, String)], song: Song?, lyrics: String?) -> JSON {
        .object(JSONObject([("system", .string(system)), ("prompt", .string(prompt(task: task, history: history, song: song, lyrics: lyrics))),
                            ("tools", .array(tools))]))
    }

    public func prompt(task: String, history: [(String, String)], song: Song?, lyrics: String?) -> String {
        var out = ""
        if let song {
            out += "<now_playing>\n題名: \(song.title)\nアーティスト: \(song.artistsText)\nアルバム: \(song.album?.title ?? "")\nvideo_id: \(song.videoId)\n</now_playing>\n\n"
        }
        if let lyrics, !lyrics.isEmpty { out += "<lyrics>\n\(String(lyrics.prefix(maxLyricsChars)))\n</lyrics>\n\n" }
        if !history.isEmpty {
            out += "<conversation>\n"
            for (role, content) in history { out += (role == "user" ? "利用者: " : "アシスタント: ") + content + "\n" }
            out += "</conversation>\n\n"
        }
        return out + task
    }
}
