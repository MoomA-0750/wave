import Foundation

/// 歌詞の1行。startMs は曲の頭からの時刻。
public struct LyricLine: Hashable, Sendable {
    public var startMs: Int64
    public var text: String
    public init(startMs: Int64, text: String) {
        self.startMs = startMs
        self.text = text
    }
}

/// 曲の歌詞。source は出典の表示（「LRCLIB」「Source: Musixmatch」など）。
public enum SongLyrics: Hashable, Sendable {
    /// 行ごとに時刻がある（再生位置に合わせて表示する）。
    case synced(Synced)
    /// 時刻の無い本文。
    case plain(Plain)

    public struct Synced: Hashable, Sendable {
        public var lines: [LyricLine]
        public var source: String?
        public init(lines: [LyricLine], source: String?) {
            self.lines = lines
            self.source = source
        }
    }

    public struct Plain: Hashable, Sendable {
        public var text: String
        public var source: String?
        public init(text: String, source: String?) {
            self.text = text
            self.source = source
        }
    }

    public var source: String? {
        switch self {
        case .synced(let s): s.source
        case .plain(let p): p.source
        }
    }
}

/// LRC 形式（[mm:ss.xx]本文）の読み取り。
public enum Lrc {
    private static let timePattern = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]"#)
    private static let offsetPattern = try! NSRegularExpression(pattern: #"^\[offset:\s*([+-]?\d+)\s*]$"#, options: [.caseInsensitive])

    /// 時刻の付いた行だけを時刻の順に返す。[offset:+500] は全体を 500ms 早める。
    public static func parse(_ text: String) -> [LyricLine] {
        var offset: Int64 = 0
        var lines: [LyricLine] = []
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let ns = line as NSString
            if let m = offsetPattern.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) {
                offset = parseLong(ns.substring(with: m.range(at: 1))) ?? 0
                continue
            }
            var times: [Int64] = []
            var rest = line
            while true {
                let restNs = rest as NSString
                guard let match = timePattern.firstMatch(in: rest, range: NSRange(location: 0, length: restNs.length)),
                    match.range.location == 0
                else { break }
                times.append(millisOf(match, in: restNs))
                rest = restNs.substring(from: match.range.length)
            }
            let body = rest.trimmingCharacters(in: .whitespacesAndNewlines)
            for t in times { lines.append(LyricLine(startMs: max(0, t - offset), text: body)) }
        }
        // 同じ時刻の行は元の順のまま（安定な並べ替え）
        return lines.enumerated().sorted { a, b in
            a.element.startMs != b.element.startMs ? a.element.startMs < b.element.startMs : a.offset < b.offset
        }.map(\.element)
    }

    private static func millisOf(_ match: NSTextCheckingResult, in text: NSString) -> Int64 {
        let fractionRange = match.range(at: 3)
        let fraction = fractionRange.location == NSNotFound ? "" : text.substring(with: fractionRange)
        // 小数部は桁数で意味が変わる（.9 = 900ms、.90 = 900ms、.905 = 905ms）
        let fractionMs = fraction.isEmpty ? 0 : (parseLong(fraction.padding(toLength: 3, withPad: "0", startingAt: 0)) ?? 0)
        let minutes = parseLong(text.substring(with: match.range(at: 1))) ?? 0
        let seconds = parseLong(text.substring(with: match.range(at: 2))) ?? 0
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }

    private static func parseLong(_ s: String) -> Int64? { Int64(s) }
}

/// 再生位置と歌詞の行の対応。
public enum LyricsTiming {
    /// 行の開始より少し早く切り替える（読み始めが歌に遅れないように）。
    public static let leadMs: Int64 = 150

    /// positionMs で歌っている行の位置。最初の行より前なら -1。
    public static func currentIndex(_ lines: [LyricLine], positionMs: Int64) -> Int {
        let at = positionMs + leadMs
        var low = 0
        var high = lines.count - 1
        var found = -1
        while low <= high {
            let mid = Int(UInt(bitPattern: low + high) >> 1)
            if lines[mid].startMs <= at {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
