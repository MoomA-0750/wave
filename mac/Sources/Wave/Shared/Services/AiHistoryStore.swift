import Foundation
import Observation
import WaveCore

/// 保存した会話の1つ分の発言（Android の AiSavedMessage と同じ）。kind は user・assistant・action・songs・failure。
/// videoId は assistant だけ: 時刻付きの歌詞を添えて聞いたときの曲（答えの中の時刻は、この曲が鳴っているときだけ押せる）。
struct AiSavedMessage: Codable {
    var kind: String
    var text: String = ""
    var videoId: String?
    var songs: [SongRecord]?
}

/// 保存した会話。題名は最初の自分の発言。時刻は Unix ミリ秒。
struct AiConversation: Codable, Identifiable, AiHistoryEntry {
    var id: String
    var title: String
    var createdAtMs: Int64
    var updatedAtMs: Int64
    var messages: [AiSavedMessage]
}

private struct AiHistoryFile: Codable {
    var version: Int
    var conversations: [AiConversation]
}

/// Claude との会話の履歴のファイル（データの置き場の ai_history.json。Android・Windows と同じ）。
/// 端末の中だけに置き、同期しない。発言と曲の情報だけで、ログインの情報は入れない。
@MainActor @Observable
final class AiHistoryStore {
    /// 新しく話した順。
    private(set) var conversations: [AiConversation] = []
    @ObservationIgnored private let url = AppPaths.file("ai_history.json")

    init() {
        if let data = try? Data(contentsOf: url), let file = try? JSONDecoder().decode(AiHistoryFile.self, from: data), file.version == AiHistory.version {
            conversations = Array(file.conversations.sorted { $0.updatedAtMs > $1.updatedAtMs }.prefix(AiHistory.max))
        }
    }

    func save(_ conversation: AiConversation) { write(AiHistory.upsert(conversations, conversation)) }

    func remove(_ id: String) { write(conversations.filter { $0.id != id }) }

    /// ログアウトのとき: 全部消す。
    func clear() { write([]) }

    private func write(_ list: [AiConversation]) {
        conversations = list
        if list.isEmpty {
            try? FileManager.default.removeItem(at: url)
            return
        }
        guard let data = try? JSONEncoder().encode(AiHistoryFile(version: AiHistory.version, conversations: list)) else { return }
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            AppPaths.log("ai history save failed type=\(type(of: error))")
        }
    }
}
