import Foundation

/// 再生の統計（YouTube Music の再生履歴に載せる）の送信（Android 版の ADR-0006 と同じ）。
/// 統計の URL は /player の応答のもので、music.youtube.com の /api/stats/playback に書き換えてから送る。
public final class PlaybackStats: @unchecked Sendable {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
    private static let sourceHosts: Set<String> = ["s.youtube.com", "music.youtube.com", "www.youtube.com"]

    private let client: InnerTubeClient

    public init(client: InnerTubeClient) {
        self.client = client
    }

    /// 再生1回ごとの ID（16文字）。乱数は暗号論的なもの（SystemRandomNumberGenerator）。
    public static func newCpn() -> String {
        var generator = SystemRandomNumberGenerator()
        return String((0..<16).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &generator)] })
    }

    /// 1回の再生を報告する。送れたら HTTP の状態、送らなかったら nil。
    public func report(stream: ResolvedStream, cpn: String, positionSeconds: Double) async -> Int? {
        guard let raw = stream.revealStatsUrl() else { return nil }
        guard let target = PlaybackStats.target(raw, cpn: cpn, cmt: max(0, (positionSeconds * 1000).rounded(.down) / 1000)),
            let url = URL(string: target)
        else { return nil }
        return await client.getStats(url, expectedGeneration: stream.generation)
    }

    static func target(_ statsUrl: String, cpn: String, cmt: Double) -> String? {
        guard let uri = CheckedURL(statsUrl) else { return nil }
        guard sourceHosts.contains(uri.host), uri.path == "/api/stats/playback" else { return nil }
        let kept = uri.query.split(separator: "&", omittingEmptySubsequences: true).map(String.init)
            .filter { p in
                let name = p.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
                return !["list", "cpn", "cmt", "ver", "c", "prettyPrint"].contains(name)
            }
        let query = (kept + [
            "prettyPrint=false", "ver=2", "c=" + InnerTubeClient.clientName, "cpn=" + cpn,
            "cmt=" + invariantString(cmt),
        ]).joined(separator: "&")
        return "https://music.youtube.com/api/stats/playback?" + query
    }
}
