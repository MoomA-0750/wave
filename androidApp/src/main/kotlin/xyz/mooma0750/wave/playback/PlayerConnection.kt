package xyz.mooma0750.wave.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mooma0750.wave.core.music.Song

/** UI から PlaybackService へつなぐ。MediaController はアプリで1つを使い回す。 */
class PlayerConnection(
    private val context: Context,
    private val token: String,
) {
    private var controller: MediaController? = null
    private val connecting = Mutex()
    private val mutableLikeState = kotlinx.coroutines.flow.MutableStateFlow(LikeState())

    /** 再生中の曲の評価（Service がセッションの extras で配る）。 */
    val likeState: kotlinx.coroutines.flow.StateFlow<LikeState> = mutableLikeState
    private val mutableShuffled = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** シャッフルが入っているか（Service がセッションの extras で配る）。 */
    val shuffled: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableShuffled

    /**
     * 同時に呼ばれても MediaController を1つだけ作る。作るのはメインスレッドで行う（MediaController は作ったスレッドでしか触れない。
     * 画面が無いまま Claude の道具や時計からの操作で呼ばれると、ほかのスレッドで作って落ちていた）。
     */
    suspend fun controller(): MediaController =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { connect() }

    private suspend fun connect(): MediaController =
        connecting.withLock {
            controller?.takeIf { it.isConnected }?.let { return@withLock it }
            controller?.release()
            MediaController
                .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setConnectionHints(android.os.Bundle().apply { putString(CONNECTION_TOKEN_KEY, token) })
                .setListener(
                    object : MediaController.Listener {
                        override fun onExtrasChanged(
                            controller: MediaController,
                            extras: android.os.Bundle,
                        ) {
                            mutableLikeState.value = LikeState.of(extras)
                            mutableShuffled.value = extras.getBoolean(QueueCommands.KEY_SHUFFLED)
                        }
                    },
                ).buildAsync()
                .await()
                .also {
                    controller = it
                    mutableLikeState.value = LikeState.of(it.sessionExtras)
                    mutableShuffled.value = it.sessionExtras.getBoolean(QueueCommands.KEY_SHUFFLED)
                }
        }

    companion object {
        const val CONNECTION_TOKEN_KEY = "xyz.mooma0750.wave.CONTROLLER_TOKEN"
    }

    suspend fun playSong(song: Song) = send(QueueCommands.PLAY_SONG, QueueCommands.songArgs(song))

    suspend fun playFrom(
        songs: List<Song>,
        startIndex: Int,
        playlistId: String?,
        continuation: String?,
        shuffle: Boolean = false,
    ) = send(QueueCommands.PLAY_FROM, QueueCommands.playFromArgs(QueueCommands.PlayFrom(songs, startIndex, playlistId, continuation, shuffle)))

    suspend fun addNext(song: Song) = send(QueueCommands.ADD_NEXT, QueueCommands.songArgs(song))

    suspend fun addToEnd(song: Song) = send(QueueCommands.ADD_TO_END, QueueCommands.songArgs(song))

    /** アルバム・プレイリストを丸ごと足す。 */
    suspend fun addNext(songs: List<Song>) = send(QueueCommands.ADD_NEXT, QueueCommands.songsArgs(songs))

    suspend fun addToEnd(songs: List<Song>) = send(QueueCommands.ADD_TO_END, QueueCommands.songsArgs(songs))

    suspend fun remove(itemId: String) = send(QueueCommands.REMOVE, QueueCommands.itemArgs(itemId))

    suspend fun jumpTo(itemId: String) = send(QueueCommands.JUMP, QueueCommands.itemArgs(itemId))

    /** キューを並べ替える（今の曲より後ろだけ）。 */
    suspend fun move(
        itemId: String,
        toIndex: Int,
    ) = send(QueueCommands.MOVE, QueueCommands.moveArgs(itemId, toIndex))

    /** 表示中の曲（videoId）の評価を切り替える。曲が変わっていたら Service が受け付けない。 */
    suspend fun setLiked(
        videoId: String,
        liked: Boolean,
    ) = send(
        QueueCommands.TOGGLE_LIKE,
        android.os.Bundle().apply {
            putString(QueueCommands.KEY_VIDEO_ID, videoId)
            putBoolean(QueueCommands.KEY_TARGET_LIKED, liked)
        },
    )

    /** 自分で足した曲をまとめて外す。 */
    suspend fun clearUserQueue() = send(QueueCommands.CLEAR_USER_QUEUE, android.os.Bundle.EMPTY)

    /** シャッフルの入り切り（切ると、残りの曲を入れる前の並びに戻す）。 */
    suspend fun toggleShuffle() = send(QueueCommands.TOGGLE_SHUFFLE, android.os.Bundle.EMPTY)

    /** オフ → 全体 → 1曲 → オフ。リピートは Player の標準の操作なので、カスタムコマンドにしない。 */
    suspend fun cycleRepeatMode() {
        val current = controller()
        current.repeatMode =
            when (current.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
            }
    }

    private suspend fun send(
        action: String,
        args: android.os.Bundle,
    ) {
        controller().sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args).await()
    }
}
