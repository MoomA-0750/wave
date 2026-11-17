import Foundation

/// LRCLIB（lrclib.net）から時刻付きの歌詞を探す。送るのは曲名・アーティスト名・アルバム名・長さだけ。
/// session には YouTube の cookie を持たないものを渡す。
public final class LrclibClient: @unchecked Sendable {
    public enum Result: Hashable, Sendable {
        case found([LyricLine])
        case notFound
        case failed
    }

    struct Track: Decodable, Equatable {
        var trackName: String?
        var artistName: String?
        var albumName: String?
        var duration: Double?
        var instrumental: Bool
        var syncedLyrics: String?

        init(trackName: String? = nil, artistName: String? = nil, albumName: String? = nil, duration: Double? = nil, instrumental: Bool = false, syncedLyrics: String? = nil) {
            self.trackName = trackName
            self.artistName = artistName
            self.albumName = albumName
            self.duration = duration
            self.instrumental = instrumental
            self.syncedLyrics = syncedLyrics
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            trackName = try c.decodeIfPresent(String.self, forKey: .trackName)
            artistName = try c.decodeIfPresent(String.self, forKey: .artistName)
            albumName = try c.decodeIfPresent(String.self, forKey: .albumName)
            duration = try c.decodeIfPresent(Double.self, forKey: .duration)
            instrumental = try c.decodeIfPresent(Bool.self, forKey: .instrumental) ?? false
            syncedLyrics = try c.decodeIfPresent(String.self, forKey: .syncedLyrics)
        }

        enum CodingKeys: String, CodingKey {
            case trackName, artistName, albumName, duration, instrumental, syncedLyrics
        }
    }

    /// 曲の、もう1つの言語（画面が日本語なら英語、それ以外なら日本語）での曲名とアーティスト名。
    /// YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」のように、言語で表記の違う曲を一致させるために使う。
    public struct OtherNames: Hashable, Sendable {
        public let title: String
        public let artist: String

        public init(title: String, artist: String) {
            self.title = title
            self.artist = artist
        }
    }

    struct StatusError: Error {
        let status: Int
    }

    private static let durationTolerances: [Double] = [2.0, 5.0]

    private let session: URLSession
    private let userAgent: String
    private let baseUrl: String

    public init(session: URLSession, userAgent: String, baseUrl: String = "https://lrclib.net") {
        self.session = session
        self.userAgent = userAgent
        self.baseUrl = baseUrl
    }

    /// 検索の結果に時刻付きの候補があるのに1つも一致しなければ、otherNames で曲名・アーティスト名の別名を取り、足してもう一度選ぶ。
    public func synced(
        title: String, artist: String, album: String?, durationSeconds: Int?,
        otherNames: @escaping @Sendable () async -> OtherNames? = { nil }
    ) async throws -> Result {
        do {
            if let exact = try await exact(title: title, artist: artist, album: album, durationSeconds: durationSeconds) { return .found(exact) }
            let titles = LrclibClient.searchTitles(title)
            var seen: [Track] = []
            for t in titles {
                let tracks = try await search(searchTitle: t, artist: artist)
                if let found = LrclibClient.pick(tracks, title: title, artist: artist, durationSeconds: durationSeconds).flatMap(LrclibClient.linesOf) {
                    return .found(found)
                }
                seen += tracks
            }
            // アーティスト名を付けて探すと、LRCLIB で別の表記（ローマ字など）で登録された曲は返らない。曲名だけでも探して候補に足す
            for t in titles { seen += try await search(searchTitle: t, artist: nil) }
            let picked = await LrclibClient.pickWithOtherNames(seen, title: title, artist: artist, durationSeconds: durationSeconds, otherNames: otherNames)
            if let found = picked.flatMap(LrclibClient.linesOf) { return .found(found) }
            return .notFound
        } catch {
            try rethrowIfCancelled(error)
            return .failed
        }
    }

    private func exact(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> [LyricLine]? {
        var query = "track_name=\(InnerTubeClient.escape(title))&artist_name=\(InnerTubeClient.escape(artist))"
        if let album { query += "&album_name=\(InnerTubeClient.escape(album))" }
        if let durationSeconds { query += "&duration=\(durationSeconds)" }
        let (data, status) = try await send("\(baseUrl)/api/get?\(query)")
        if status == 404 { return nil }
        if status != 200 { throw StatusError(status: status) }
        let track = try JSONDecoder().decode(Track.self, from: data)
        return LrclibClient.linesOf(track)
    }

    private func search(searchTitle: String, artist: String?) async throws -> [Track] {
        var url = "\(baseUrl)/api/search?track_name=\(InnerTubeClient.escape(searchTitle))"
        if let artist { url += "&artist_name=\(InnerTubeClient.escape(artist))" }
        let (data, status) = try await send(url)
        if status != 200 { throw StatusError(status: status) }
        return try JSONDecoder().decode([Track]?.self, from: data) ?? []
    }

    private func send(_ url: String) async throws -> (Data, Int) {
        guard let target = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: target)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private static func linesOf(_ track: Track) -> [LyricLine]? {
        guard !track.instrumental, let synced = track.syncedLyrics, !synced.isEmpty else { return nil }
        let lines = Lrc.parse(synced)
        return lines.contains { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ? lines : nil
    }

    /// 検索の結果から、曲名とアーティストが一致する時刻付きのうち長さの近いものを選ぶ。長さが分からなければ最初の一致。
    /// 一致するものが無ければ選ばない（長さだけが近い別の曲を選ばないため）。
    /// 曲名が一致しなくても、アーティストが一致して長さが `LrclibMatch.sameLengthWithoutTitle` 秒以内なら残す。
    static func pick(_ tracks: [Track], title: String, artist: String, durationSeconds: Int?) -> Track? {
        pickAny(tracks, titles: [title], artists: [artist], durationSeconds: durationSeconds)
    }

    /// 元の名前で選び、時刻付きの候補があるのに1つも一致しなければ、別名（otherNames）を足してもう一度選ぶ。
    /// 一致したとき・時刻付きの候補が無いときは otherNames を呼ばない（別名を取りに行かない）。
    static func pickWithOtherNames(
        _ tracks: [Track], title: String, artist: String, durationSeconds: Int?, otherNames: () async -> OtherNames?
    ) async -> Track? {
        if let track = pick(tracks, title: title, artist: artist, durationSeconds: durationSeconds) { return track }
        guard tracks.contains(where: isSynced), let other = await otherNames() else { return nil }
        return pickAny(tracks, titles: [title, other.title], artists: [artist, other.artist], durationSeconds: durationSeconds)
    }

    private static func isSynced(_ track: Track) -> Bool {
        !track.instrumental && !(track.syncedLyrics ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// pick と同じだが、曲名・アーティストはそれぞれ titles・artists のどれかに一致すれば良い（元の名前と別名）。
    private static func pickAny(_ tracks: [Track], titles: [String], artists: [String], durationSeconds: Int?) -> Track? {
        let wantedTitles = titles.flatMap(LrclibMatch.titleKeys)
        let wantedArtists = artists.flatMap(LrclibMatch.artistKeys)
        let matching = tracks.filter { track in
            guard isSynced(track) else { return false }
            guard LrclibMatch.overlap(wantedArtists, LrclibMatch.artistKeys(track.artistName ?? "")) else { return false }
            if LrclibMatch.overlap(wantedTitles, LrclibMatch.titleKeys(track.trackName ?? "")) { return true }
            guard let durationSeconds, let d = track.duration else { return false }
            return abs(d - Double(durationSeconds)) <= LrclibMatch.sameLengthWithoutTitle
        }
        guard let durationSeconds else { return matching.first }
        let wanted = Double(durationSeconds)
        for tolerance in durationTolerances {
            // 差の小さい順（同じ差なら先に出てきたもの）
            let hit = matching.enumerated()
                .filter { if let d = $0.element.duration { return abs(d - wanted) <= tolerance } else { return false } }
                .min { a, b in
                    let da = abs(a.element.duration! - wanted)
                    let db = abs(b.element.duration! - wanted)
                    return da != db ? da < db : a.offset < b.offset
                }
            if let hit { return hit.element }
        }
        return nil
    }

    /// 検索に使う曲名。「(From "THE FIRST TAKE")」「[Remastered]」「 - Orion」のような添え書きを外したものも試す。
    static func searchTitles(_ title: String) -> [String] {
        let brackets = #/\s*[(\[（【][^)\]）】]*[)\]）】]/#
        let withoutBrackets = title.replacing(brackets, with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        let beforeDash: String
        if let dash = withoutBrackets.range(of: " - ", options: .literal) {
            beforeDash = String(withoutBrackets[..<dash.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            beforeDash = withoutBrackets
        }
        var result: [String] = []
        for s in [title, withoutBrackets, beforeDash] where !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !result.contains(s) {
            result.append(s)
        }
        return result
    }
}

/// LRCLIB の検索の結果が今の曲と同じかの照らし合わせ（Android・Windows・Linux の版と同じ規則）。
/// 長さだけで選ぶと、長さの近い別の曲の歌詞を選んでしまうため。
enum LrclibMatch {
    /// 曲名が一致しなくても、アーティストが一致して長さがこの秒数以内なら同じ曲とみなす（日本語の曲名がローマ字・英語で登録されていることがあるため）。
    static let sameLengthWithoutTitle = 2.0

    /// 比べる形。NFKC にして小文字にし、文字（L）と数字（N）だけを残す。
    static func key(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.precomposedStringWithCompatibilityMapping.lowercased().unicodeScalars {
            switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber, .letterNumber, .otherNumber:
                out.append(scalar)
            default:
                break
            }
        }
        return String(out)
    }

    /// 曲名を比べる形。そのまま、括弧の中の補足を除いた形、「 - 」の後ろを除いた形、「feat.」の後ろを除いた形。
    static func titleKeys(_ title: String) -> [String] {
        let full = title.precomposedStringWithCompatibilityMapping
        let withoutBrackets = full.replacing(#/\s*[(\[（【][^)\]）】]*[)\]）】]/#, with: "")
        let beforeDash = before(withoutBrackets, #/\s[-–—]\s/#)
        let beforeFeat = before(beforeDash, #/(?i)\s(?:feat\.?|ft\.|featuring)\s/#)
        return distinctKeys([full, withoutBrackets, beforeDash, beforeFeat])
    }

    /// アーティストを比べる形。全体と、「,」「&」「feat.」「×」などで分けたそれぞれの名前。
    static func artistKeys(_ artist: String) -> [String] {
        let full = artist.precomposedStringWithCompatibilityMapping
        let separators = #/(?i)\s*[,&、\/;×]\s*|\s+(?:x|feat\.?|ft\.|featuring|vs\.?)\s+/#
        return distinctKeys([full] + full.split(separator: separators).map(String.init))
    }

    /// どれかの形が等しいか、片方がもう片方を含めば一致。
    static func overlap(_ a: [String], _ b: [String]) -> Bool {
        a.contains { x in b.contains { y in x.contains(y) || y.contains(x) } }
    }

    private static func before(_ text: String, _ separator: some RegexComponent) -> String {
        guard let range = text.firstRange(of: separator) else { return text }
        return String(text[..<range.lowerBound])
    }

    private static func distinctKeys(_ texts: [String]) -> [String] {
        var out: [String] = []
        for key in texts.map(key) where !key.isEmpty && !out.contains(key) { out.append(key) }
        return out
    }
}
