import Foundation

/// 本人が再生を始めたときの判断（D-9、Android・Windows の PlayButtonPolicy と同じ）。
enum PlayButtonPolicy {
    enum Decision: String {
        /// 手元のキューで新しい再生として claim する。
        case claim

        /// 家で最後に鳴った再生（キューと位置）に手元を合わせてから claim する。
        case syncAndClaim

        /// 一時停止して、最新の状態とアカウントタグがそろうのを待つ。
        case wait

        /// 他の端末で鳴っている: 引き取る（前面）。
        case takeOver

        /// 他の端末で鳴っている: 鳴らさずに知らせる（裏）。
        case blockRemotePlaying

        /// 待ちで止めた再生を戻して claim する。
        case resumeAndClaim

        /// 何もしない（待っている間に本人が別の操作をした・もう退いた端末でない）。
        case nothing
        case notifyNotMember
        case notifyUnreachable
        case notifyUnverified
    }

    enum WaitOutcome: String { case ready, notMember, unreachable, unverified }

    /// - Parameters:
    ///   - newSelection: 曲を選んだ（キューを置き換えた・飛んだ）
    ///   - retreated: 他の端末に引き取られた・止められた印がある
    ///   - stateSettled: 今の接続で中継の最新の状態をそろえた
    ///   - hasLocalQueue: 手元にキューがある（前に聴いていたキューを戻してある）
    static func onPlay(
        newSelection: Bool, retreated: Bool, inHousehold: Bool, stateSettled: Bool, hasAccountTag: Bool, remotePlaying: Bool, foreground: Bool,
        hasLocalQueue: Bool = false
    ) -> Decision {
        // 曲を選ぶのは、いつでも新しい再生（L-3）
        if newSelection || !inHousehold { return .claim }
        // 前面で再生ボタンを押したら、待たずにこの端末で鳴らす。キューは家で最後に鳴ったもの（ほかの端末で鳴っていればその位置）に合わせる
        // （Android と同じ。止まっている間も合わせてある。ほかの端末は claim で止まる）。状態がそろう前は手元のまま鳴らす
        if foreground && (hasLocalQueue || !retreated) { return stateSettled && hasAccountTag ? .syncAndClaim : .claim }
        if !retreated { return .claim }
        // 古い状態で「鳴っていない」と決めない
        if !stateSettled || !hasAccountTag { return .wait }
        if !remotePlaying { return .claim }
        return foreground ? .takeOver : .blockRemotePlaying
    }

    /// 待ちの1回分。そろっていれば ready、まだなら nil。
    static func waitStep(notMember: Bool, tailnetUnreachable: Bool, settledAndTagged: () -> Bool) -> WaitOutcome? {
        notMember ? .notMember
            : tailnetUnreachable ? .unreachable
            : settledAndTagged() ? .ready
            : nil
    }

    /// 時間切れ: 状態はそろっているならアカウントの問題、そうでなければ中継の問題。
    static func timeoutOutcome(settled: Bool) -> WaitOutcome { settled ? .unverified : .unreachable }

    static func afterWait(_ outcome: WaitOutcome, intentUnchanged: Bool, stillRetreated: Bool, remotePlaying: Bool, foreground: Bool) -> Decision {
        if !intentUnchanged || !stillRetreated { return .nothing }
        switch outcome {
        case .ready: return !remotePlaying ? .resumeAndClaim : foreground ? .takeOver : .blockRemotePlaying
        case .notMember: return .notifyNotMember
        case .unreachable: return .notifyUnreachable
        case .unverified: return .notifyUnverified
        }
    }
}
