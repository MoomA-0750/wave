package xyz.mooma0750.wave.debug

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.AppContainer
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.session.CallResult

/**
 * debug ビルドだけ: adb から `am start -n xyz.mooma0750.wave/.MainActivity --es debug_action <名前>` で確認の操作を起こす。
 * 画面の文字を読まずに（個人のライブラリを PC に出さずに）通し確認をするため。release ビルドでは何もしない。
 */
object DebugActions {
    private const val EXTRA = "debug_action"

    fun handle(
        intent: Intent?,
        container: AppContainer,
    ) {
        if (!container.isDebuggable) return
        val action = intent?.getStringExtra(EXTRA) ?: return
        intent.removeExtra(EXTRA)
        Log.i(WaveApplication.LOG_TAG, "debug action=$action")
        container.appScope.launch(Dispatchers.Main) {
            // 起動直後は保存したログインの復元が終わっていない
            val auth = container.authManager.state.first { it !is AuthState.Restoring }
            // セッションへの反映も待つ（HomeScreen と同じ）
            if (auth is AuthState.LoggedIn) container.session.appliedObtainedAtEpochMs.first { it == auth.obtainedAtEpochMs }
            val player = container.player
            when (action) {
                "search_play" ->
                    when (val result = container.music.searchSongs(intent.getStringExtra("query") ?: "YOASOBI")) {
                        is CallResult.Ok -> result.value.songs.getOrNull(intent.getIntExtra("index", 0))?.let { player.playSong(it) }
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug search failed ${result.kind}")
                    }
                "add_unplayable_next" -> player.addNext(DebugPlayback.unplayableSong)
                "play_public_playlist" ->
                    when (val page = container.music.playlist(DebugPlayback.PUBLIC_PLAYLIST_ID)) {
                        is CallResult.Ok -> player.playFrom(page.value.songs, 0, DebugPlayback.PUBLIC_PLAYLIST_ID, page.value.continuation)
                        is CallResult.Failed -> Log.i(WaveApplication.LOG_TAG, "debug playlist failed ${page.kind}")
                    }
                "last_item" -> player.controller().run { seekTo(mediaItemCount - 1, 0L) }
                "near_end" -> player.controller().run { if (duration > 10_000) seekTo(duration - 5_000) }
                "logout" -> container.logout()
                else -> Log.i(WaveApplication.LOG_TAG, "debug action unknown")
            }
        }
    }
}
