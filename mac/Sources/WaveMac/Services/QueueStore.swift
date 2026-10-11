import Foundation
import WaveCore

/// 保存したキューの1項目。
struct SavedQueueItem: Codable {
    var song: SongRecord
    var itemId: String
    var origin: QueueOrigin
}

/// 次の起動に持ち越すキュー（Android の SavedQueue と同じ考え）。obtainedAtEpochMs は保存したときのログインの取得時刻で、
/// 今のログインと違えば戻さない（別のアカウントのキューを出さない）。
struct SavedQueue: Codable {
    static let currentVersion = 1
    var version: Int
    var obtainedAtEpochMs: Int64
    var items: [SavedQueueItem]
    var currentItemId: String
    var positionMs: Int64
    var repeatMode: Int
    var sourceId: String?
    var radioSeed: String?
    var continuation: String?
    var radioPlaylistId: String?
    /// シャッフル中なら、切ったときに戻す並び（項目の ID → 位置）。
    var shuffleOrder: [String: Int]?
}

/// キューの保存ファイル。データの置き場に置き、暗号化はしない（曲の情報だけで、ログイン情報は入れない。Android と同じ）。
/// 書き込みは一時ファイルに書いてから置き換える（途中で落ちても前の内容か新しい内容のどちらかが残る）。
final class QueueStore {
    private let url = AppPaths.file("queue.json")

    func load() -> SavedQueue? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        do {
            let saved = try JSONDecoder().decode(SavedQueue.self, from: data)
            return saved.version == SavedQueue.currentVersion && !saved.items.isEmpty ? saved : nil
        } catch {
            AppPaths.log("queue load failed type=\(type(of: error))")
            return nil
        }
    }

    func save(_ queue: SavedQueue) {
        guard let data = try? JSONEncoder().encode(queue) else { return }
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            AppPaths.log("queue save failed type=\(type(of: error))")
        }
    }

    func delete() {
        try? FileManager.default.removeItem(at: url)
    }
}
