package xyz.mooma0750.wave.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import xyz.mooma0750.wave.core.stream.StreamFailure
import xyz.mooma0750.wave.handoff.HandoffRepeat

/** 再生サービスの判断のうち、Android の状態に依らないもの（そのままテストできる）。 */
internal object PlaybackDecisions {
    /** 再生の失敗の分類。cause は PlaybackException.cause、errorCode は PlaybackException.errorCode。 */
    fun failureOf(
        cause: Throwable?,
        errorCode: Int,
    ): PlaybackFailure =
        when {
            cause is StreamResolveException ->
                when {
                    cause.reason in THIS_SONG_REASONS -> PlaybackFailure.ThisSong
                    cause.transient -> PlaybackFailure.Transient
                    else -> PlaybackFailure.Everywhere
                }
            cause is StreamRejectedException -> PlaybackFailure.ThisSong
            errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure.ThisSong
            errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> PlaybackFailure.ThisSong
            else -> PlaybackFailure.Transient
        }

    /**
     * 読み込みの失敗を、どれだけ空けて読み直すか。null なら既定の扱い（DefaultLoadErrorHandlingPolicy）。
     * 拒否と、時間をおいても直らない解決の失敗は読み直さない（C.TIME_UNSET）。/player の失敗（429 など）はすぐに叩き直さない。
     */
    fun retryDelayFor(
        cause: Throwable?,
        errorCount: Int,
    ): Long? =
        when {
            cause is StreamRejectedException -> androidx.media3.common.C.TIME_UNSET
            cause is StreamResolveException && !cause.transient -> androidx.media3.common.C.TIME_UNSET
            cause is StreamResolveException -> 5_000L * errorCount
            else -> null
        }

    fun repeatModeOf(repeat: HandoffRepeat): Int =
        when (repeat) {
            HandoffRepeat.Off -> Player.REPEAT_MODE_OFF
            HandoffRepeat.One -> Player.REPEAT_MODE_ONE
            HandoffRepeat.All -> Player.REPEAT_MODE_ALL
        }

    /** 知らない値は Off。 */
    fun repeatOf(mode: Int): HandoffRepeat =
        when (mode) {
            Player.REPEAT_MODE_ONE -> HandoffRepeat.One
            Player.REPEAT_MODE_ALL -> HandoffRepeat.All
            else -> HandoffRepeat.Off
        }

    /** 実際に鳴っている（鳴らそうとしている）か。最後まで行った・エラーで止まったものは含めない（レビュー p6b34 中-2）。 */
    fun isActivelyPlaying(
        playWhenReady: Boolean,
        playbackState: Int,
    ): Boolean = playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    /**
     * キューの操作の後に「曲を選んだ（新しい再生）」と知らせるか（L-3、レビュー p6b34 高-1、再レビュー 低-O）。
     * 曲を選んだ（キューを置き換えた・飛んだ）か、空のキュー・止まっていたキューに足して再生が始まったとき。
     */
    fun startedNewSelection(
        handled: Boolean,
        userSelection: Boolean,
        wasEmpty: Boolean,
        wasPlaying: Boolean,
        itemCountAfter: Int,
        playWhenReadyAfter: Boolean,
    ): Boolean {
        val startedPlaying = (wasEmpty || !wasPlaying) && itemCountAfter > 0 && playWhenReadyAfter
        return handled && (userSelection || startedPlaying)
    }

    private val THIS_SONG_REASONS = setOf(StreamFailure.Unplayable, StreamFailure.NoAudioFormat, StreamFailure.ItagUnavailable, StreamFailure.StreamChanged, StreamFailure.LiveStream, StreamFailure.SongLoginRequired)
}
