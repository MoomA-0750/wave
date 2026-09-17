package xyz.mooma0750.wave.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenCounterTest {
    @Test
    fun accumulatesOnlyWhilePlayingAcrossPauses() {
        val counter = ListenCounter(30_000)
        assertEquals(30_000L, counter.start(1_000))
        counter.stop(11_000) // 10秒聴いた
        // 一時停止中の時間は数えない
        assertEquals(20_000L, counter.start(100_000))
        assertEquals(15_000L, counter.listenedMs(105_000))
    }

    @Test
    fun startingTwiceDoesNotRestartTheWait() {
        val counter = ListenCounter(30_000)
        counter.start(0)
        assertNull(counter.start(5_000))
    }

    @Test
    fun stopWithoutStartAndClockGoingBackAreHarmless() {
        val counter = ListenCounter(30_000)
        counter.stop(10)
        counter.start(100)
        counter.stop(50)
        assertEquals(0L, counter.listenedMs(50))
    }

    @Test
    fun thresholdIsHalfForShortSongsButAtLeastFiveSeconds() {
        assertEquals(30_000L, ListenCounter.thresholdFor(null))
        assertEquals(30_000L, ListenCounter.thresholdFor(240_000))
        assertEquals(30_000L, ListenCounter.thresholdFor(30_000))
        assertEquals(10_000L, ListenCounter.thresholdFor(20_000))
        assertEquals(5_000L, ListenCounter.thresholdFor(6_000))
        assertEquals(30_000L, ListenCounter.thresholdFor(0))
    }
}
