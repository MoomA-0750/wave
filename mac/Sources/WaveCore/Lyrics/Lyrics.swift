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

    /// 行ごとに時刻がある（再生位置に合わせて表示する）。
    public struct Synced: Hashable, Sendable {
        public var lines: [LyricLine]
        public var source: String?
        public init(lines: [LyricLine], source: String?) {
            self.lines = lines
            self.source = source
        }
    }

    /// 時刻の無い本文。
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
        case .synced(let s): return s.source
        case .plain(let p): return p.source
        }
    }
}

/// LRC 形式（[mm:ss.xx]本文）の読み取り。
public enum Lrc {
    /// 時刻の付いた行だけを時刻の順に返す。[offset:+500] は全体を 500ms 早める。
    public static func parse(_ text: String) -> [LyricLine] {
        let timePattern = #/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/#
        let offsetPattern = #/^\[offset:\s*([+-]?\d+)\s*\]$/#.ignoresCase()
        var offset: Int64 = 0
        var lines: [LyricLine] = []
        // "\r\n" を1文字として扱わないよう、Character ではなく文字列で区切る
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if let offsetMatch = line.wholeMatch(of: offsetPattern) {
                offset = Int64(offsetMatch.output.1) ?? 0
                continue
            }
            var times: [Int64] = []
            var rest = Substring(line)
            while let match = rest.prefixMatch(of: timePattern) {
                times.append(millisOf(minutes: match.output.1, seconds: match.output.2, fraction: match.output.3))
                rest = rest[match.range.upperBound...]
            }
            let body = rest.trimmingCharacters(in: .whitespacesAndNewlines)
            for t in times { lines.append(LyricLine(startMs: max(0, t - offset), text: body)) }
        }
        // 時刻の順（同じ時刻は元の順のまま）
        return lines.enumerated().sorted { a, b in
            a.element.startMs != b.element.startMs ? a.element.startMs < b.element.startMs : a.offset < b.offset
        }.map(\.element)
    }

    private static func millisOf(minutes: Substring, seconds: Substring, fraction: Substring?) -> Int64 {
        let fraction = fraction.map(String.init) ?? ""
        // 小数部は桁数で意味が変わる（.9 = 900ms、.90 = 900ms、.905 = 905ms）
        let fractionMs = fraction.isEmpty ? 0 : Int64(fraction.padding(toLength: 3, withPad: "0", startingAt: 0)) ?? 0
        return (Int64(minutes) ?? 0) * 60_000 + (Int64(seconds) ?? 0) * 1_000 + fractionMs
    }
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
            let mid = (low + high) >> 1
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
