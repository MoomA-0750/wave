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
    /** 「どのプレイリストに入っているか」の覚え。変えたプレイリストの分だけ直す。 */
    private val membership: PlaylistMembership? = null,
) {
    private val inFlight = mutableSetOf<String>()

    /** @param onResult 結果（追加の画面が、閉じずに「入っている」の印を出すのに使う）。メインスレッドとは限らない。 */
    fun add(
        playlist: PlaylistSummary,
        song: Song,
        onResult: (AddToPlaylistResult) -> Unit = {},
    ) = run("add:${playlist.playlistId}:${song.videoId}") { generation ->
        val result = music.addToPlaylist(playlist.playlistId, song.videoId, generation)
        Log.i(WaveApplication.LOG_TAG, "playlist add result=${result::class.simpleName}")
        when (result) {
            AddToPlaylistResult.Added, AddToPlaylistResult.AlreadyInPlaylist -> membership?.noteAdded(playlist.playlistId, song.videoId)
            AddToPlaylistResult.Unknown -> membership?.invalidate(playlist.playlistId)
            else -> Unit
        }
        onResult(result)
        if (result == AddToPlaylistResult.Added || result == AddToPlaylistResult.Unknown) onChanged()
        when (result) {
            AddToPlaylistResult.Added -> context.getString(R.string.library_added_to_playlist, playlist.title)
            AddToPlaylistResult.AlreadyInPlaylist -> context.getString(R.string.library_already_in_playlist, playlist.title)
            is AddToPlaylistResult.NotChecked -> context.getString(R.string.library_add_not_checked)
            is AddToPlaylistResult.Failed -> context.getString(R.string.library_add_failed)
            AddToPlaylistResult.Unknown -> context.getString(R.string.library_add_unknown)
        }
    }

    /**
     * 追加の画面で、入っているプレイリストを押した: その曲を外す（同じ曲が2回入っていれば両方）。
     * @param onResult 外せたか（追加の画面が印を戻すのに使う）。メインスレッドとは限らない。
     */
    fun removeSong(
        playlist: PlaylistSummary,
        song: Song,
        onResult: (removed: Boolean) -> Unit = {},
    ) = run("remove-song:${playlist.playlistId}:${song.videoId}") { generation ->
        val setVideoIds = membership?.setVideoIds(playlist.playlistId, song.videoId)
        if (setVideoIds.isNullOrEmpty()) {
            Log.i(WaveApplication.LOG_TAG, "playlist remove-song no set ids known=${setVideoIds != null}")
            onResult(false)
            return@run context.getString(R.string.library_remove_failed)
        }
        val results = setVideoIds.map { music.removeFromPlaylist(playlist.playlistId, song.videoId, it, generation) }
        Log.i(WaveApplication.LOG_TAG, "playlist remove-song results=${results.map { it::class.simpleName }}")
        val removed = results.all { it is WriteResult.Done }
        if (removed) membership?.noteRemoved(playlist.playlistId, song.videoId) else membership?.invalidate(playlist.playlistId)
        if (results.any { it !is WriteResult.Failed }) onChanged()
        onResult(removed)
        when {
            removed -> context.getString(R.string.library_removed_from_playlist_named, playlist.title)
            results.any { it is WriteResult.Unknown } -> context.getString(R.string.library_remove_unknown)
            else -> context.getString(R.string.library_remove_failed)
        }
    }

    fun remove(
        playlistId: String,
        song: Song,
        setVideoId: String,
    ) = run("remove:$playlistId:$setVideoId") { generation ->
        val result = music.removeFromPlaylist(playlistId, song.videoId, setVideoId, generation)
        Log.i(WaveApplication.LOG_TAG, "playlist remove result=${result::class.simpleName}")
        if (result !is WriteResult.Failed) membership?.invalidate(playlistId)
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
