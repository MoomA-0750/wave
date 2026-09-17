package xyz.mooma0750.wave.playback

import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.LikeStatus
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.core.session.WriteResult

/** 再生中の曲の評価。Service が持ち、画面と通知へ配る。 */
data class LikeState(
    val videoId: String? = null,
    /** 読めていなければ null。 */
    val status: LikeStatus? = null,
    val pending: Boolean = false,
    /** 直前の操作の結果を知らせる文言。 */
    val message: String? = null,
    /** 同じ文言が続いても知らせ直すための番号。 */
    val messageId: Int = 0,
) {
    fun toBundle(): Bundle =
        Bundle().apply {
            putString(KEY_VIDEO_ID, videoId)
            putString(KEY_STATUS, status?.name)
            putBoolean(KEY_PENDING, pending)
            putString(KEY_MESSAGE, message)
            putInt(KEY_MESSAGE_ID, messageId)
        }

    companion object {
        private const val KEY_VIDEO_ID = "like.videoId"
        private const val KEY_STATUS = "like.status"
        private const val KEY_PENDING = "like.pending"
        private const val KEY_MESSAGE = "like.message"
        private const val KEY_MESSAGE_ID = "like.messageId"

        fun of(extras: Bundle?): LikeState =
            if (extras == null) {
                LikeState()
            } else {
                LikeState(
                    videoId = extras.getString(KEY_VIDEO_ID),
                    status = extras.getString(KEY_STATUS)?.let { name -> LikeStatus.entries.firstOrNull { it.name == name } },
                    pending = extras.getBoolean(KEY_PENDING),
                    message = extras.getString(KEY_MESSAGE),
                    messageId = extras.getInt(KEY_MESSAGE_ID),
                )
            }
    }
}

/**
 * 再生中の曲の評価を読み、切り替える（F1d）。書き込みの規則は WaveSession.callWrite:
 * 自動で送り直さず、楽観的に表示を変えず、分からないときは読み直す。メインスレッドだけから使う。
 */
class LikeController(
    private val music: MusicRepository,
    private val scope: CoroutineScope,
    private val onChanged: (LikeState) -> Unit,
    /** 評価が変わった（かもしれない）とき。ライブラリの「高く評価した曲」を読み直させる。 */
    private val onLikedSongsChanged: () -> Unit,
) {
    var state = LikeState()
        private set
    private var loadJob: Job? = null
    private var epoch = 0

    fun onCurrentSong(videoId: String?) {
        if (videoId == state.videoId) return
        loadJob?.cancel()
        epoch++
        publish(LikeState(videoId = videoId, messageId = state.messageId))
        if (videoId != null) load(videoId)
    }

    /** ログアウト・アカウントの切り替え。書き込み中の結果も捨てる。 */
    fun reset() {
        loadJob?.cancel()
        epoch++
        publish(LikeState(messageId = state.messageId))
    }

    /**
     * 表示中の曲（expectedVideoId）を、評価の目標（targetLiked）にする。評価を読めていなければ読み直す。
     * 目標が今の状態と同じ（押した側の表示が古い）なら書き込まずに状態を配り直す。受け付けなければ false。
     */
    fun setLiked(
        expectedVideoId: String?,
        targetLiked: Boolean?,
    ): Boolean {
        val current = state
        val videoId = current.videoId ?: return false
        if (expectedVideoId != videoId || current.pending) return false
        if (current.status == null) {
            if (loadJob?.isActive != true) load(videoId)
            return true
        }
        val liked = targetLiked ?: return false
        if (liked == (current.status == LikeStatus.Like)) {
            publish(current)
            return false
        }
        val startedEpoch = epoch
        // 始めたときのログインで送る（途中でアカウントが変われば送らない）
        val generation = music.currentLoginGeneration()
        publish(current.copy(pending = true, message = null))
        scope.launch {
            val result = music.setLiked(videoId, liked, generation)
            Log.i(WaveApplication.LOG_TAG, "like write result=${result::class.simpleName} liked=$liked")
            if (result !is WriteResult.Failed) onLikedSongsChanged()
            // 曲が変わった・ログアウトしたなら、表示には反映しない。別の曲を挟んで同じ曲に戻っていたら、古い評価を読んでいるかもしれないので読み直す
            if (startedEpoch != epoch || state.videoId != videoId) {
                if (result !is WriteResult.Failed && state.videoId == videoId) load(videoId)
                return@launch
            }
            when (result) {
                is WriteResult.Done -> publish(state.copy(status = if (liked) LikeStatus.Like else LikeStatus.Indifferent, pending = false))
                is WriteResult.Failed -> publish(state.copy(pending = false, message = "評価を変更できませんでした", messageId = state.messageId + 1))
                WriteResult.Unknown -> {
                    publish(state.copy(status = null, pending = false, message = "評価を変更できたか確認できませんでした", messageId = state.messageId + 1))
                    load(videoId)
                }
            }
        }
        return true
    }

    private fun load(videoId: String) {
        val startedEpoch = epoch
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val result = music.likeStatus(videoId)
                if (startedEpoch != epoch || state.videoId != videoId) return@launch
                when (result) {
                    is CallResult.Ok -> publish(state.copy(status = result.value, message = null))
                    is CallResult.Failed -> {
                        Log.i(WaveApplication.LOG_TAG, "like status failed ${result.kind}")
                        publish(state.copy(message = "評価を読み込めませんでした（押すと読み直します）", messageId = state.messageId + 1))
                    }
                }
            }
    }

    private fun publish(next: LikeState) {
        state = next
        onChanged(next)
    }
}
