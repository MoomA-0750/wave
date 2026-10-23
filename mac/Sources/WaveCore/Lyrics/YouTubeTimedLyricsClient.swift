import Foundation

/// YouTube Music の時刻付きの歌詞（Android 版の YouTubeTimedLyricsClient と同じ）。歌詞のタブ（next で見つけた MPLY…）を
/// ANDROID_MUSIC として読むと、行ごとの時刻が付いて返る（WEB_REMIX では時刻なしだけ）。ログインは要らないので、
/// session には認証情報を持たないものを渡し、匿名で送る（ADR-0003。cookie を WEB_REMIX 以外の名乗りで送らない）。
/// 送るのは歌詞のタブの ID だけ。
public final class YouTubeTimedLyricsClient: @unchecked Sendable {
    /// 6.x では時刻付きの形にならない（2026-10 に確かめた）。7.27.52・8.40.54 は返る。
    public static let clientVersion = "7.27.52"
    public static let userAgent = "com.google.android.apps.youtube.music/\(clientVersion) (Linux; U; Android 11) gzip"

    public enum Result: Hashable, Sendable {
        case found(SongLyrics.Synced)
        /// 時刻付きが無い（MV など時刻の無い歌詞・歌詞の無い曲）。
        case notFound
        /// 通信・応答の失敗。
        case failed
    }

    private let session: URLSession
    private let hl: String
    private let gl: String

    public init(session: URLSession, hl: String, gl: String) {
        self.session = session
        self.hl = hl
        self.gl = gl
    }

    /// throw するのは取り消しだけ。
    public func timed(browseId: String) async throws -> Result {
        let body: JSON = .obj([
            "context": .obj(["client": .obj(["clientName": "ANDROID_MUSIC", "clientVersion": .string(YouTubeTimedLyricsClient.clientVersion),
                                              "hl": .string(hl), "gl": .string(gl)])]),
            "browseId": .string(browseId),
        ])
        var request = URLRequest(url: URL(string: "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = body.data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(YouTubeTimedLyricsClient.userAgent, forHTTPHeaderField: "User-Agent")
        // 念のため: 共有の URLSession に cookie の保存があっても送らない
        request.httpShouldHandleCookies = false
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(status), let root = try? JSON.parse(data) else { return .failed }
            return YouTubeTimedLyricsClient.parse(root).map { .found($0) } ?? .notFound
        } catch {
            try rethrowIfCancelled(error)
            return .failed
        }
    }

    /// 応答の `…timedLyricsModel.lyricsData` の行と出典。時刻（cueRange）の無い行ばかり（MV の歌詞）・行が無いなら nil。
    /// 間奏の「♪」の行は空の行にする（画面では行の区切りとして出す）。
    public static func parse(_ root: JSON) -> SongLyrics.Synced? {
        guard let data = find(root, key: "timedLyricsModel")?["lyricsData"], data.obj != nil else { return nil }
        var lines: [LyricLine] = []
        for row in data["timedLyricsData"]?.arr ?? [] {
            guard row.obj != nil, let start = row["cueRange"]?["startTimeMilliseconds"]?.str.flatMap({ Int64($0) }) else { continue }
            let text = (row["lyricLine"]?.str ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            lines.append(LyricLine(startMs: start, text: text == "♪" ? "" : text))
        }
        if lines.isEmpty { return nil }
        // 時刻の順に（同じ時刻は元の順のまま）
        let sorted = lines.enumerated().sorted { ($0.element.startMs, $0.offset) < ($1.element.startMs, $1.offset) }.map(\.element)
        return SongLyrics.Synced(lines: sorted, source: data["sourceMessage"]?.str)
    }

    /// key の値が object のものを、深さ優先で最初に見つかったものを返す。
    private static func find(_ json: JSON, key: String) -> JSON? {
        switch json {
        case .object(let o):
            if let hit = o[key], hit.obj != nil { return hit }
            for (_, value) in o.pairs {
                if let found = find(value, key: key) { return found }
            }
            return nil
        case .array(let a):
            for item in a {
                if let found = find(item, key: key) { return found }
            }
            return nil
        default:
            return nil
        }
    }
}
