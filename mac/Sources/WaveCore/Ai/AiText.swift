import Foundation

/// Claude に送る歌詞と、Claude の答えの中の時刻（Android の core.ai.AiText・Windows の Wave.Core.Ai.AiText と同じ）。
/// 時刻付きの歌詞は行ごとに「[01:23] 本文」の形で送り、Claude には歌詞を引くときに同じ形で時刻を添えてもらう
/// （ai/agent.json の指示）。答えの中の [mm:ss] は、押すとその位置へ飛べるようにする。
public enum AiText {
    /// 中継へ送る歌詞。時刻付きなら行ごとに [mm:ss] 本文（空の行は飛ばす）、時刻が無ければ本文のまま。
    public static func lyrics(_ lyrics: SongLyrics) -> String {
        switch lyrics {
        case .synced(let s):
            return s.lines
                .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                .map { "[\(stamp($0.startMs))] \($0.text.trimmingCharacters(in: .whitespacesAndNewlines))" }
                .joined(separator: "\n")
        case .plain(let p):
            return p.text
        }
    }

    /// 曲の頭からの時刻を mm:ss にする（秒より下は切り捨て。1時間を超えても分で数える）。
    public static func stamp(_ positionMs: Int64) -> String {
        let seconds = max(0, positionMs / 1000)
        return String(format: "%02lld:%02lld", seconds / 60, seconds % 60)
    }

    /// 答えの中の時刻 [mm:ss] の1つ。range は括弧を含む位置、label は括弧の中身。
    public struct Timestamp: Equatable, Sendable {
        public var range: Range<String.Index>
        public var positionMs: Int64
        public var label: String
    }

    private static let pattern = try! NSRegularExpression(pattern: #"\[(\d{1,3}):([0-5]\d)\]"#)

    /// 答えの中の時刻を、前から順に返す。
    public static func timestamps(_ text: String) -> [Timestamp] {
        pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { m in
            guard let whole = Range(m.range, in: text), let mr = Range(m.range(at: 1), in: text), let sr = Range(m.range(at: 2), in: text),
                  let minutes = Int64(text[mr]), let seconds = Int64(text[sr]) else { return nil }
            return Timestamp(range: whole, positionMs: (minutes * 60 + seconds) * 1000, label: "\(text[mr]):\(text[sr])")
        }
    }
}

/// 履歴に残す会話の、並べ替えに使うところ。
public protocol AiHistoryEntry {
    var id: String { get }
    var updatedAtMs: Int64 { get }
}

/// Claude との会話の履歴の決まり（端末の中だけに置き、同期しない。中継は会話を持たない）。新しく話した順に、最大 max 件。
/// Android の core.ai.AiHistory・Windows の Wave.Core.Ai.AiHistory と同じ。
public enum AiHistory {
    public static let version = 1
    public static let max = 50
    public static let titleMax = 80

    /// 題名（最初の自分の発言を1行にして縮める）。自分の発言が無ければ空。
    public static func title(firstUserText: String?) -> String {
        guard let text = firstUserText else { return "" }
        let line = text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return line.count > titleMax ? String(line.prefix(titleMax - 1)) + "…" : line
    }

    /// 会話を入れる（同じ id があれば置き換える）。新しく話した順に並べ、max 件を超えた古いものは落とす。
    public static func upsert<T: AiHistoryEntry>(_ list: [T], _ entry: T) -> [T] {
        Array((list.filter { $0.id != entry.id } + [entry]).sorted { $0.updatedAtMs > $1.updatedAtMs }.prefix(max))
    }
}
