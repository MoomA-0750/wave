import Foundation

/// 中継の接続の見張り（Android の RelayWatch・Windows の RelayWatch と同じ規則。docs/decisions.md 2026-11-14）。
/// 死んだソケットを「つながっている」と思い込んだまま、ほかの端末の再生が届かないのを防ぐ。
/// つなぎ始めてから 15 秒のうちに welcome が来なければ切る。つながっている間、20 秒何も届かなければ確かめを送り、
/// 8 秒のうちに何も届かなければ切る。前面に戻った・ネットにつながり直した・スリープから戻ったら（wake）、すぐ確かめを送る。
/// 時刻は呼ぶ側の単調時計（ミリ秒）。呼ぶ側で1本に並べる。
public struct RelayWatch: Sendable {
    public enum Action: Sendable, Equatable {
        case none
        /// 確かめを送る。
        case probe
        /// このソケットは死んでいる。切ってつなぎ直す。
        case dead
    }

    public static let welcomeTimeoutMs: Int64 = 15_000
    public static let idleMs: Int64 = 20_000
    public static let probeTimeoutMs: Int64 = 8_000
    public static let tickMs: Int64 = 1_000

    /// 確かめに送るもの。鎖のずっと先を頼む（中継は空の鎖を返すだけで、どの版の中継も答える）。
    public static let probe = "{\"op\":\"chain_get\",\"fromSeq\":2147483647}"

    private var openedAt: Int64 = 0
    private var welcomed = false
    private var lastReceivedAt: Int64 = 0
    private var probeSentAt: Int64?

    public init() {}

    /// 新しいソケットでつなぎ始めた。
    public mutating func opened(_ nowMs: Int64) {
        openedAt = nowMs
        welcomed = false
        lastReceivedAt = nowMs
        probeSentAt = nil
    }

    /// 中継から何か届いた。
    public mutating func received(_ nowMs: Int64, welcome: Bool = false) {
        if welcome { welcomed = true }
        lastReceivedAt = nowMs
        probeSentAt = nil
    }

    /// 前面に戻った・ネットにつながり直した。つながっていれば、すぐ確かめる。
    public mutating func wake(_ nowMs: Int64) -> Action {
        if !welcomed || probeSentAt != nil { return .none }
        probeSentAt = nowMs
        return .probe
    }

    /// 1秒ごとに呼ぶ。
    public mutating func tick(_ nowMs: Int64) -> Action {
        if !welcomed { return nowMs - openedAt >= RelayWatch.welcomeTimeoutMs ? .dead : .none }
        if let sent = probeSentAt { return nowMs - sent >= RelayWatch.probeTimeoutMs ? .dead : .none }
        if nowMs - lastReceivedAt >= RelayWatch.idleMs {
            probeSentAt = nowMs
            return .probe
        }
        return .none
    }

    /// つなぎ直すまでの待ち（ミリ秒）。failedAttempts は welcome を受けてから続けて失敗した数（つながっていたソケットが切れた直後は 0）。
    /// 1秒、2秒、4秒、8秒、16秒、以後30秒。
    public static func reconnectDelayMs(_ failedAttempts: Int) -> Int64 {
        failedAttempts <= 0 ? 1_000 : min(Int64(2_000) << Int64(min(failedAttempts - 1, 4)), 30_000)
    }

    /// 単調時計（ミリ秒。スリープの間も進む）。
    public static func nowMs() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }
}
