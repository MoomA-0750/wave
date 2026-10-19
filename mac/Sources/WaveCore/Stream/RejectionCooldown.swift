import Foundation

/// 解決し直しても拒否された曲を覚えて、しばらく解決し直さない（/player の連打を防ぐ。Android の ResolvedStreamCache の
/// markRejected・recentlyRejected と同じ。SPEC「拒否が続く曲は少しの間解決し直さない」）。時刻は単調な時計のミリ秒。
public struct RejectionCooldown: Sendable {
    public static let cooldownMs: Int64 = 2 * 60 * 1000

    private var rejectedAt: [String: Int64] = [:]

    public init() {}

    /// 解決し直しても再生できなかった。
    public mutating func markRejected(_ videoId: String, nowMs: Int64) {
        rejectedAt[videoId] = nowMs
    }

    /// 拒否されてから 2 分たっていないか（たっていれば忘れる）。
    public mutating func recentlyRejected(_ videoId: String, nowMs: Int64) -> Bool {
        guard let at = rejectedAt[videoId] else { return false }
        if nowMs - at < RejectionCooldown.cooldownMs { return true }
        rejectedAt.removeValue(forKey: videoId)
        return false
    }

    public mutating func clear() { rejectedAt.removeAll() }
}
