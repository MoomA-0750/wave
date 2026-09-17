package xyz.mooma0750.wave.handoff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffFlowTest {
    private var now = 0L
    private val handoffId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun snapshotText(id: String = handoffId) =
        HandoffCodec.encode(
            HandoffSnapshot(
                HandoffCodec.SCHEMA_VERSION, id,
                listOf(HandoffItem("00000000-0000-4000-8000-000000000001", "abcdefghijk", "t", emptyList(), null, 100, null, HandoffOrigin.Source)),
                "00000000-0000-4000-8000-000000000001", 0, true, HandoffRepeat.Off, null, null, null, true, 0, false, 0,
            ),
        )

    @Test
    fun takerAdoptsOnlyItsOwnPendingRequest() {
        val taker = HandoffTaker(nowMs = { now })
        // 要求していないのに届いた snapshot（押し付け）は受けない
        assertEquals(HandoffTaker.SnapshotDecision.Ignore("not_waiting", sendCancel = false), taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), true))
        assertTrue(taker.request("A", handoffId))
        assertFalse(taker.request("A", "another"), "要求を2つ持った")
        // 別の端末から
        assertIs<HandoffTaker.SnapshotDecision.Ignore>(taker.onSnapshot("C", HandoffBody.Snapshot("h", snapshotText()), true))
        // 別の handoffId の snapshot
        assertIs<HandoffTaker.SnapshotDecision.Ignore>(HandoffTaker(nowMs = { now }).apply { request("A", handoffId) }.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText("11111111-1111-4111-8111-111111111111")), true))
        val decision = assertIs<HandoffTaker.SnapshotDecision.Adopt>(taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), true))
        assertEquals(handoffId, decision.snapshot.handoffId)
        assertIs<HandoffTaker.State.Adopting>(taker.state)
        taker.onAdopted(handoffId)
        assertIs<HandoffTaker.State.Adopted>(taker.state)
    }

    @Test
    fun takerKeepsAdoptingPastDeadlineAndTracksIssuedRequests() {
        val taker = HandoffTaker(nowMs = { now })
        taker.request("A", handoffId)
        assertIs<HandoffTaker.SnapshotDecision.Adopt>(taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), true))
        // 取り込みの途中で期限が来ても取り下げない（Cancel を送ると A が再開して二重に鳴る）
        now += HandoffTaker.DEFAULT_TIMEOUT_MS + 1
        assertNull(taker.expire())
        assertFalse(taker.request("A", "another"), "取り込み中に次の要求を受けた")
        assertFalse(taker.onCancel("A", HandoffBody.Cancel(handoffId, "no_queue")), "取り込み中に Cancel で取り下げた")
        assertTrue(taker.busy)
        taker.onAdoptFailed(handoffId)
        assertEquals(HandoffTaker.State.Idle, taker.state)
        assertFalse(taker.busy)
        // 取り込まなかった要求は、次にその相手に出す要求に載せる（相手が一時停止したままなら取り下げさせる）
        assertEquals(handoffId, taker.abandonedFor("A"))
        assertNull(taker.abandonedFor("C"))
    }

    @Test
    fun abandonedRequestIsRememberedOnTimeoutButNotAfterAdoption() {
        val taker = HandoffTaker(nowMs = { now })
        taker.request("A", handoffId)
        now += HandoffTaker.DEFAULT_TIMEOUT_MS + 1
        assertEquals(handoffId, taker.expire()?.handoffId)
        assertEquals(handoffId, taker.abandonedFor("A"))
        val second = "11111111-1111-4111-8111-111111111111"
        taker.request("A", second)
        assertIs<HandoffTaker.SnapshotDecision.Adopt>(taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText(second)), true))
        taker.onAdopted(second)
        assertEquals(second, taker.currentHandoffId)
        // 取り込んだ要求は「取り込まなかった」にしない。直前に取り込まなかった要求のまま
        assertEquals(handoffId, taker.abandonedFor("A"))
    }

    @Test
    fun undeliverableOnlyMatchesTheWaitingRequest() {
        val taker = HandoffTaker(nowMs = { now })
        taker.request("A", handoffId)
        assertFalse(taker.onUndeliverable("C", handoffId), "別の端末への送信の失敗で取り下げた")
        assertFalse(taker.onUndeliverable("A", null), "handoffId の無い送信（解除など）の失敗で取り下げた")
        assertTrue(taker.onUndeliverable("A", handoffId))
        val giver = HandoffGiver(nowMs = { now })
        assertEquals(HandoffGiver.RequestDecision.Capture, giver.onRequest("B", HandoffBody.Request(handoffId, "x"), canGive = true))
        assertFalse(giver.onUndeliverable("B", "other"))
        assertTrue(giver.onUndeliverable("B", handoffId))
        assertFalse(giver.holdingConnection)
    }

    @Test
    fun takerStopsWaitingOnlyForMatchingRefusal() {
        val taker = HandoffTaker(nowMs = { now })
        assertFalse(taker.onCancel("A", HandoffBody.Cancel(handoffId, "no_queue")), "待っていないのに取り下げた")
        taker.request("A", handoffId)
        assertFalse(taker.onCancel("C", HandoffBody.Cancel(handoffId, "no_queue")), "別の端末の Cancel で取り下げた")
        assertFalse(taker.onCancel("A", HandoffBody.Cancel("other", "no_queue")), "別の handoffId の Cancel で取り下げた")
        assertIs<HandoffTaker.State.Requested>(taker.state)
        assertTrue(taker.onCancel("A", HandoffBody.Cancel(handoffId, "no_queue")))
        assertEquals(HandoffTaker.State.Idle, taker.state)
        // 取り下げた後に遅れて届いた snapshot は受けない
        assertIs<HandoffTaker.SnapshotDecision.Ignore>(taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), true))
    }

    @Test
    fun takerRejectsAnotherAccountAndLateSnapshots() {
        val taker = HandoffTaker(nowMs = { now })
        taker.request("A", handoffId)
        assertEquals(HandoffTaker.SnapshotDecision.Ignore("account_mismatch", sendCancel = true), taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), false))
        taker.request("A", handoffId)
        now += HandoffTaker.DEFAULT_TIMEOUT_MS + 1
        assertEquals(HandoffTaker.SnapshotDecision.Ignore("expired", sendCancel = true), taker.onSnapshot("A", HandoffBody.Snapshot("h", snapshotText()), true))
        taker.request("A", handoffId)
        now += HandoffTaker.DEFAULT_TIMEOUT_MS + 1
        assertEquals(handoffId, taker.expire()?.handoffId)
        assertNull(taker.expire())
    }

    @Test
    fun giverIsIdempotentAndResumesOnlyOnExplicitCancel() {
        val giver = HandoffGiver(nowMs = { now })
        val request = HandoffBody.Request(handoffId, "hmac")
        assertEquals(HandoffGiver.RequestDecision.Capture, giver.onRequest("B", request, canGive = true))
        assertEquals(HandoffGiver.RequestDecision.Duplicate, giver.onRequest("B", request, canGive = true))
        assertTrue(giver.holdingConnection)
        // 別の端末・別の handoffId の Ack や Cancel は無視
        assertFalse(giver.onAck("C", HandoffBody.Ack(handoffId)))
        assertFalse(giver.onCancel("B", HandoffBody.Cancel("other", "x")))
        assertTrue(giver.onAck("B", HandoffBody.Ack(handoffId)))
        // Ack の後の Cancel は無視（Ack が終端）
        assertFalse(giver.onCancel("B", HandoffBody.Cancel(handoffId, "late")))
    }

    @Test
    fun giverRefusesWhenItCannotGiveAndExpiresTheWindow() {
        val giver = HandoffGiver(nowMs = { now })
        assertEquals(HandoffGiver.RequestDecision.Refuse, giver.onRequest("B", HandoffBody.Request("id-1", "h"), canGive = false))
        assertEquals(HandoffGiver.RequestDecision.Capture, giver.onRequest("B", HandoffBody.Request("id-2", "h"), canGive = true))
        assertFalse(giver.expire())
        now += HandoffGiver.DEFAULT_WINDOW_MS + 1
        assertTrue(giver.expire())
        assertFalse(giver.holdingConnection)
    }

    @Test
    fun bodiesRoundTrip() {
        val bodies = listOf(HandoffBody.Request(handoffId, "h"), HandoffBody.Snapshot("h", "s"), HandoffBody.Ack(handoffId), HandoffBody.Cancel(handoffId, "r"), HandoffBody.Unpair)
        bodies.forEach { assertEquals(it, HandoffBody.decode(HandoffBody.encode(it))) }
        assertNull(HandoffBody.decode("{\"kind\":\"unknown\"}"))
    }
}
