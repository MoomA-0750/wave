package xyz.mooma0750.wave.handoff.v2

import xyz.mooma0750.wave.handoff.v2.PlaybackArbiter.Decision
import xyz.mooma0750.wave.handoff.v2.PlaybackArbiter.Evidence
import xyz.mooma0750.wave.handoff.v2.PlaybackArbiter.Reason
import xyz.mooma0750.wave.handoff.v2.PlaybackArbiter.Session
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackArbiterTest {
    private fun decide(
        mine: Session,
        evidence: Evidence,
        active: String? = null,
    ) = PlaybackArbiter.decide("A", mine, evidence, active)

    @Test
    fun laterSessionWinsRegardlessOfClocks() {
        // A はカーソル v10 を書いて再生中（セッションは v9 を見て始め、最初に書けたのが v10）
        val a = Session(base = 9, startVersion = 10)
        // B は A の v12 を見てから始めた → A が止まる（サーバーが A を再生中と言っていても）
        assertEquals(PlaybackArbiter.Result(Decision.Stop, Reason.OtherStartedAfterMine), decide(a, Evidence.Yield("B", sessionBase = 12), active = "A"))
        assertEquals(Decision.Stop, decide(a, Evidence.PlayingCursor("B", sessionBase = 10, sessionStartVersion = 13)).decision)
        // A は B のセッション（v5 から）を見てから始めた → A は止まらない（B が A のカーソルを見て止まる）
        assertEquals(PlaybackArbiter.Result(Decision.Keep, Reason.MineStartedAfterOther), decide(a, Evidence.PlayingCursor("B", sessionBase = 4, sessionStartVersion = 5), active = "B"))
    }

    @Test
    fun theOtherSideReachesTheOppositeDecision() {
        // 2台が同じ証拠を互いに見たとき、片方だけが止まる
        val a = Session(base = 9, startVersion = 10)
        val b = Session(base = 12, startVersion = 13)
        val aDecision = PlaybackArbiter.decide("A", a, Evidence.PlayingCursor("B", b.base, b.startVersion), serverActiveDeviceId = "A").decision
        val bDecision = PlaybackArbiter.decide("B", b, Evidence.PlayingCursor("A", a.base, a.startVersion), serverActiveDeviceId = "A").decision
        assertEquals(Decision.Stop, aDecision)
        assertEquals(Decision.Keep, bDecision)
    }

    @Test
    fun concurrentStartsAreSettledByTheServersChoiceOnly() {
        // 2台とも v20 を見て同時に始めた（どちらも相手の書いた版を見ていない）
        val a = Session(base = 20, startVersion = 21)
        val bCursor = Evidence.PlayingCursor("B", sessionBase = 20, sessionStartVersion = 22)
        // A の base(20) < B の start(22)、B の base(20) < A の start(21) → 同時
        assertEquals(PlaybackArbiter.Result(Decision.Stop, Reason.Concurrent), decide(a, bCursor, active = "B"))
        assertEquals(PlaybackArbiter.Result(Decision.Keep, Reason.Concurrent), decide(a, bCursor, active = "A"))
        // まだ書けていない（通信が無い間に始めた）端末への yield も同時の扱い
        val offlineStart = Session(base = 20, startVersion = null)
        assertEquals(Decision.Stop, decide(offlineStart, Evidence.Yield("B", sessionBase = 20), active = "B").decision)
        assertEquals(Decision.Keep, decide(offlineStart, Evidence.Yield("B", sessionBase = 20), active = null).decision)
    }

    @Test
    fun reconnectingDeviceThatKeptPlayingStops() {
        // A は v30 で書いた後に通信を失い、鳴り続けた。その間に B が v30 を見て引き取り v31 を書いた
        val a = Session(base = 29, startVersion = 30)
        assertEquals(Decision.Stop, decide(a, Evidence.PlayingCursor("B", sessionBase = 30, sessionStartVersion = 31), active = "B").decision)
        // 自分自身の証拠では止まらない
        assertEquals(Reason.Self, PlaybackArbiter.decide("B", Session(30, 31), Evidence.PlayingCursor("B", 30, 31), "A").reason)
    }
}
