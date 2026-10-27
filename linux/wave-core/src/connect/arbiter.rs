//! 止まる規則（D-8、L-3・L-10）。再生中の端末が、ほかの player の署名を確かめた証拠を受けたときに、止まるかを決める（Android 版・Windows 版の PlaybackArbiter と同じ）。
//! 端末の時計は使わない。「相手の再生を見てから始めた方が後」で決め、互いを見ずに始めたときだけ、中継が claim に振った順番（claimOrder）で決める。
//! 順番がまだ分からなければ保留し、分かったときに決め直す。
//! 呼ぶ前に確かめておくこと（ここでは見ない）: 証拠の署名・書き手が家の player・一覧の頭が分岐していない・accountTag が自分と一致。

/// この端末の今の再生のセッション。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Session {
    /// セッションを始めたときに見ていた最新のカーソルの版。
    pub base: i64,
    /// このセッションで自分が最初に書けたカーソルの版（まだ書けていなければ None）。
    pub start_version: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Evidence {
    /// 自分宛の yield（相手は新しくセッションを始めたところで、まだ書いた版が無い）。
    Yield { from: String, session_base: i64 },
    /// 相手の、再生中のカーソル。一時停止のカーソルは証拠にしない。
    PlayingCursor { from: String, session_base: i64, session_start_version: Option<i64> },
}

impl Evidence {
    pub fn from(&self) -> &str {
        match self {
            Evidence::Yield { from, .. } | Evidence::PlayingCursor { from, .. } => from,
        }
    }

    pub fn session_base(&self) -> i64 {
        match self {
            Evidence::Yield { session_base, .. } | Evidence::PlayingCursor { session_base, .. } => *session_base,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    Stop,
    Keep,
    /// 同時に始めたが、どちらかの claimOrder がまだ分からない。分かったら決め直す。
    Defer,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reason {
    /// 相手は、こちらのセッションを見てから始めた（相手が後）。
    OtherStartedAfterMine,
    /// こちらが、相手のセッションを見てから始めた（こちらが後）。
    MineStartedAfterOther,
    /// 互いを見ずに始めた。claimOrder で決めた（または保留）。
    Concurrent,
    /// 自分自身の証拠。
    Own,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Verdict {
    pub decision: Decision,
    pub reason: Reason,
}

/// - `my_claim_order`: このセッションの自分の claim に中継が振った順番（まだ届いていなければ None）
/// - `other_claim_order`: 相手の最新の claim の順番（まだ届いていなければ None）
pub fn decide(my_id: &str, mine: Session, evidence: &Evidence, my_claim_order: Option<i64>, other_claim_order: Option<i64>) -> Verdict {
    let verdict = |decision, reason| Verdict { decision, reason };
    if evidence.from() == my_id {
        return verdict(Decision::Keep, Reason::Own);
    }
    if mine.start_version.is_some_and(|my_start| evidence.session_base() >= my_start) {
        return verdict(Decision::Stop, Reason::OtherStartedAfterMine);
    }
    if let Evidence::PlayingCursor { session_start_version: Some(other_start), .. } = evidence
        && mine.base >= *other_start
    {
        return verdict(Decision::Keep, Reason::MineStartedAfterOther);
    }
    match (my_claim_order, other_claim_order) {
        (Some(my), Some(other)) => verdict(if my < other { Decision::Stop } else { Decision::Keep }, Reason::Concurrent),
        _ => verdict(Decision::Defer, Reason::Concurrent),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(base: i64, start: Option<i64>) -> Session {
        Session { base, start_version: start }
    }

    fn yield_from(from: &str, base: i64) -> Evidence {
        Evidence::Yield { from: from.into(), session_base: base }
    }

    fn cursor(from: &str, base: i64, start: Option<i64>) -> Evidence {
        Evidence::PlayingCursor { from: from.into(), session_base: base, session_start_version: start }
    }

    fn decide_a(mine: Session, evidence: Evidence, my: Option<i64>, other: Option<i64>) -> Verdict {
        decide("A", mine, &evidence, my, other)
    }

    #[test]
    fn later_session_wins_regardless_of_claim_orders() {
        let a = session(9, Some(10));
        assert_eq!(Verdict { decision: Decision::Stop, reason: Reason::OtherStartedAfterMine }, decide_a(a, yield_from("B", 12), Some(9), Some(1)));
        assert_eq!(Decision::Stop, decide_a(a, cursor("B", 10, Some(13)), None, None).decision);
        assert_eq!(Verdict { decision: Decision::Keep, reason: Reason::MineStartedAfterOther }, decide_a(a, cursor("B", 4, Some(5)), Some(1), Some(9)));
    }

    #[test]
    fn concurrent_starts_are_settled_by_claim_order_and_deferred_until_known() {
        let a = session(20, Some(21));
        let b_cursor = cursor("B", 20, Some(22));
        assert_eq!(Verdict { decision: Decision::Stop, reason: Reason::Concurrent }, decide_a(a, b_cursor.clone(), Some(5), Some(6)));
        assert_eq!(Verdict { decision: Decision::Keep, reason: Reason::Concurrent }, decide_a(a, b_cursor.clone(), Some(6), Some(5)));
        assert_eq!(Decision::Defer, decide_a(a, b_cursor.clone(), None, Some(6)).decision);
        assert_eq!(Decision::Defer, decide_a(a, b_cursor, Some(5), None).decision);
        let offline_start = session(20, None);
        assert_eq!(Decision::Stop, decide_a(offline_start, yield_from("B", 20), Some(3), Some(4)).decision);
        assert_eq!(Decision::Keep, decide_a(offline_start, yield_from("B", 20), Some(4), Some(3)).decision);
        assert_eq!(Reason::Own, decide("B", session(30, Some(31)), &cursor("B", 30, Some(31)), None, None).reason);
    }
}
