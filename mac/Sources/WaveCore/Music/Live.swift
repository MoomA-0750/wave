import Foundation

/// HLS のマスタープレイリストの1本（画質ごとの媒体プレイリスト）。
public struct HlsVariant: Hashable, Sendable {
    public var bandwidth: Int64
    public var codecs: String?
    public var url: String
    /// 映像の高さ（RESOLUTION の縦）。書いていなければ nil。
    public var height: Int?
    public init(bandwidth: Int64, codecs: String?, url: String, height: Int? = nil) {
        self.bandwidth = bandwidth
        self.codecs = codecs
        self.url = url
        self.height = height
    }
}

/// ライブ配信（YouTube の生配信）の検索と、再生に使う HLS の解決の読み取り（純粋な関数。Kotlin の LiveParsers と同じ規則）。
/// - 検索は YouTube（www.youtube.com）の「ライブ」の絞り込み。YouTube Music の検索にはライブの絞り込みが無い
/// - 再生は ANDROID クライアントの /player の hlsManifestUrl。WEB_REMIX・WEB は匿名では再生できず、IOS は HLS を返さない（2026-10 に確かめた）
public enum LiveParsers {
    /// YouTube の検索（ライブで絞り込み）の結果と続き。ライブのバッジが付いた動画だけを取る。
    public static func webSearch(_ root: JSON) -> SongPage {
        var seen = Set<String>()
        let songs = root.findAll("videoRenderer").compactMap { video -> Song? in
            guard isLiveNow(video), let videoId = video.path("videoId").str else { return nil }
            let title = video.path("title", "runs").items.map { $0.path("text").str ?? "" }.joined()
            guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            let owner = video.path("ownerText", "runs").firstItem.path("text").str
            return Song(videoId: videoId, title: title, artists: owner.map { [ArtistRef(name: $0, browseId: nil)] } ?? [], album: nil,
                        durationSeconds: nil, thumbnailUrl: video.path("thumbnail", "thumbnails").items.last.path("url").str,
                        kind: Song.kindUserVideo, isLive: true)
        }.filter { seen.insert($0.videoId).inserted }
        let continuation = root.findAll("continuationItemRenderer").lazy.compactMap { $0.path("continuationEndpoint", "continuationCommand", "token").str }.first
        return SongPage(songs: songs, continuation: continuation)
    }

    private static func isLiveNow(_ video: JSON) -> Bool {
        video.path("badges").items.contains { $0.path("metadataBadgeRenderer", "style").str == "BADGE_STYLE_TYPE_LIVE_NOW" }
            || video.path("thumbnailOverlays").items.contains { $0.path("thumbnailOverlayTimeStatusRenderer", "style").str == "LIVE" }
    }

    /// /player の応答から、再生できるライブの HLS のマスタープレイリストの URL。ライブでない・再生できないなら nil。
    public static func hlsManifestUrl(_ player: JSON) -> String? {
        guard player.path("playabilityStatus", "status").str == "OK", let url = player.path("streamingData", "hlsManifestUrl").str else { return nil }
        return isAllowedLiveManifestUrl(url) ? url : nil
    }

    /// /player の応答の URL の寿命（秒）。
    public static func expiresInSeconds(_ player: JSON) -> Int64? { player.path("streamingData", "expiresInSeconds").long }

    /// /player の応答の videoDetails がライブ配信中を示すか。
    public static func isLive(_ player: JSON) -> Bool { player.path("videoDetails", "isLive").bool == true }

    /// マスタープレイリストの画質の一覧。相対の URL はマスターの URL を基準に直す。
    public static func variants(_ master: String, masterUrl: String) -> [HlsVariant] {
        let lines = master.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }
        var variants: [HlsVariant] = []
        for (index, line) in lines.enumerated() where line.hasPrefix("#EXT-X-STREAM-INF:") {
            let attributes = attributesOf(String(line.dropFirst("#EXT-X-STREAM-INF:".count)))
            let uri = lines[(index + 1)...].first { !$0.isEmpty && !$0.hasPrefix("#") }
            if let uri, let bandwidth = attributes["BANDWIDTH"].flatMap({ Int64($0) }) {
                let height = attributes["RESOLUTION"].flatMap { $0.split(separator: "x").last.flatMap { Int($0) } }
                variants.append(HlsVariant(bandwidth: bandwidth, codecs: attributes["CODECS"], url: resolveUrl(masterUrl, uri), height: height))
            }
        }
        return variants
    }

    /// 音を聴くための1本を選ぶ。AAC-LC（mp4a.40.2）の中で最も帯域の小さいもの（映像も同じ流れに入っているので、小さい画質で通信量を抑える）。
    /// AAC-LC が無ければ、全体で最も帯域の小さいもの。
    public static func chooseAudioVariant(_ variants: [HlsVariant]) -> HlsVariant? {
        variants.filter { $0.codecs?.contains("mp4a.40.2") == true }.min { $0.bandwidth < $1.bandwidth }
            ?? variants.min { $0.bandwidth < $1.bandwidth }
    }

    /// 映像を見るときの画質の上限（高さ）。通信量と、スマホ・小窓で見るときの見え方の釣り合い。
    public static let maxVideoHeight = 720

    /// 映像を見るための1本を選ぶ。高さ maxVideoHeight（720）以下で最も帯域の大きいもの（AAC-LC を先に）。
    /// 高さが書かれていなければ音のための1本と同じ（小さい画質のまま）。Kotlin の chooseVideoVariant と同じ。
    public static func chooseVideoVariant(_ variants: [HlsVariant]) -> HlsVariant? {
        let fitting = variants.filter { ($0.height ?? .max) <= maxVideoHeight }
        return fitting.filter { $0.codecs?.contains("mp4a.40.2") == true }.max { $0.bandwidth < $1.bandwidth }
            ?? fitting.max { $0.bandwidth < $1.bandwidth }
            ?? chooseAudioVariant(variants)
    }

    private static func attributesOf(_ text: String) -> [String: String] {
        let chars = Array(text)
        var result: [String: String] = [:]
        var index = 0
        while index < chars.count {
            guard let eq = chars[index...].firstIndex(of: "=") else { break }
            let name = String(chars[index..<eq]).trimmingCharacters(in: .whitespaces)
            let end: Int
            let value: String
            if eq + 1 < chars.count && chars[eq + 1] == "\"" {
                let close = chars[(eq + 2)...].firstIndex(of: "\"") ?? chars.count
                value = String(chars[(eq + 2)..<close])
                end = close < chars.count ? (chars[close...].firstIndex(of: ",") ?? chars.count) : chars.count
            } else {
                end = chars[eq...].firstIndex(of: ",") ?? chars.count
                value = String(chars[(eq + 1)..<end])
            }
            result[name] = value
            index = end + 1
        }
        return result
    }

    private static func resolveUrl(_ base: String, _ reference: String) -> String {
        if reference.hasPrefix("https://") || reference.hasPrefix("http://") { return reference }
        guard let schemeEnd = base.range(of: "://") else { return reference }
        let afterScheme = base[schemeEnd.upperBound...]
        let origin = String(base[..<schemeEnd.upperBound]) + String(afterScheme.prefix { $0 != "/" })
        if reference.hasPrefix("/") { return origin + reference }
        let noQuery = base.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? base
        let dir = noQuery.range(of: "/", options: .backwards).map { String(noQuery[..<$0.lowerBound]) } ?? noQuery
        return dir + "/" + reference
    }
}

/// ライブの HLS のマスター・媒体プレイリストの URL として使ってよいか（https・443・manifest.googlevideo.com の /api/manifest/hls_ 配下）。
public func isAllowedLiveManifestUrl(_ url: String) -> Bool {
    guard let uri = CheckedURL(url) else { return false }
    return uri.host == "manifest.googlevideo.com" && uri.path.hasPrefix("/api/manifest/hls_")
}

/// ライブの HLS の区切り（googlevideo の /videoplayback/…）として取ってよいか。
public func isAllowedLiveSegmentUrl(_ url: String) -> Bool {
    guard let uri = CheckedURL(url) else { return false }
    return uri.host.hasSuffix(".googlevideo.com") && (uri.path == "/videoplayback" || uri.path.hasPrefix("/videoplayback/"))
}

/// 解決したライブの再生先。URL は IP に結び付いた署名を含むので、description に出さない。
public final class LiveStream: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let videoId: String
    private let url: String
    /// 呼び出し側の時計（nowMs）のミリ秒。
    public let expiresAtMs: Int64

    init(videoId: String, url: String, expiresAtMs: Int64) {
        self.videoId = videoId
        self.url = url
        self.expiresAtMs = expiresAtMs
    }

    /// 再生器にだけ渡す。ログ・例外文・画面に出さない。
    public func revealUrl() -> String { url }

    public var description: String { "LiveStream(videoId=\(videoId))" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: ["videoId": videoId], displayStyle: .class) }
}

/// ライブの解決の失敗の種類。
public enum LiveFailure: Sendable, Equatable {
    case network
    case notLive
    case noVariant
}

public struct LiveResult: Sendable {
    public let stream: LiveStream?
    public let failure: LiveFailure?
    public static func ok(_ stream: LiveStream) -> LiveResult { LiveResult(stream: stream, failure: nil) }
    public static func failed(_ failure: LiveFailure) -> LiveResult { LiveResult(stream: nil, failure: failure) }
}

/// ライブ配信の検索と HLS の解決。www.youtube.com へ送るので、認証のヘッダ・cookie を付けない
/// （ADR-0003。session は cookie を持たない InnerTubeClient.createSession() のものを渡す）。
public final class LiveClient: Sendable {
    /// YouTube の検索の「ライブ」の絞り込み。
    public static let liveFilter = "EgJAAQ%3D%3D"
    public static let webClientVersion = "2.20261001.00.00"
    public static let androidClientVersion = "20.10.38"
    public static let androidUserAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
    private static let defaultLifetimeSeconds: Int64 = 6 * 60 * 60
    private static let expirySafetyMs: Int64 = 5 * 60 * 1000
    private static let maxResponseBytes = 8 * 1024 * 1024

    private let session: URLSession
    private let hl: String
    private let gl: String
    private let nowMs: @Sendable () -> Int64

    public init(session: URLSession, hl: String, gl: String, nowMs: @escaping @Sendable () -> Int64) {
        self.session = session
        self.hl = hl
        self.gl = gl
        self.nowMs = nowMs
    }

    /// YouTube の検索をライブに絞る。continuation を渡したら続きのページ。読めなければ nil。throw するのは取り消しだけ。
    public func search(_ query: String, continuation: String? = nil) async throws -> SongPage? {
        var body = JSONObject([("context", .obj(["client": .obj(["clientName": "WEB", "clientVersion": .string(LiveClient.webClientVersion),
                                                                  "hl": .string(hl), "gl": .string(gl)])]))])
        if let continuation {
            body["continuation"] = .string(continuation)
        } else {
            body["query"] = .string(query)
            body["params"] = .string(LiveClient.liveFilter)
        }
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/search?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = JSON.object(body).data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(InnerTubeClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        guard let data = try await fetch(request), let root = try? JSON.parse(data) else { return nil }
        return LiveParsers.webSearch(root)
    }

    /// ライブの HLS（音を聴くための1本の媒体プレイリスト）を解決する。throw するのは取り消しだけ。
    /// ライブの HLS（音を聴くための1本、video なら映像を見るための1本の媒体プレイリスト）を解決する。
    public func resolve(videoId: String, video: Bool = false) async throws -> LiveResult {
        guard videoId.wholeMatch(of: #/[A-Za-z0-9_-]{11}/#) != nil else { return .failed(.notLive) }
        let requestedAt = nowMs()
        let body = JSON.obj([
            "context": .obj(["client": .obj([
                "clientName": "ANDROID", "clientVersion": .string(LiveClient.androidClientVersion), "androidSdkVersion": 30,
                "osName": "Android", "osVersion": "11", "hl": .string(hl), "gl": .string(gl),
            ])]),
            "videoId": .string(videoId),
            "contentCheckOk": true,
            "racyCheckOk": true,
        ])
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = body.data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(LiveClient.androidUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("3", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(LiveClient.androidClientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        guard let data = try await fetch(request) else { return .failed(.network) }
        guard let player = try? JSON.parse(data) else { return .failed(.network) }
        guard let masterUrl = LiveParsers.hlsManifestUrl(player), let url = URL(string: masterUrl) else { return .failed(.notLive) }
        guard let masterData = try await fetch(URLRequest(url: url)) else { return .failed(.network) }
        let master = String(decoding: masterData, as: UTF8.self)
        let variants = LiveParsers.variants(master, masterUrl: masterUrl)
        guard let variant = video ? LiveParsers.chooseVideoVariant(variants) : LiveParsers.chooseAudioVariant(variants),
            isAllowedLiveManifestUrl(variant.url)
        else { return .failed(.noVariant) }
        let seconds = LiveParsers.expiresInSeconds(player).flatMap { $0 > 0 ? $0 : nil } ?? LiveClient.defaultLifetimeSeconds
        return .ok(LiveStream(videoId: videoId, url: variant.url, expiresAtMs: requestedAt + seconds * 1000 - LiveClient.expirySafetyMs))
    }

    /// 2xx の中身。通信の失敗・大きすぎる応答は nil。
    private func fetch(_ request: URLRequest) async throws -> Data? {
        // 念のため: cookie・認証のヘッダを持たないことを確かめる（共有の URLSession に cookie の保存があっても送らない）
        var request = request
        request.httpShouldHandleCookies = false
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(status), data.count <= LiveClient.maxResponseBytes else { return nil }
            return data
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return nil
        }
    }
}
