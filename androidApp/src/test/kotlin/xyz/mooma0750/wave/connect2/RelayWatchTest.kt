package xyz.mooma0750.wave.connect2

import org.junit.Test
import kotlin.test.assertEquals

class RelayWatchTest {
    @Test
    fun aSocketWithoutWelcomeIsGivenUpAfterFifteenSeconds() {
        val watch = RelayWatch()
        watch.opened(0)
        assertEquals(RelayWatch.Action.None, watch.tick(14_999))
        // challenge だけ届いても welcome でなければ待ち続けない
        watch.received(5_000)
        assertEquals(RelayWatch.Action.Dead, watch.tick(15_000))
    }

    @Test
    fun anIdleSocketIsProbedAndDroppedWhenNothingComesBack() {
        val watch = RelayWatch()
        watch.opened(0)
        watch.received(1_000, welcome = true)
        assertEquals(RelayWatch.Action.None, watch.tick(20_999))
        assertEquals(RelayWatch.Action.Probe, watch.tick(21_000))
        // 確かめは1回だけ送る
        assertEquals(RelayWatch.Action.None, watch.tick(28_999))
        assertEquals(RelayWatch.Action.Dead, watch.tick(29_000))
    }

    @Test
    fun anyReplyKeepsTheSocket() {
        val watch = RelayWatch()
        watch.opened(0)
        watch.received(0, welcome = true)
        assertEquals(RelayWatch.Action.Probe, watch.tick(20_000))
        watch.received(20_300)
        assertEquals(RelayWatch.Action.None, watch.tick(29_000))
        assertEquals(RelayWatch.Action.Probe, watch.tick(40_300))
    }

    @Test
    fun wakingProbesAtOnceOnlyWhenConnected() {
        val watch = RelayWatch()
        watch.opened(0)
        assertEquals(RelayWatch.Action.None, watch.wake(100), "welcome の前は welcome の期限に任せる")
        watch.received(200, welcome = true)
        assertEquals(RelayWatch.Action.Probe, watch.wake(300))
        assertEquals(RelayWatch.Action.None, watch.wake(400), "確かめの返事を待っている間は重ねない")
        assertEquals(RelayWatch.Action.Dead, watch.tick(8_300))
    }

    @Test
    fun reconnectWaitsStartAtOneSecondAndStopAtThirty() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map(RelayWatch::reconnectDelayMs))
    }
}
