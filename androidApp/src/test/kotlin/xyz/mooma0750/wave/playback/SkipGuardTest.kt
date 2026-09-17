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
        val results = List(5) { guard.onError(PlaybackFailure.ThisSong, hasNext = true) }
        assertEquals(listOf(true, true, true, false, false), results)
    }

    @Test
    fun neverSkipsFailuresThatHappenForEverySong() {
        val guard = SkipGuard()
        assertFalse(guard.onError(PlaybackFailure.Everywhere, hasNext = true))
        assertFalse(guard.onError(PlaybackFailure.Transient, hasNext = true))
        assertFalse(guard.onError(PlaybackFailure.ThisSong, hasNext = false))
    }

    @Test
    fun resetsWhenPlaybackStartsOrUserSelects() {
        val guard = SkipGuard(maxConsecutiveSkips = 1)
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true))
        assertFalse(guard.onError(PlaybackFailure.ThisSong, hasNext = true))
        guard.onPlaying()
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true))
        guard.onUserSelection()
        assertTrue(guard.onError(PlaybackFailure.ThisSong, hasNext = true))
    }
}
