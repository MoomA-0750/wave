package xyz.mooma0750.wave.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song
import xyz.mooma0750.wave.playback.SongJson

/** 高く評価した曲を、プレイリストに足すよう誘う通知（開く画面 [AddToPlaylistActivity] はスマホ・時計がそれぞれ持つ）。 */
object LikedNotice {
    const val EXTRA_SONG = "song"
    private const val CHANNEL_ID = "liked"
    private const val NOTIFICATION_ID = 4301

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
                    .putExtra(EXTRA_SONG, SongJson.encode(song))
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

    /** 通知から開いた画面が、足す曲を読む。 */
    fun songOf(intent: Intent): Song? = SongJson.decode(intent.getStringExtra(EXTRA_SONG))

    /** 通知は押したら役目を終える。 */
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
