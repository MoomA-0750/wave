import Foundation

/// 1回の再生で聴いた時間（ADR-0006 の 1。Android の ListenCounter と同じ）。位置ではなく、単調な時計で再生中の区間だけを足す。
/// シークしただけでは増えない。他の端末から引き取ったときは、相手が聴いた時間から続ける（決定 K-8）。
public struct ListenCounter: Sendable {
    public static let thresholdMs: Int64 = 30_000
    public static let minThresholdMs: Int64 = 5_000

    /// 30秒。曲が30秒より短ければ長さの半分（5秒以上）。長さが分からなければ30秒。
    public static func threshold(durationMs: Int64?) -> Int64 {
        guard let duration = durationMs, duration > 0 else { return thresholdMs }
        return duration < thresholdMs ? max(duration / 2, minThresholdMs) : thresholdMs
    }

    private var listened: Int64
    private var playingSinceMs: Int64?

    public init(listenedMs: Int64 = 0) {
        listened = max(0, listenedMs)
    }

    public var running: Bool { playingSinceMs != nil }

    /// 再生が始まった（既に数えていれば何もしない）。
    public mutating func start(_ nowMs: Int64) {
        if playingSinceMs == nil { playingSinceMs = nowMs }
    }

    /// 止まった。それまでの区間を足す。
    public mutating func stop(_ nowMs: Int64) {
        if let since = playingSinceMs { listened = ListenCounter.saturatingAdd(listened, max(0, nowMs - since)) }
        playingSinceMs = nil
    }

    /// 聴いた時間を置き換える（数えている途中なら、今から数え直す）。
    public mutating func reset(listenedMs: Int64, playing: Bool, nowMs: Int64) {
        listened = max(0, listenedMs)
        playingSinceMs = playing ? nowMs : nil
    }

    public func listenedMs(_ nowMs: Int64) -> Int64 {
        ListenCounter.saturatingAdd(listened, playingSinceMs.map { max(0, nowMs - $0) } ?? 0)
    }

    /// この再生で履歴を送るときか（まだ送っておらず、聴いた時間が threshold に達した）。
    public func shouldSend(nowMs: Int64, durationMs: Int64?, alreadySent: Bool) -> Bool {
        !alreadySent && listenedMs(nowMs) >= ListenCounter.threshold(durationMs: durationMs)
    }

    private static func saturatingAdd(_ a: Int64, _ b: Int64) -> Int64 { a > Int64.max - b ? Int64.max : a + b }
}
