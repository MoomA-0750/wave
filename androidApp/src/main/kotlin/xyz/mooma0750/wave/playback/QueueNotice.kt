package xyz.mooma0750.wave.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.core.music.Song

/**
 * 通知センターに出すキュー（次に流れる曲。設定で入れたときだけ）。再生中は消せない通知にし、止まっているときは消せる。
 * 押すとアプリの再生画面のキューを開く。再生サービスが、曲・キュー・再生の状態が変わるたびに [update] を呼ぶ。
 */
class QueueNotice(
    private val context: Context,
) {
    private var lastKey: String? = null

    /**
     * @param queue 次に流れる曲と、今の曲の後に残っている曲の数。null（キューが空・設定で切っている）なら消す
     * @param playing 鳴っている（消せない通知にする）
     */
    fun update(
        queue: Upcoming?,
        playing: Boolean,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (queue == null || !manager.areNotificationsEnabled()) {
            cancel()
            return
        }
        val lines = queue.songs.take(MAX_LINES).map(::lineOf)
        val key = "$playing|${queue.currentId}|${queue.songs.take(MAX_LINES).joinToString("|") { it.videoId }}|${queue.remaining}"
        if (key == lastKey) return
        lastKey = key
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setName(context.getString(R.string.queue_notice_channel))
                .setShowBadge(false)
                .build(),
        )
        val open =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_QUEUE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val rest = queue.remaining - lines.size
        val style = NotificationCompat.InboxStyle().setBigContentTitle(context.getString(R.string.nowplaying_next_up))
        lines.forEach(style::addLine)
        if (rest > 0) style.setSummaryText(context.getString(R.string.queue_notice_more, rest))
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_queue_music)
                .setContentTitle(context.getString(R.string.nowplaying_next_up))
                .setContentText(lines.firstOrNull() ?: context.getString(R.string.queue_notice_empty))
                .setStyle(style)
                .setContentIntent(open)
                .setOngoing(playing)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup(GROUP)
                .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun lineOf(song: Song): String = listOf(song.title, song.artists.joinToString(", ") { it.name }).filter(String::isNotBlank).joinToString(" · ")

    /** 次に流れる曲（先頭から数曲）と、今の曲の後に残っている曲の数。 */
    data class Upcoming(
        val currentId: String?,
        val songs: List<Song>,
        val remaining: Int,
    )

    fun cancel() {
        if (lastKey == null) return
        lastKey = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "xyz.mooma0750.wave.OPEN_QUEUE"
        // 重要度が低いと「サイレント」のまとまりに畳まれて見えにくいので、通常の重要度で音と振動を切る
        // （チャンネルの重要度は作った後に上げられないので、ID を変えた）
        private const val CHANNEL_ID = "queue_visible"
        private const val GROUP = "queue"
        private const val NOTIFICATION_ID = 4501
        private const val REQUEST_CODE = 4501
        private const val MAX_LINES = 6

        /** 手元の再生器の、今の曲の後に流れる曲（再生の順）。 */
        fun upcomingOf(player: Player): Upcoming? {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return null
            val songs = mutableListOf<Song>()
            var index = player.currentMediaItemIndex
            var remaining = 0
            while (true) {
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                if (index == C.INDEX_UNSET) break
                remaining++
                if (songs.size < MAX_LINES) Media3QueuePlayer.songOf(player.getMediaItemAt(index))?.let(songs::add)
            }
            return Upcoming(player.currentMediaItem?.mediaId, songs, remaining)
        }

        private val mutableOpenRequested = MutableStateFlow(false)

        /** 通知から「キューを開いて」と頼まれた（再生画面が開いて、キューを出したら [consumeOpenRequest]）。 */
        val openRequested: StateFlow<Boolean> = mutableOpenRequested.asStateFlow()

        fun requestOpen() {
            mutableOpenRequested.value = true
        }

        fun consumeOpenRequest() {
            mutableOpenRequested.value = false
        }
    }
}
