import Foundation

/// 止まる規則（D-8、L-3・L-10）。再生中の端末が、ほかの player の署名を確かめた証拠を受けたときに、止まるかを決める（Android 版と同じ）。
/// 端末の時計は使わない。「相手の再生を見てから始めた方が後」で決め、互いを見ずに始めたときだけ、中継が claim に振った順番（claimOrder）で決める。
/// 順番がまだ分からなければ保留し、分かったときに決め直す。
/// 呼ぶ前に確かめておくこと（ここでは見ない）: 証拠の署名・書き手が家の player・一覧の頭が分岐していない・accountTag が自分と一致。
public enum PlaybackArbiter {
    /// この端末の今の再生のセッション。
    public struct Session: Equatable, Sendable {
        /// セッションを始めたときに見ていた最新のカーソルの版
        public var base: Int64

        /// このセッションで自分が最初に書けたカーソルの版（まだ書けていなければ nil）
        public var startVersion: Int64?

        public init(base: Int64, startVersion: Int64?) {
            self.base = base
            self.startVersion = startVersion
        }
    }

    public enum Evidence: Equatable, Sendable {
        /// 自分宛の yield（相手は新しくセッションを始めたところで、まだ書いた版が無い）。
        case yield(from: String, sessionBase: Int64)

        /// 相手の、再生中のカーソル。一時停止のカーソルは証拠にしない。
        case playingCursor(from: String, sessionBase: Int64, sessionStartVersion: Int64?)

        public var from: String {
            switch self {
            case .yield(let from, _), .playingCursor(let from, _, _): return from
            }
        }

        public var sessionBase: Int64 {
            switch self {
            case .yield(_, let base), .playingCursor(_, let base, _): return base
            }
        }
    }

    public enum Decision: Sendable {
        case stop
        case keep

        /// 同時に始めたが、どちらかの claimOrder がまだ分からない。分かったら決め直す。
        case `defer`
    }

    public enum Reason: Sendable {
        /// 相手は、こちらのセッションを見てから始めた（相手が後）。
        case otherStartedAfterMine

        /// こちらが、相手のセッションを見てから始めた（こちらが後）。
        case mineStartedAfterOther

        /// 互いを見ずに始めた。claimOrder で決めた（または保留）。
        case concurrent

        /// 自分自身の証拠。
        case selfEvidence
    }

    public struct Result: Equatable, Sendable {
        public let decision: Decision
        public let reason: Reason

        public init(_ decision: Decision, _ reason: Reason) {
            self.decision = decision
            self.reason = reason
        }
    }

    /// - Parameters:
    ///   - myClaimOrder: このセッションの自分の claim に中継が振った順番（まだ届いていなければ nil）
    ///   - otherClaimOrder: 相手の最新の claim の順番（まだ届いていなければ nil）
    public static func decide(myId: String, mine: Session, evidence: Evidence, myClaimOrder: Int64?, otherClaimOrder: Int64?) -> Result {
        if evidence.from == myId { return Result(.keep, .selfEvidence) }
        if let myStart = mine.startVersion, evidence.sessionBase >= myStart { return Result(.stop, .otherStartedAfterMine) }
        if case .playingCursor(_, _, let otherStart?) = evidence, mine.base >= otherStart { return Result(.keep, .mineStartedAfterOther) }
        guard let my = myClaimOrder, let other = otherClaimOrder else { return Result(.defer, .concurrent) }
        return Result(my < other ? .stop : .keep, .concurrent)
    }
}
