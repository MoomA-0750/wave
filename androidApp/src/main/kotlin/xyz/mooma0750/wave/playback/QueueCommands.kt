package xyz.mooma0750.wave.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    val all = listOf(PLAY_SONG, PLAY_FROM, ADD_NEXT, ADD_TO_END, REMOVE, JUMP).map { SessionCommand(it, Bundle.EMPTY) }

    private const val KEY_SONG = "song"
    private const val KEY_PLAY_FROM = "playFrom"
    private const val KEY_ITEM_ID = "itemId"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class PlayFrom(
        val songs: List<Song>,
        val startIndex: Int,
        val playlistId: String?,
        val continuation: String?,
    )

    fun songArgs(song: Song): Bundle = Bundle().apply { putString(KEY_SONG, json.encodeToString(Song.serializer(), song)) }

    fun songOf(args: Bundle): Song? = args.getString(KEY_SONG)?.let { runCatching { json.decodeFromString(Song.serializer(), it) }.getOrNull() }

    fun playFromArgs(playFrom: PlayFrom): Bundle = Bundle().apply { putString(KEY_PLAY_FROM, json.encodeToString(PlayFrom.serializer(), playFrom)) }

    fun playFromOf(args: Bundle): PlayFrom? = args.getString(KEY_PLAY_FROM)?.let { runCatching { json.decodeFromString(PlayFrom.serializer(), it) }.getOrNull() }

    fun itemArgs(itemId: String): Bundle = Bundle().apply { putString(KEY_ITEM_ID, itemId) }

    fun itemIdOf(args: Bundle): String? = args.getString(KEY_ITEM_ID)
}
