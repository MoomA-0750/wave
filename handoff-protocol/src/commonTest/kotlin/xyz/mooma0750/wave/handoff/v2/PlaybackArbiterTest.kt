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
        myOrder: Long? = null,
        otherOrder: Long? = null,
    ) = PlaybackArbiter.decide("A", mine, evidence, myOrder, otherOrder)

    @Test
    fun laterSessionWinsRegardlessOfClaimOrders() {
        // A はカーソル v10 を書いて再生中（セッションは v9 を見て始め、最初に書けたのが v10）
        val a = Session(base = 9, startVersion = 10)
        // B は A の v12 を見てから始めた → A が止まる（claimOrder が逆でも）
        assertEquals(PlaybackArbiter.Result(Decision.Stop, Reason.OtherStartedAfterMine), decide(a, Evidence.Yield("B", sessionBase = 12), myOrder = 9, otherOrder = 1))
        assertEquals(Decision.Stop, decide(a, Evidence.PlayingCursor("B", sessionBase = 10, sessionStartVersion = 13)).decision)
        // A は B のセッション（v5 から）を見てから始めた → A は止まらない
        assertEquals(PlaybackArbiter.Result(Decision.Keep, Reason.MineStartedAfterOther), decide(a, Evidence.PlayingCursor("B", sessionBase = 4, sessionStartVersion = 5), myOrder = 1, otherOrder = 9))
    }

    @Test
    fun theOtherSideReachesTheOppositeDecision() {
        val a = Session(base = 9, startVersion = 10)
        val b = Session(base = 12, startVersion = 13)
        assertEquals(Decision.Stop, PlaybackArbiter.decide("A", a, Evidence.PlayingCursor("B", b.base, b.startVersion), 1, 2).decision)
        assertEquals(Decision.Keep, PlaybackArbiter.decide("B", b, Evidence.PlayingCursor("A", a.base, a.startVersion), 2, 1).decision)
    }

    @Test
    fun concurrentStartsAreSettledByClaimOrderAndDeferredUntilKnown() {
        // 2台とも v20 を見て同時に始めた
        val a = Session(base = 20, startVersion = 21)
        val bCursor = Evidence.PlayingCursor("B", sessionBase = 20, sessionStartVersion = 22)
        assertEquals(PlaybackArbiter.Result(Decision.Stop, Reason.Concurrent), decide(a, bCursor, myOrder = 5, otherOrder = 6))
        assertEquals(PlaybackArbiter.Result(Decision.Keep, Reason.Concurrent), decide(a, bCursor, myOrder = 6, otherOrder = 5))
        // どちらかの順番がまだ届いていなければ保留（受けた順番で決めない）
        assertEquals(Decision.Defer, decide(a, bCursor, myOrder = null, otherOrder = 6).decision)
        assertEquals(Decision.Defer, decide(a, bCursor, myOrder = 5, otherOrder = null).decision)
        // 両側の結論は逆になる
        val b = Session(base = 20, startVersion = 22)
        assertEquals(Decision.Keep, PlaybackArbiter.decide("B", b, Evidence.PlayingCursor("A", 20, 21), 6, 5).decision)
        // まだ書けていない（通信が無い間に始めた）端末への yield も同時の扱い
        val offlineStart = Session(base = 20, startVersion = null)
        assertEquals(Decision.Stop, decide(offlineStart, Evidence.Yield("B", sessionBase = 20), myOrder = 3, otherOrder = 4).decision)
        assertEquals(Decision.Keep, decide(offlineStart, Evidence.Yield("B", sessionBase = 20), myOrder = 4, otherOrder = 3).decision)
    }

    @Test
    fun reconnectingDeviceThatKeptPlayingStops() {
        val a = Session(base = 29, startVersion = 30)
        assertEquals(Decision.Stop, decide(a, Evidence.PlayingCursor("B", sessionBase = 30, sessionStartVersion = 31)).decision)
        assertEquals(Reason.Self, PlaybackArbiter.decide("B", Session(30, 31), Evidence.PlayingCursor("B", 30, 31), null, null).reason)
    }
}
