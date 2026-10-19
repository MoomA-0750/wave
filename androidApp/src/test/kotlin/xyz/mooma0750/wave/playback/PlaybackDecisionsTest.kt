package xyz.mooma0750.wave.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Test
import xyz.mooma0750.wave.core.stream.StreamFailure
import xyz.mooma0750.wave.handoff.HandoffRepeat
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 再生サービスの判断（Service から切り出す前と同じ表）。 */
class PlaybackDecisionsTest {
    @Test
    fun everyResolveFailureIsClassifiedAsBefore() {
        val expected =
            mapOf(
                StreamFailure.Unplayable to PlaybackFailure.ThisSong,
                StreamFailure.NoAudioFormat to PlaybackFailure.ThisSong,
                StreamFailure.ItagUnavailable to PlaybackFailure.ThisSong,
                StreamFailure.SongLoginRequired to PlaybackFailure.ThisSong,
                StreamFailure.StreamChanged to PlaybackFailure.ThisSong,
                StreamFailure.LiveStream to PlaybackFailure.ThisSong,
                StreamFailure.Network to PlaybackFailure.Transient,
                StreamFailure.Http to PlaybackFailure.Transient,
                StreamFailure.PlayerConfigUnavailable to PlaybackFailure.Transient,
                StreamFailure.NotLoggedIn to PlaybackFailure.Everywhere,
                StreamFailure.Expired to PlaybackFailure.Everywhere,
                StreamFailure.Suspect to PlaybackFailure.Everywhere,
                StreamFailure.Parse to PlaybackFailure.Everywhere,
                StreamFailure.SabrOnly to PlaybackFailure.Everywhere,
                StreamFailure.DecipherFailed to PlaybackFailure.Everywhere,
                StreamFailure.RejectedUrl to PlaybackFailure.Everywhere,
            )
        // 新しい失敗の種類を足したら、ここに分類を足すこと
        assertEquals(StreamFailure.entries.toSet(), expected.keys)
        for ((reason, failure) in expected) {
            assertEquals(failure, PlaybackDecisions.failureOf(StreamResolveException(reason), PlaybackException.ERROR_CODE_IO_UNSPECIFIED), "reason=$reason")
        }
    }

    @Test
    fun otherFailuresUseTheErrorCode() {
        assertEquals(PlaybackFailure.ThisSong, PlaybackDecisions.failureOf(StreamRejectedException(403), PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(PlaybackFailure.ThisSong, PlaybackDecisions.failureOf(null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PlaybackFailure.ThisSong, PlaybackDecisions.failureOf(null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED))
        assertEquals(PlaybackFailure.ThisSong, PlaybackDecisions.failureOf(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackFailure.ThisSong, PlaybackDecisions.failureOf(null, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertEquals(PlaybackFailure.Transient, PlaybackDecisions.failureOf(IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackFailure.Transient, PlaybackDecisions.failureOf(null, PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun retryDelays() {
        assertEquals(C.TIME_UNSET, PlaybackDecisions.retryDelayFor(StreamRejectedException(403), 1))
        assertEquals(C.TIME_UNSET, PlaybackDecisions.retryDelayFor(StreamResolveException(StreamFailure.Unplayable), 1))
        assertEquals(5_000L, PlaybackDecisions.retryDelayFor(StreamResolveException(StreamFailure.Http), 1))
        assertEquals(15_000L, PlaybackDecisions.retryDelayFor(StreamResolveException(StreamFailure.Network), 3))
        assertNull(PlaybackDecisions.retryDelayFor(IOException(), 1))
        assertNull(PlaybackDecisions.retryDelayFor(null, 1))
    }

    @Test
    fun repeatModesMapBothWays() {
        for (repeat in HandoffRepeat.entries) assertEquals(repeat, PlaybackDecisions.repeatOf(PlaybackDecisions.repeatModeOf(repeat)))
        assertEquals(Player.REPEAT_MODE_ONE, PlaybackDecisions.repeatModeOf(HandoffRepeat.One))
        assertEquals(HandoffRepeat.Off, PlaybackDecisions.repeatOf(99))
    }

    @Test
    fun onlyReadyOrBufferingWithPlayWhenReadyCountsAsPlaying() {
        assertTrue(PlaybackDecisions.isActivelyPlaying(true, Player.STATE_READY))
        assertTrue(PlaybackDecisions.isActivelyPlaying(true, Player.STATE_BUFFERING))
        assertFalse(PlaybackDecisions.isActivelyPlaying(true, Player.STATE_ENDED))
        assertFalse(PlaybackDecisions.isActivelyPlaying(true, Player.STATE_IDLE))
        assertFalse(PlaybackDecisions.isActivelyPlaying(false, Player.STATE_READY))
    }

    @Test
    fun aNewSelectionIsReportedOnlyWhenHandled() {
        fun selection(
            handled: Boolean = true,
            userSelection: Boolean = false,
            wasEmpty: Boolean = false,
            wasPlaying: Boolean = true,
            countAfter: Int = 3,
            playingAfter: Boolean = true,
        ) = PlaybackDecisions.startedNewSelection(handled, userSelection, wasEmpty, wasPlaying, countAfter, playingAfter)
        // 曲を選んだ
        assertTrue(selection(userSelection = true))
        assertFalse(selection(handled = false, userSelection = true))
        // 鳴っているキューに足しただけ
        assertFalse(selection())
        // 空のキュー・止まっていたキューに足して鳴り出した（再レビュー 低-O）
        assertTrue(selection(wasEmpty = true))
        assertTrue(selection(wasPlaying = false))
        // 足しても鳴っていない・何も入っていない
        assertFalse(selection(wasPlaying = false, playingAfter = false))
        assertFalse(selection(wasEmpty = true, countAfter = 0))
    }
}
