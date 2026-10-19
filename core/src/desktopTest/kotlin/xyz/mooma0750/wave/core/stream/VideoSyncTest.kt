package xyz.mooma0750.wave.core.stream

import xyz.mooma0750.wave.core.stream.VideoSync.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoSyncTest {
    @Test
    fun hidesWhereTheVideoHasNoMatchingPart() {
        assertEquals(Step.Hide, VideoSync.step(null, 0, playing = true, videoReady = true))
    }

    @Test
    fun pausedMatchesThePositionWithoutPlaying() {
        assertEquals(Step.Seek(10_000, play = false), VideoSync.step(10_000, 5_000, playing = false, videoReady = true))
        assertEquals(Step.Run(1f, play = false), VideoSync.step(10_000, 10_050, playing = false, videoReady = true))
    }

    @Test
    fun farAwayJumpsButNotWhileStillLoading() {
        assertEquals(Step.Seek(60_000, play = true), VideoSync.step(60_000, 10_000, playing = true, videoReady = true))
        assertEquals(Step.Run(1f, play = true), VideoSync.step(60_000, 10_000, playing = true, videoReady = false))
    }

    @Test
    fun smallDriftIsClosedBySpeedWithinLimits() {
        assertEquals(Step.Run(1f, play = true), VideoSync.step(10_000, 10_030, playing = true, videoReady = true))
        assertEquals(Step.Run(0.9f, play = true), VideoSync.step(10_000, 10_100, playing = true, videoReady = true))
        assertEquals(Step.Run(1.05f, play = true), VideoSync.step(10_000, 9_950, playing = true, videoReady = true))
        assertEquals(Step.Run(1.1f, play = true), VideoSync.step(10_000, 9_400, playing = true, videoReady = true))
    }
}
