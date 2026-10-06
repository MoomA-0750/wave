package xyz.mooma0750.wave.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.AddToPlaylistResult
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.PlaylistSummary
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.core.session.WriteResult

/**
 * プレイリストの追加・削除・作成（F1e）。画面を閉じても続くよう、アプリのスコープで走らせ、結果はトーストで知らせる。
 * 同じ操作が進行中なら受け付けない（押し直しで重複させない）。自動で送り直さない。
 */
class PlaylistEditor(
    private val context: Context,
    private val music: MusicRepository,
    private val scope: CoroutineScope,
    /** プレイリストやライブラリの表示を読み直させる。 */
    private val onChanged: () -> Unit,
) {
    private val inFlight = mutableSetOf<String>()

    fun add(
        playlist: PlaylistSummary,
        song: Song,
    ) = run("add:${playlist.playlistId}:${song.videoId}") { generation ->
        val result = music.addToPlaylist(playlist.playlistId, song.videoId, generation)
        Log.i(WaveApplication.LOG_TAG, "playlist add result=${result::class.simpleName}")
        if (result == AddToPlaylistResult.Added || result == AddToPlaylistResult.Unknown) onChanged()
        when (result) {
            AddToPlaylistResult.Added -> context.getString(R.string.library_added_to_playlist, playlist.title)
            AddToPlaylistResult.AlreadyInPlaylist -> context.getString(R.string.library_already_in_playlist, playlist.title)
            is AddToPlaylistResult.NotChecked -> context.getString(R.string.library_add_not_checked)
            is AddToPlaylistResult.Failed -> context.getString(R.string.library_add_failed)
            AddToPlaylistResult.Unknown -> context.getString(R.string.library_add_unknown)
        }
    }

    fun remove(
        playlistId: String,
        song: Song,
        setVideoId: String,
    ) = run("remove:$playlistId:$setVideoId") { generation ->
        val result = music.removeFromPlaylist(playlistId, song.videoId, setVideoId, generation)
        Log.i(WaveApplication.LOG_TAG, "playlist remove result=${result::class.simpleName}")
        if (result !is WriteResult.Failed) onChanged()
        when (result) {
            is WriteResult.Done -> context.getString(R.string.library_removed_from_playlist)
            is WriteResult.Failed -> context.getString(R.string.library_remove_failed)
            WriteResult.Unknown -> context.getString(R.string.library_remove_unknown)
        }
    }

    /** 作って、song があればそこに追加する。 */
    fun create(
        title: String,
        song: Song?,
    ) = run("create") { generation ->
        val result = music.createPlaylist(title, generation)
        Log.i(WaveApplication.LOG_TAG, "playlist create result=${result::class.simpleName}")
        if (result !is WriteResult.Failed) onChanged()
        when (result) {
            is WriteResult.Done ->
                if (song == null) {
                    context.getString(R.string.library_playlist_created, title)
                } else {
                    val added = music.addToPlaylist(result.value, song.videoId, generation, knownEmpty = true)
                    Log.i(WaveApplication.LOG_TAG, "playlist add result=${added::class.simpleName}")
                    onChanged()
                    when (added) {
                        AddToPlaylistResult.Added -> context.getString(R.string.library_playlist_created_and_added, title)
                        AddToPlaylistResult.Unknown -> context.getString(R.string.library_playlist_created_add_unknown, title)
                        else -> context.getString(R.string.library_playlist_created_add_failed, title)
                    }
                }
            is WriteResult.Failed -> context.getString(R.string.library_create_playlist_failed)
            WriteResult.Unknown -> context.getString(R.string.library_create_playlist_unknown)
        }
    }

    private fun run(
        key: String,
        block: suspend (generation: Long) -> String,
    ): Boolean {
        synchronized(inFlight) { if (!inFlight.add(key)) return false }
        // 操作を選んだときのログインに結び付ける（途中でアカウントが変われば送らない）
        val generation = music.currentLoginGeneration()
        scope.launch {
            try {
                val message = block(generation)
                xyz.mooma0750.wave.ui.Notices.show(message)
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
        return true
    }
}
