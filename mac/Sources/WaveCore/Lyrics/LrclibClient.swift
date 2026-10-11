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

    public func synced(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> Result {
        do {
            if let exact = try await exact(title: title, artist: artist, album: album, durationSeconds: durationSeconds) { return .found(exact) }
            for t in LrclibClient.searchTitles(title) {
                if let found = try await search(title: t, artist: artist, durationSeconds: durationSeconds) { return .found(found) }
            }
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

    private func search(title: String, artist: String, durationSeconds: Int?) async throws -> [LyricLine]? {
        let (data, status) = try await send("\(baseUrl)/api/search?track_name=\(InnerTubeClient.escape(title))&artist_name=\(InnerTubeClient.escape(artist))")
        if status != 200 { throw StatusError(status: status) }
        let tracks = try JSONDecoder().decode([Track]?.self, from: data) ?? []
        return LrclibClient.pick(tracks, durationSeconds: durationSeconds).flatMap(LrclibClient.linesOf)
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

    /// 検索の結果から、時刻付きで長さの近いものを選ぶ。長さが分からなければ最初の時刻付き。
    static func pick(_ tracks: [Track], durationSeconds: Int?) -> Track? {
        let synced = tracks.filter { !$0.instrumental && !($0.syncedLyrics ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let durationSeconds else { return synced.first }
        let wanted = Double(durationSeconds)
        for tolerance in durationTolerances {
            // 差の小さい順（同じ差なら先に出てきたもの）
            let hit = synced.enumerated()
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
