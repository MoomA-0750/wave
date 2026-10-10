package xyz.mooma0750.wave.releases

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.music.NewRelease

/**
 * 登録したアーティストの新作（アルバム・シングル・先行の曲・YouTube の動画）を、裏で数時間おきに確かめて通知で知らせる。
 * アーティストのページの棚を前に見たときと比べる（ReleaseWatchState）。初めて見るアーティストは覚えるだけ。
 */
class ReleaseWatcher : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as WaveApplication).container
        job =
            scope.launch {
                val rescheduleOnFailure =
                    try {
                        !check(this@ReleaseWatcher, container)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.i(WaveApplication.LOG_TAG, "release watch failed type=${error::class.simpleName}")
                        true
                    }
                jobFinished(params, rescheduleOnFailure)
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4401
        private const val CHANNEL_ID = "releases"
        private const val INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val EXTRA_ALBUM = "xyz.mooma0750.wave.OPEN_ALBUM"
        const val EXTRA_ALBUM_TITLE = "xyz.mooma0750.wave.OPEN_ALBUM_TITLE"
        const val EXTRA_ARTIST = "xyz.mooma0750.wave.OPEN_ARTIST"
        const val EXTRA_ARTIST_NAME = "xyz.mooma0750.wave.OPEN_ARTIST_NAME"

        /** 定期の確かめを予約する（同じものがあれば置き換えない）。enabled = false なら取り消す。 */
        fun schedule(
            context: Context,
            enabled: Boolean,
        ) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, ReleaseWatcher::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build()
            scheduler.schedule(info)
        }

        /** @return 最後まで確かめられたか */
        suspend fun check(
            context: Context,
            container: xyz.mooma0750.wave.AppContainer,
        ): Boolean =
            container.releases.check { found ->
                if (found.isEmpty()) return@check
                // 鳴らす通知は、よく聴くアーティスト（高く評価した曲が MIN_LIKED_TO_NOTIFY 曲以上）の新作だけにする（Apple Music の
                // お気に入りの新作の通知と同じ考え方）。ほかの新作はホームとアーティストのページの「新作」に並べるだけ
                val liked = container.likedSongs.songs(container.likedSongsVersion.value).songs
                val favorites = found.filter { xyz.mooma0750.wave.library.LikedByArtist.filter(liked, it.artistId, it.artistName).size >= MIN_LIKED_TO_NOTIFY }
                android.util.Log.i(xyz.mooma0750.wave.WaveApplication.LOG_TAG, "release notify found=${found.size} favorites=${favorites.size} liked=${liked.size}")
                // 時計では知らせない（スマホの通知が時計にも出るので、二重になる）。一覧（新作の画面）には入る
                if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) favorites.take(MAX_NOTIFICATIONS).forEach { notify(context, it) }
            }

        private const val MAX_NOTIFICATIONS = 10

        /** 新作を通知するのに要る、そのアーティストの高く評価した曲の数。 */
        private const val MIN_LIKED_TO_NOTIFY = 3

        fun notify(
            context: Context,
            release: NewRelease,
        ) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(context.getString(R.string.releases_channel)).build(),
            )
            // 押すとアルバム（動画ならアーティストのページ）を開く
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply {
                        if (release.albumBrowseId != null) {
                            putExtra(EXTRA_ALBUM, release.albumBrowseId)
                            putExtra(EXTRA_ALBUM_TITLE, release.title)
                        } else {
                            putExtra(EXTRA_ARTIST, release.artistId)
                            putExtra(EXTRA_ARTIST_NAME, release.artistName)
                        }
                    }
            val key = release.albumBrowseId ?: release.videoId ?: release.title
            val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_music_note)
                    .setContentTitle(context.getString(if (release.videoId != null) R.string.releases_new_video else R.string.releases_new_release, release.artistName))
                    .setContentText(release.title)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
            runCatching { manager.notify(key.hashCode(), notification) }
        }
    }
}
