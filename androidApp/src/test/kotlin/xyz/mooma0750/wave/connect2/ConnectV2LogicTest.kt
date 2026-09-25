package xyz.mooma0750.wave.connect2

import org.junit.Test
import xyz.mooma0750.wave.handoff.HandoffCodec
import xyz.mooma0750.wave.handoff.HandoffItem
import xyz.mooma0750.wave.handoff.HandoffOrigin
import xyz.mooma0750.wave.handoff.HandoffRepeat
import xyz.mooma0750.wave.handoff.HandoffSnapshot
import xyz.mooma0750.wave.handoff.v2.CursorBody
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectV2LogicTest {
    @Test
    fun accountTagIsFixed() {
        // 形が変わると、家の他の端末と別アカウント扱いになる（引き取れない・止まらない）
        assertEquals("Jek8hmMpqENRCGyp2wiF_FeBPnCBwrz92URWrqWmK8A", AccountTag.of("house-1", "account-1"))
        assertTrue(AccountTag.of("house-1", "account-1") != AccountTag.of("house-2", "account-1"))
    }

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun item(n: Int) = HandoffItem(uuid(n), "video%04d__".format(n).take(11), "曲$n", emptyList(), null, 200, null, HandoffOrigin.Source)

    private fun snapshot(count: Int) =
        HandoffSnapshot(
            schemaVersion = HandoffCodec.SCHEMA_VERSION,
            handoffId = uuid(999_999),
            items = (0 until count).map(::item),
            currentItemId = uuid(0),
            positionMs = 0,
            playing = false,
            repeat = HandoffRepeat.Off,
            source = null,
            continuation = "more",
            radioPlaylistId = null,
            exhausted = true,
            listenedMs = 0,
            historySent = false,
            capturedAtEpochMs = 0,
        )

    private fun cursor(
        itemId: String,
        repeat: HandoffRepeat = HandoffRepeat.All,
    ) = CursorBody(
        queueRef = "q",
        currentItemId = itemId,
        positionMs = 0,
        playing = true,
        repeat = repeat,
        sessionBase = 0,
        sessionStartVersion = null,
        deviceSeq = 1,
        listenedMs = 30_000,
        historySent = true,
        accountTag = "t",
        startedAtEpochMs = 0,
    )

    @Test
    fun theQueueMovesToTheCursorsSongAndSettings() {
        val moved = assertNotNull(RemoteQueue.atCursor(snapshot(5), cursor(uuid(3)), 60_000))
        assertEquals(uuid(3), moved.currentItemId)
        assertEquals(60_000, moved.positionMs)
        assertEquals(HandoffRepeat.All, moved.repeat)
        assertEquals(30_000, moved.listenedMs)
        assertTrue(moved.historySent)
        // 後ろを切っていなければ、続きと尽きた印はそのまま
        assertEquals("more", moved.continuation)
        assertTrue(moved.exhausted)
    }

    @Test
    fun anUnknownSongIsNotAdoptedAndPositionsAreClamped() {
        assertNull(RemoteQueue.atCursor(snapshot(5), cursor(uuid(77)), 0))
        assertEquals(0, assertNotNull(RemoteQueue.atCursor(snapshot(5), cursor(uuid(1)), -5)).positionMs)
        // 曲の長さ（200秒）を超える位置は切り詰める
        assertEquals(200_000, assertNotNull(RemoteQueue.atCursor(snapshot(5), cursor(uuid(1)), 999_000)).positionMs)
    }

    @Test
    fun cuttingTheTailDropsTheContinuation() {
        // 今の曲の後ろに300件を超えてあると後ろを切る。続きは読み込み済みの末尾を指さないので捨てる（再レビュー 低-G）
        val moved = assertNotNull(RemoteQueue.atCursor(snapshot(320), cursor(uuid(0)), 0))
        assertEquals(301, moved.items.size)
        assertNull(moved.continuation)
        assertFalse(moved.exhausted)
        // 前は20件まで
        assertEquals(21, assertNotNull(RemoteQueue.atCursor(snapshot(40), cursor(uuid(30)), 0)).items.indexOfFirst { it.itemId == uuid(30) } + 1)
    }

    @Test
    fun decodingRoundTripsAndRejectsGarbage() {
        val snapshot = snapshot(3)
        assertEquals(snapshot.items.map { it.itemId }, assertNotNull(RemoteQueue.decode(HandoffCodec.encode(snapshot))).items.map { it.itemId })
        assertNull(RemoteQueue.decode("not json"))
        assertNull(RemoteQueue.decode("{}"))
    }
}
