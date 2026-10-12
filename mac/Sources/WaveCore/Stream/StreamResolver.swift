import Foundation

public enum StreamFailure: Sendable, Equatable {
    case notLoggedIn
    case network
    case http
    case parse
    case playerConfigUnavailable
    case unplayable
    case noAudioFormat
    case sabrOnly
    case decipherFailed
    case rejectedUrl
    /// ライブ配信だった（ライブの印の無い曲として渡された）。HLS（LiveClient）で流し直す。
    case liveStream
}

/// 解決済みのストリーム。署名付き URL を含むので、description・debugDescription・Mirror に出さない。
public final class ResolvedStream: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    private let url: String
    private let statsUrl: String?

    public let videoId: String
    public let itag: Int
    public let mimeType: String
    public let contentLength: Int64?
    public let expiresAtMs: Int64
    public let generation: Int64
    /// DASH の目次を作るための値（初期化と索引のバイト範囲など）。無ければ nil。
    public let dash: DashInfo?

    init(videoId: String, itag: Int, mimeType: String, contentLength: Int64?, url: String, expiresAtMs: Int64, statsUrl: String?, generation: Int64, dash: DashInfo? = nil) {
        self.dash = dash
        self.videoId = videoId
        self.itag = itag
        self.mimeType = mimeType
        self.contentLength = contentLength
        self.url = url
        self.expiresAtMs = expiresAtMs
        self.statsUrl = statsUrl
        self.generation = generation
    }

    public var hasPlaybackStats: Bool { statsUrl != nil }

    /// 音声を取得するところだけが呼ぶ。ログ・例外文・画面に渡さない。
    public func revealUrl() -> String { url }

    func revealStatsUrl() -> String? { statsUrl }

    public var description: String {
        "ResolvedStream(videoId=\(videoId), itag=\(itag), mimeType=\(mimeType), contentLength=\(contentLength.map(String.init) ?? ""))"
    }

    public var debugDescription: String { description }

    public var customMirror: Mirror {
        Mirror(self, children: ["videoId": videoId, "itag": itag, "mimeType": mimeType, "contentLength": contentLength as Any], displayStyle: .class)
    }
}

/// DASH（SegmentBase）の目次を作るための、形式ごとの値。
public struct DashInfo: Hashable, Sendable {
    public var initStart: Int64
    public var initEnd: Int64
    public var indexStart: Int64
    public var indexEnd: Int64
    public var bitrate: Int64
    public var sampleRate: Int
    public var durationMs: Int64
    public var codecs: String
    public var width: Int
    public var height: Int
    public var frameRate: Int

    public init(initStart: Int64, initEnd: Int64, indexStart: Int64, indexEnd: Int64, bitrate: Int64, sampleRate: Int, durationMs: Int64, codecs: String,
                width: Int = 0, height: Int = 0, frameRate: Int = 0)
    {
        self.initStart = initStart
        self.initEnd = initEnd
        self.indexStart = indexStart
        self.indexEnd = indexEnd
        self.bitrate = bitrate
        self.sampleRate = sampleRate
        self.durationMs = durationMs
        self.codecs = codecs
        self.width = width
        self.height = height
        self.frameRate = frameRate
    }
}

/// 取るもの。映像は音の無い映像だけ（曲に重ねる MV）。
public enum StreamKind: Sendable {
    case audio
    case video
}

public struct ResolveResult: Sendable {
    public let stream: ResolvedStream?
    public let failure: StreamFailure?

    public static func ok(_ stream: ResolvedStream) -> ResolveResult { ResolveResult(stream: stream, failure: nil) }
    public static func failed(_ failure: StreamFailure) -> ResolveResult { ResolveResult(stream: nil, failure: failure) }
}

/// WEB_REMIX の /player から音声のストリーム URL を解決する（Android 版の ADR-0001 と同じ経路。po_token は作らない）。
public final class StreamResolver: @unchecked Sendable {
    /// 優先順。Windows の標準の再生（Media Foundation）が確実に鳴らせる AAC（m4a）を先にし、opus は後にする。
    /// AAC 256kbps → AAC 128 → opus 約 256 → opus 160 → opus 70 → opus 50。
    /// （macOS の AVPlayer も AAC は確実に鳴らせ、opus（webm）は鳴らせないことがあるので、同じ順のままにする）
    public static let audioItagPreference = [141, 140, 774, 251, 250, 249]
    private static let unwantedParameters: Set<String> = ["pot", "ump"]
    private static let defaultLifetimeSeconds: Int64 = 60 * 60
    private static let expirySafetyMs: Int64 = 60 * 1000

    private let client: InnerTubeClient
    private let cipher: PlayerCipherProvider
    private let nowMs: @Sendable () -> Int64

    public init(client: InnerTubeClient, cipher: PlayerCipherProvider, nowMs: @escaping @Sendable () -> Int64) {
        self.client = client
        self.cipher = cipher
        self.nowMs = nowMs
    }

    public func resolve(videoId: String, kind: StreamKind = .audio) async throws -> ResolveResult {
        guard videoId.wholeMatch(of: #/[A-Za-z0-9_-]{11}/#) != nil else { return .failed(.parse) }
        guard let config = try await cipher.get() else { return .failed(.playerConfigUnavailable) }
        let requestedAt = nowMs()
        let generation = client.generation
        let body: JSON = .obj([
            "videoId": .string(videoId),
            "playbackContext": .obj([
                "contentPlaybackContext": .obj(["html5Preference": "HTML5_PREF_WANTS", "signatureTimestamp": JSON(config.signatureTimestamp)]),
            ]),
            "contentCheckOk": true,
            "racyCheckOk": true,
            "videoCheckOk": true,
        ])
        let response = try await client.post("player", body: body)
        guard response.isOk, let root = response.value else {
            switch response.failure {
            case .notLoggedIn: return .failed(.notLoggedIn)
            case .network: return .failed(.network)
            case .parse: return .failed(.parse)
            default: return .failed(.http)
            }
        }
        // ライブ配信は HLS で流す（LiveClient）。WEB_REMIX の応答では再生できない
        if LiveParsers.isLive(root) { return .failed(.liveStream) }
        let status = root.path("playabilityStatus", "status").str
        if status == "LOGIN_REQUIRED" { return .failed(.notLoggedIn) }
        if status != "OK" { return .failed(.unplayable) }
        let streaming = root.path("streamingData")
        let formats = streaming.path("adaptiveFormats").items
        let candidates = kind == .video ? StreamResolver.videoCandidates(formats) : StreamResolver.audioCandidates(formats)
        if candidates.isEmpty {
            return .failed(!formats.isEmpty && formats.allSatisfy { !StreamResolver.hasSource($0) } ? .sabrOnly : .noAudioFormat)
        }

        for format in candidates {
            guard var url = StreamResolver.decipher(format, solver: config.solver) else { continue }
            url = StreamResolver.removeQueryParameters(url, names: StreamResolver.unwantedParameters)
            if !StreamResolver.isAllowedMediaUrl(url) { return .failed(.rejectedUrl) }
            let seconds = streaming.path("expiresInSeconds").long
            let lifetime = ((seconds ?? 0) > 0 ? seconds! : StreamResolver.defaultLifetimeSeconds) * 1000
            let statsUrl = generation == client.generation && kind == .audio ? root.path("playbackTracking", "videostatsPlaybackUrl", "baseUrl").str : nil
            return .ok(ResolvedStream(
                videoId: videoId,
                // 候補は itag のあるものだけ（映像で無ければ 0）
                itag: format.path("itag").int ?? 0,
                mimeType: format.path("mimeType").str ?? "audio/mp4",
                contentLength: format.path("contentLength").long,
                url: url,
                expiresAtMs: requestedAt + lifetime - StreamResolver.expirySafetyMs,
                statsUrl: statsUrl,
                generation: generation,
                dash: StreamResolver.dashOf(format)))
        }
        cipher.invalidate()
        return .failed(.decipherFailed)
    }

    static func dashOf(_ format: JSON) -> DashInfo? {
        let initRange = format.path("initRange")
        let index = format.path("indexRange")
        guard let i0 = initRange.path("start").long, let i1 = initRange.path("end").long,
            let x0 = index.path("start").long, let x1 = index.path("end").long
        else { return nil }
        let mime = format.path("mimeType").str ?? ""
        let codecs = mime.firstMatch(of: #/codecs="([^"]+)"/#).map { String($0.output.1) } ?? "mp4a.40.2"
        return DashInfo(initStart: i0, initEnd: i1, indexStart: x0, indexEnd: x1, bitrate: format.path("bitrate").long ?? 128000,
                        sampleRate: Int(format.path("audioSampleRate").long ?? 44100),
                        durationMs: format.path("approxDurationMs").long ?? 0, codecs: codecs,
                        width: Int(format.path("width").long ?? 0), height: Int(format.path("height").long ?? 0), frameRate: Int(format.path("fps").long ?? 0))
    }

    /// Premium 音質（AAC 256kbps・opus 約 256kbps）の形式の itag。
    public static let premiumItags = [141, 774]

    /// Premium 音質を使えるか（Android の premiumStatus と同じ）。決まった1曲で Premium の形式が返るかを見る。分からなければ nil。
    public func premiumAudio() async throws -> Bool? {
        guard let config = try await cipher.get() else { return nil }
        let body: JSON = .obj([
            "videoId": .string(StreamResolver.premiumProbeVideoId),
            "playbackContext": .obj(["contentPlaybackContext": .obj(["signatureTimestamp": JSON(config.signatureTimestamp)])]),
            "contentCheckOk": true,
            "racyCheckOk": true,
        ])
        let response = try await client.post("player", body: body)
        guard response.isOk, let root = response.value, root.path("playabilityStatus", "status").str == "OK" else { return nil }
        return root.path("streamingData", "adaptiveFormats").items.contains { f in
            guard let itag = f.path("itag").long else { return false }
            return StreamResolver.premiumItags.contains(Int(itag))
        }
    }

    private static let premiumProbeVideoId = "dQw4w9WgXcQ"

    /// 解決した URL が拒否された（403 など）。次は解読の準備を取り直す。
    public func onStreamRejected() { cipher.invalidate() }

    public func isFresh(_ stream: ResolvedStream, marginMs: Int64) -> Bool { stream.expiresAtMs - nowMs() > marginMs }

    /// signatureCipher を解いて n を変換した URL。解けなければ nil。
    static func decipher(_ format: JSON, solver: CipherSolver) -> String? {
        var url = format.path("url").str
        let cipherText = format.path("signatureCipher").str ?? format.path("cipher").str
        if url == nil, let cipherText {
            let p = parseQueryString(cipherText)
            let sp = p["sp"] ?? "signature"
            guard let s = p["s"], let baseUrl = p["url"], isAllowedMediaUrl(baseUrl), sp.wholeMatch(of: #/[A-Za-z0-9_]{1,32}/#) != nil else { return nil }
            guard let solved = solver.solveSignature(s) else { return nil }
            url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + sp + "=" + InnerTubeClient.escape(solved)
        }
        guard var url else { return nil }
        if let n = url.firstMatch(of: #/([?&])n=([^&]+)/#) {
            let value = n.output.2
            let original = String(value).removingPercentEncoding ?? String(value)
            // n を解けない URL は遅い（または拒否される）ので使わない
            guard let transformed = solver.solveN(original) else { return nil }
            url = String(url[..<value.startIndex]) + InnerTubeClient.escape(transformed) + String(url[value.endIndex...])
        }
        return url
    }

    /// HttpUtility.ParseQueryString と同じ読み方（+ は空白、% は戻す、同じ名前が並べば「,」でつなぐ）。
    static func parseQueryString(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        let query = text.hasPrefix("?") ? String(text.dropFirst()) : text
        for part in query.split(separator: "&", omittingEmptySubsequences: true) {
            let pieces = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pieces.count == 2 else { continue }
            func decode(_ s: Substring) -> String {
                let spaced = s.replacingOccurrences(of: "+", with: " ")
                return spaced.removingPercentEncoding ?? spaced
            }
            let name = decode(pieces[0])
            let value = decode(pieces[1])
            result[name] = result[name].map { $0 + "," + value } ?? value
        }
        return result
    }

    /// 映像の高さの上限（右の欄・全画面でも足りる高さで、通信量を抑える）。
    public static let maxVideoHeight: Int64 = 720

    /// 映像だけのフォーマット。Windows の標準の再生が DASH で確実に鳴らせる H.264（mp4・avc1）だけを、高い方から。
    public static func videoCandidates(_ formats: [JSON]) -> [JSON] {
        formats
            .filter { f in
                guard let m = f.path("mimeType").str else { return false }
                return m.hasPrefix("video/mp4") && m.contains("avc1")
            }
            .filter { ($0.path("height").long ?? Int64.max) <= maxVideoHeight }
            .filter(hasSource)
            .stableSorted(by: { $0.path("height").long ?? 0 }, descending: true)
    }

    public static func audioCandidates(_ formats: [JSON]) -> [JSON] {
        formats
            .filter { $0.path("mimeType").str?.hasPrefix("audio/") == true }
            .filter { !isTrue($0.path("isDrc")) }
            .filter { f in
                guard let itag = f.path("itag").long else { return false }
                return audioItagPreference.contains(Int(itag))
            }
            // 吹き替えなど音声トラックが複数ある曲では、既定のトラックだけを使う
            .filter { present($0.path("audioTrack")) == nil || isTrue($0.path("audioTrack", "audioIsDefault")) }
            .filter(hasSource)
            .stableSorted(by: { audioItagPreference.firstIndex(of: Int($0.path("itag").long ?? 0)) ?? -1 })
    }

    private static func isTrue(_ node: JSON?) -> Bool { node?.bool == true }

    private static func hasSource(_ f: JSON) -> Bool {
        present(f.path("url")) != nil || present(f.path("signatureCipher")) != nil || present(f.path("cipher")) != nil
    }

    /// googlevideo の /videoplayback への https だけを使う。
    public static func isAllowedMediaUrl(_ url: String) -> Bool {
        guard let uri = CheckedURL(url) else { return false }
        return (uri.host == "googlevideo.com" || uri.host.hasSuffix(".googlevideo.com")) && uri.path == "/videoplayback"
    }

    /// クエリの並びと他の値の符号化を変えずに、指定した名前のパラメータだけを外す。
    public static func removeQueryParameters(_ url: String, names: Set<String>) -> String {
        guard let queryStart = url.firstIndex(of: "?") else { return url }
        let fragmentStart = url[queryStart...].firstIndex(of: "#") ?? url.endIndex
        let kept = url[url.index(after: queryStart)..<fragmentStart].split(separator: "&", omittingEmptySubsequences: false)
            .filter { p in
                !p.isEmpty && !names.contains(String(p.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""))
            }
        let baseUrl = String(url[..<queryStart])
        return (kept.isEmpty ? baseUrl : baseUrl + "?" + kept.joined(separator: "&")) + String(url[fragmentStart...])
    }
}
