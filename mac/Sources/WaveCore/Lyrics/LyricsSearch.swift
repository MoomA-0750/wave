import Foundation

/// 歌詞の中で、打った言葉が見つかった行。startMs は時刻付きの歌詞のときだけ。exact は言葉がそのまま入っていたか（false は大体合っている）。
public struct LyricsMatch: Hashable, Sendable {
    public var line: String
    public var startMs: Int64?
    public var exact: Bool
    public init(line: String, startMs: Int64?, exact: Bool) {
        self.line = line
        self.startMs = startMs
        self.exact = exact
    }
}

/// 歌詞から探した結果の1曲。match が nil なら、歌詞が取れなかったか、言葉が見つからなかった（検索の順で後ろに並べる）。
public struct LyricsHit: Hashable, Sendable, Identifiable {
    public var song: Song
    public var match: LyricsMatch?
    public init(song: Song, match: LyricsMatch?) {
        self.song = song
        self.match = match
    }

    public var id: String { song.videoId }
}

/// 歌詞の一節から曲を探す（Android 版の LyricsSearch と同じ。決定 2026-10-14）。外部の歌詞検索サービスは使わず、
/// YouTube Music の絞り込まない検索（歌詞の一節でも曲が上に来る）の上位の曲の歌詞を取り、打った言葉を含む曲を前に出す。
/// 歌詞は再生画面と同じ LyricsService（LRCLIB の時刻付き、無ければ YouTube Music）から取る。throw するのは取り消しだけ。
public final class LyricsSearch: Sendable {
    /// 歌詞を確かめる曲の数。
    public static let candidateCount = 6

    private let candidates: @Sendable (String) async throws -> ApiResult<[Song]>
    private let lyrics: @Sendable (Song) async throws -> LyricsService.Result

    public init(candidates: @escaping @Sendable (String) async throws -> ApiResult<[Song]>,
                lyrics: @escaping @Sendable (Song) async throws -> LyricsService.Result)
    {
        self.candidates = candidates
        self.lyrics = lyrics
    }

    public convenience init(music: MusicRepository, lyrics: LyricsService) {
        self.init(candidates: { try await music.searchTopSongs($0) }, lyrics: { try await lyrics.lyrics(song: $0) })
    }

    public func search(_ query: String) async throws -> ApiResult<[LyricsHit]> {
        if LyricsMatcher.normalize(query).isEmpty { return .ok([]) }
        let found = try await candidates(query)
        guard found.isOk, let songs = found.value else { return .failed(found.failure ?? .parse, status: found.status) }
        let picked = Array(LyricsSearch.distinctSongs(songs).prefix(LyricsSearch.candidateCount))
        let lyrics = self.lyrics
        // 曲ごとの歌詞を同時に取り、検索の順に戻す
        let matches = try await withThrowingTaskGroup(of: (Int, LyricsMatch?).self, returning: [LyricsMatch?].self) { group in
            for (i, song) in picked.enumerated() {
                group.addTask {
                    guard case .found(let text) = try await lyrics(song) else { return (i, nil) }
                    return (i, LyricsMatcher.find(text, query))
                }
            }
            var result = [LyricsMatch?](repeating: nil, count: picked.count)
            for try await (i, match) in group { result[i] = match }
            return result
        }
        return .ok(LyricsSearch.rank(zip(picked, matches).map { LyricsHit(song: $0, match: $1) }))
    }

    /// そのまま入っていた曲、大体合っている曲、見つからなかった曲、見つからなかった投稿者の動画（歌ってみた・歌詞動画など）の順。
    /// 同じ組の中は検索の順のまま。
    static func rank(_ hits: [LyricsHit]) -> [LyricsHit] {
        func group(_ hit: LyricsHit) -> Int {
            if hit.match?.exact == true { return 0 }
            if hit.match != nil { return 1 }
            return hit.song.kind != Song.kindUserVideo ? 2 : 3
        }
        return hits.enumerated().sorted { a, b in
            let (ga, gb) = (group(a.element), group(b.element))
            return ga != gb ? ga < gb : a.offset < b.offset
        }.map(\.element)
    }

    /// 同じ曲の音源と MV が両方出たら、先に出た位置に音源を残す（曲として流すため）。ライブは除く。
    /// 同じ曲かは、曲名と最初のアーティストを正規化して比べる。
    static func distinctSongs(_ songs: [Song]) -> [Song] {
        var result: [Song] = []
        var index: [String: Int] = [:]
        for song in songs {
            if song.isLive || result.contains(where: { $0.videoId == song.videoId }) { continue }
            let key = LyricsMatcher.normalize(song.title) + "\u{0}" + LyricsMatcher.normalize(song.artists.first?.name ?? "")
            if let at = index[key] {
                if song.kind == Song.kindSong && result[at].kind != Song.kindSong { result[at] = song }
            } else {
                index[key] = result.count
                result.append(song)
            }
        }
        return result
    }
}

/// 歌詞の行と打った言葉の照らし合わせ（Android 版の LyricsMatcher と同じ規則）。画面にも通信にも依らない。
public enum LyricsMatcher {
    /// 大体合っているとみなす、言葉の2文字の組が行に入っている割合。
    public static let approximateRatio = 0.7

    /// 文字（Lu・Ll・Lt・Lm・Lo）と10進の数字（Nd）。Kotlin の Char.isLetterOrDigit と同じ分類。
    private static let letterOrDigit: Set<Unicode.GeneralCategory> = [
        .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber,
    ]

    /// 比べやすい形にする。小文字にし、全角の英数字（U+FF01–FF5E）を半角に、カタカナ（U+30A1–30F6）をひらがなにし、文字と数字以外（空白・句読点・記号）を除く。
    /// 「i'm」と「im」、「夜に駆ける」と「夜にかける」の打ち方の違いを吸収する（漢字とかなの違いは吸収しない）。Unicode scalar ごとに見る。
    public static func normalize(_ text: String) -> String {
        String(String.UnicodeScalarView(normalizedScalars(text)))
    }

    private static func normalizedScalars(_ text: String) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []
        out.reserveCapacity(text.unicodeScalars.count)
        for raw in text.unicodeScalars {
            var c = raw
            if (0xFF01...0xFF5E).contains(c.value) { c = Unicode.Scalar(c.value - 0xFEE0)! }
            if (0x30A1...0x30F6).contains(c.value) { c = Unicode.Scalar(c.value - 0x60)! }
            guard letterOrDigit.contains(c.properties.generalCategory) else { continue }
            // 小文字は1文字に収まるときだけ（Kotlin の lowercaseChar と同じく、文字数を変えない）
            let lower = c.properties.lowercaseMapping.unicodeScalars
            out.append(lower.count == 1 ? lower.first! : c)
        }
        return out
    }

    /// 打った言葉を含む最初の行。行をまたいだ言葉も、その行と次の行をつないで探す。無ければ大体合っている行（一番近いもの）。
    public static func find(_ lyrics: SongLyrics, _ query: String) -> LyricsMatch? {
        let all: [(String, Int64?)]
        switch lyrics {
        case .synced(let s): all = s.lines.map { ($0.text, $0.startMs) }
        case .plain(let p): all = p.text.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline).map { (String($0), nil) }
        }
        let lines = all.filter { !$0.0.allSatisfy(\.isWhitespace) }
        let q = normalizedScalars(query)
        if q.isEmpty || lines.isEmpty { return nil }
        let normalized = lines.map { normalizedScalars($0.0) }
        let spans = normalized.indices.map { i in normalized[i] + (i + 1 < normalized.count ? normalized[i + 1] : []) }
        func match(_ i: Int, exact: Bool) -> LyricsMatch {
            LyricsMatch(line: lines[i].0.trimmingCharacters(in: .whitespacesAndNewlines), startMs: lines[i].1, exact: exact)
        }
        // 1行に収まる一致を先に探す（つないだ方だけで探すと、次の行だけに入っている言葉を前の行の一致にしてしまう）
        if let i = normalized.firstIndex(where: { contains($0, q) }) ?? spans.firstIndex(where: { contains($0, q) }) {
            return match(i, exact: true)
        }
        if q.count < 4 { return nil }
        let wanted = bigrams(q)
        var best = -1
        var bestRatio = 0.0
        for (i, span) in spans.enumerated() {
            let have = bigrams(span)
            let ratio = Double(wanted.filter { have.contains($0) }.count) / Double(wanted.count)
            if ratio > bestRatio {
                best = i
                bestRatio = ratio
            }
        }
        return best >= 0 && bestRatio >= approximateRatio ? match(best, exact: false) : nil
    }

    /// 並びの中に needle がそのまま入っているか（文字の合成を考えずに、scalar を1つずつ比べる）。
    private static func contains(_ haystack: [Unicode.Scalar], _ needle: [Unicode.Scalar]) -> Bool {
        if needle.isEmpty { return true }
        if needle.count > haystack.count { return false }
        for start in 0...(haystack.count - needle.count) where haystack[start] == needle[0] {
            if haystack[start..<(start + needle.count)].elementsEqual(needle) { return true }
        }
        return false
    }

    /// 隣り合う2文字の組（Kotlin の windowed(2).toSet() と同じ）。
    private static func bigrams(_ text: [Unicode.Scalar]) -> Set<UInt64> {
        guard text.count >= 2 else { return [] }
        return Set((0..<(text.count - 1)).map { UInt64(text[$0].value) << 32 | UInt64(text[$0 + 1].value) })
    }
}
