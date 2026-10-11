import Foundation

/// 映像だけの再生器を、音の再生位置に合わせる判断（Android の VideoSync と同じ）。音は止めずに、映像の方を飛ばす・速さを少し変えて追いつかせる。
/// 画面にも再生の部品にも依らない。
public enum VideoSync {
    public enum Step: Equatable, Sendable {
        /// 映像に当たる部分が無い（MV に無い区間）。映像を止めて隠す。
        case hide
        case seek(toMs: Int64, play: Bool)
        case run(speed: Double, play: Bool)
    }

    /// これより離れたら飛ぶ。近ければ速さで合わせる（飛ぶと映像が一瞬止まるので）。
    public static let seekThresholdMs: Int64 = 700

    /// これ以内なら合っているとみなす。
    public static let inSyncMs: Int64 = 40

    /// 止まっているときに合わせ直す差。
    public static let pausedToleranceMs: Int64 = 80

    /// 速さの変え幅の上限（見た目に不自然でない範囲）。
    public static let maxSpeedChange = 0.1

    /// - Parameters:
    ///   - targetMs: 今の音の位置に当たる映像の位置。nil なら当たる部分が無い
    ///   - videoReady: 映像の再生器が読み込み中でない（読み込み中に飛び直すと、いつまでも追いつかない）
    public static func decide(targetMs: Int64?, videoMs: Int64, playing: Bool, videoReady: Bool) -> Step {
        guard let target = targetMs else { return .hide }
        let diff = videoMs - target
        if !playing { return abs(diff) > pausedToleranceMs ? .seek(toMs: target, play: false) : .run(speed: 1, play: false) }
        if !videoReady { return .run(speed: 1, play: true) }
        if abs(diff) > seekThresholdMs { return .seek(toMs: target, play: true) }
        if abs(diff) <= inSyncMs { return .run(speed: 1, play: true) }
        // 映像が進んでいれば遅く、遅れていれば速く。1秒で差を埋める速さ（上限あり）
        return .run(speed: min(max(1 - Double(diff) / 1000.0, 1 - maxSpeedChange), 1 + maxSpeedChange), play: true)
    }
}
