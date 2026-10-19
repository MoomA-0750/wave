package xyz.mooma0750.wave.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * アプリを開かずにプレイリストに足す（通知の「プレイリストに追加」から開く）。透明な Activity にダイアログだけを出し、閉じたら終わる。
 * 外からは開けない（exported=false。自アプリの通知の PendingIntent からだけ）。
 */
class AddToPlaylistActivity : ComponentActivity() {
    // Android 12 以前は、設定で固定した画面の言語をここで当てる（13 以降は OS が当てる）
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WaveApplication).container
        val song = LikedNotice.songOf(intent)
        if (song == null || container.authManager.state.value !is AuthState.LoggedIn) {
            finish()
            return
        }
        // 通知は押したら役目を終える
        LikedNotice.cancel(this)
        setContent {
            WaveTheme {
                AddToPlaylistDialog(
                    viewModel { LibraryViewModel(container.music, container.libraryCache) },
                    onDismiss = ::finish,
                    onPick = { playlist, onResult -> container.playlistEditor.add(playlist, song, onResult) },
                    onUnpick = { playlist, onResult -> container.playlistEditor.removeSong(playlist, song, onResult) },
                    onCreate = { title ->
                        container.playlistEditor.create(title, song)
                        finish()
                    },
                    song = song,
                    membership = container.playlistMembership,
                )
            }
        }
    }
}
