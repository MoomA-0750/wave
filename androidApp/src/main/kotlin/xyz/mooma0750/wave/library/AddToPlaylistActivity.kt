package xyz.mooma0750.wave.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * アプリを開かずにプレイリストに足す（通知の「プレイリストに追加」から開く）。透明な Activity にダイアログだけを出し、閉じたら終わる。
 * 外からは開けない（exported=false。自アプリの通知の PendingIntent からだけ）。
 */
class AddToPlaylistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WaveApplication).container
        val song = intent.getStringExtra(EXTRA_SONG)?.let { runCatching { json.decodeFromString(Song.serializer(), it) }.getOrNull() }
        if (song == null || container.authManager.state.value !is AuthState.LoggedIn) {
            finish()
            return
        }
        // 通知は押したら役目を終える
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        setContent {
            WaveTheme {
                AddToPlaylistDialog(
                    viewModel { LibraryViewModel(container.music) },
                    onDismiss = ::finish,
                    onPick = { playlist, onResult -> container.playlistEditor.add(playlist, song, onResult) },
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

    companion object {
        private const val EXTRA_SONG = "song"
        private const val CHANNEL_ID = "liked"
        private const val NOTIFICATION_ID = 4301
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 通知のハートで高く評価したことを知らせ、「プレイリストに追加」のボタンを付ける。
         * 通知の許可が無ければ出さない。
         */
        fun notifyLiked(
            context: Context,
            song: Song,
        ) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(context.getString(R.string.library_notification_channel_liked)).build(),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, AddToPlaylistActivity::class.java)
                        .putExtra(EXTRA_SONG, json.encodeToString(Song.serializer(), song))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_check_circle)
                    .setContentTitle(context.getString(R.string.library_liked_notice_title, song.title))
                    .setContentText(context.getString(R.string.library_liked_notice_text))
                    .addAction(R.drawable.ic_playlist_add, context.getString(R.string.library_add_to_playlist), open)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000)
                    .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
    }
}
