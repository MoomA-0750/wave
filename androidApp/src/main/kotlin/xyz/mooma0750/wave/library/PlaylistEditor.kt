package xyz.mooma0750.wave.library

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    ) = run("add:${playlist.playlistId}:${song.videoId}") {
        val result = music.addToPlaylist(playlist.playlistId, song.videoId)
        Log.i(WaveApplication.LOG_TAG, "playlist add result=${result::class.simpleName}")
        if (result == AddToPlaylistResult.Added || result == AddToPlaylistResult.Unknown) onChanged()
        when (result) {
            AddToPlaylistResult.Added -> "「${playlist.title}」に追加しました"
            AddToPlaylistResult.AlreadyInPlaylist -> "「${playlist.title}」には既にあります"
            is AddToPlaylistResult.NotChecked -> "プレイリストを読めなかったので、追加していません"
            is AddToPlaylistResult.Failed -> "追加できませんでした"
            AddToPlaylistResult.Unknown -> "追加できたか確認できませんでした。プレイリストを開いて確かめてください"
        }
    }

    fun remove(
        playlistId: String,
        song: Song,
        setVideoId: String,
    ) = run("remove:$playlistId:$setVideoId") {
        val result = music.removeFromPlaylist(playlistId, song.videoId, setVideoId)
        Log.i(WaveApplication.LOG_TAG, "playlist remove result=${result::class.simpleName}")
        if (result !is WriteResult.Failed) onChanged()
        when (result) {
            is WriteResult.Done -> "プレイリストから外しました"
            is WriteResult.Failed -> "外せませんでした"
            WriteResult.Unknown -> "外せたか確認できませんでした。表示を読み直します"
        }
    }

    /** 作って、song があればそこに追加する。 */
    fun create(
        title: String,
        song: Song?,
    ) = run("create") {
        val result = music.createPlaylist(title)
        Log.i(WaveApplication.LOG_TAG, "playlist create result=${result::class.simpleName}")
        if (result !is WriteResult.Failed) onChanged()
        when (result) {
            is WriteResult.Done ->
                if (song == null) {
                    "「$title」を作りました"
                } else {
                    val added = music.addToPlaylist(result.value, song.videoId)
                    Log.i(WaveApplication.LOG_TAG, "playlist add result=${added::class.simpleName}")
                    onChanged()
                    if (added == AddToPlaylistResult.Added) "「$title」を作って追加しました" else "「$title」を作りましたが、追加できたか確認できませんでした"
                }
            is WriteResult.Failed -> "プレイリストを作れませんでした"
            WriteResult.Unknown -> "プレイリストを作れたか確認できませんでした。ライブラリを読み直すので、作られていないことを確かめてから作り直してください"
        }
    }

    private fun run(
        key: String,
        block: suspend () -> String,
    ): Boolean {
        synchronized(inFlight) { if (!inFlight.add(key)) return false }
        scope.launch {
            try {
                val message = block()
                withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
        return true
    }
}
