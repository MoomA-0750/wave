package xyz.mooma0750.wave.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipGuardTest {
    @Test
    fun stopsAfterTheLimitEvenIfSkipsCauseTransitions() {
        val guard = SkipGuard(maxConsecutiveSkips = 3)
        // 飛ばすためのシークで曲の遷移が起きても、数えは戻さない
        val results = List(5) { guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = it * 1_000L) }
        assertEquals(listOf(true, true, true, false, false), results)
    }

    @Test
    fun neverSkipsFailuresThatHappenForEverySong() {
        val guard = SkipGuard()
        assertFalse(guard.onError(PlaybackFailure.Everywhere, hasNext = true, nowMs = 0))
        assertFalse(guard.onError(PlaybackFailure.Transient, hasNext = true, nowMs = 0))
        assertFalse(guard.onError(PlaybackFailure.ThisSong, hasNext = false, nowMs = 0))
    }

    @Test
    fun briefPlaybackBeforeEachRejectionDoesNotResetTheCount() {
        val guard = SkipGuard(maxConsecutiveSkips = 3)
        // 各曲が少しだけ再生されてから拒否される
        val results =
            List(5) { i ->
                val t = i * 10_000L
                guard.onPlaying(t)
                guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = t + 2_000)
            }
        assertEquals(listOf(true, true, true, false, false), results)
    }

    @Test
    fun resetsAfterRealPlaybackOrUserSelection() {
        val guard = SkipGuard(maxConsecutiveSkips = 1, maxSkipsInWindow = 100)
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = 0))
        assertFalse(guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = 1_000))
        guard.onPlaying(2_000)
        guard.onNotPlaying(20_000)
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = 21_000))
        guard.onUserSelection()
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = 22_000))
    }

    @Test
    fun limitsSkipsInATimeWindowEvenWithPlaybackBetween() {
        val guard = SkipGuard(maxConsecutiveSkips = 3, maxSkipsInWindow = 6, windowMs = 600_000)
        var t = 0L
        val results =
            List(8) {
                guard.onPlaying(t)
                t += 15_000 // 毎回15秒は再生できる
                guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = t).also { t += 1_000 }
            }
        assertEquals(listOf(true, true, true, true, true, true, false, false), results)
        // 窓の外に出たらまた飛ばせる
        guard.onPlaying(t)
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true, nowMs = t + 700_000))
    }
}
