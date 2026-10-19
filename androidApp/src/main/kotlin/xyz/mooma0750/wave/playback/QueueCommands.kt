package xyz.mooma0750.wave.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.serialization.Serializable
import xyz.mooma0750.wave.core.music.Song

/**
 * UI から PlaybackService へのキューの操作。キューを置き換える入口はここだけにする（世代を進める場所を1つにするため）。
 * このアプリ自身のコントローラーにだけ許可する。
 */
object QueueCommands {
    const val PLAY_SONG = "xyz.mooma0750.wave.PLAY_SONG"
    const val PLAY_FROM = "xyz.mooma0750.wave.PLAY_FROM"
    const val ADD_NEXT = "xyz.mooma0750.wave.ADD_NEXT"
    const val ADD_TO_END = "xyz.mooma0750.wave.ADD_TO_END"
    const val REMOVE = "xyz.mooma0750.wave.REMOVE"
    const val JUMP = "xyz.mooma0750.wave.JUMP"
    const val MOVE = "xyz.mooma0750.wave.MOVE"

    /** シャッフルの入り切り。入っているかはセッションの extras（KEY_SHUFFLED）で自アプリの画面に配る。 */
    const val TOGGLE_SHUFFLE = "xyz.mooma0750.wave.TOGGLE_SHUFFLE"

    /** 自分で足した曲（キュー）をまとめて外す（Spotify の「キューを消去」）。 */
    const val CLEAR_USER_QUEUE = "xyz.mooma0750.wave.CLEAR_USER_QUEUE"
    const val KEY_SHUFFLED = "queue.shuffled"

    /** 再生中の曲の評価を切り替える。自アプリの UI と、トークン付きの通知のボタンから受ける（決定 G-2）。 */
    const val TOGGLE_LIKE = "xyz.mooma0750.wave.TOGGLE_LIKE"
    const val KEY_VIDEO_ID = "videoId"

    /** 評価の目標（true = 高く評価、false = 外す）。Service の状態と食い違えば受け付けない。 */
    const val KEY_TARGET_LIKED = "targetLiked"
    const val KEY_NOTIFICATION_TOKEN = "notificationToken"

    /** ライブ配信の映像を出す・やめる（KEY_ON）。出すときだけ映像を見るための画質で読み直す。 */
    const val SET_LIVE_VIDEO = "xyz.mooma0750.wave.SET_LIVE_VIDEO"
    const val KEY_ON = "on"

    val all = listOf(PLAY_SONG, PLAY_FROM, ADD_NEXT, ADD_TO_END, REMOVE, JUMP, MOVE, TOGGLE_SHUFFLE, CLEAR_USER_QUEUE, TOGGLE_LIKE, SET_LIVE_VIDEO).map { SessionCommand(it, Bundle.EMPTY) }

    private const val KEY_SONG = "song"
    private const val KEY_SONGS = "songs"
    private const val KEY_PLAY_FROM = "playFrom"
    private const val KEY_ITEM_ID = "itemId"
    private const val KEY_TO_INDEX = "toIndex"
    private val json = SongJson.json

    @Serializable
    data class PlayFrom(
        val songs: List<Song>,
        val startIndex: Int,
        val playlistId: String?,
        val continuation: String?,
        val shuffle: Boolean = false,
    )

    fun songArgs(song: Song): Bundle = Bundle().apply { putString(KEY_SONG, SongJson.encode(song)) }

    fun songOf(args: Bundle): Song? = SongJson.decode(args.getString(KEY_SONG))

    /** アルバム・プレイリストを丸ごとキューに入れる。 */
    fun songsArgs(songs: List<Song>): Bundle =
        Bundle().apply { putString(KEY_SONGS, SongJson.encodeList(songs)) }

    fun songsOf(args: Bundle): List<Song>? =
        SongJson.decodeList(args.getString(KEY_SONGS))?.takeIf { it.isNotEmpty() }

    fun playFromArgs(playFrom: PlayFrom): Bundle = Bundle().apply { putString(KEY_PLAY_FROM, json.encodeToString(PlayFrom.serializer(), playFrom)) }

    fun playFromOf(args: Bundle): PlayFrom? = args.getString(KEY_PLAY_FROM)?.let { runCatching { json.decodeFromString(PlayFrom.serializer(), it) }.getOrNull() }

    fun itemArgs(itemId: String): Bundle = Bundle().apply { putString(KEY_ITEM_ID, itemId) }

    fun itemIdOf(args: Bundle): String? = args.getString(KEY_ITEM_ID)

    fun moveArgs(
        itemId: String,
        toIndex: Int,
    ): Bundle =
        Bundle().apply {
            putString(KEY_ITEM_ID, itemId)
            putInt(KEY_TO_INDEX, toIndex)
        }

    fun toIndexOf(args: Bundle): Int? = args.getInt(KEY_TO_INDEX, -1).takeIf { it >= 0 }
}
