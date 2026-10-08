package xyz.mooma0750.wave.releases

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.NewRelease
import xyz.mooma0750.wave.core.music.ReleaseWatchState
import xyz.mooma0750.wave.core.session.CallResult
import java.io.File

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
        private const val MAX_ARTISTS = 200
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

        private fun stateFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "release-watch.json"))

        /** ログアウトで、前のアカウントの見張りの記録を消す。 */
        fun clear(context: Context) = stateFile(context).delete()

        /** @return 最後まで確かめられたか */
        suspend fun check(
            context: Context,
            container: xyz.mooma0750.wave.AppContainer,
        ): Boolean {
            if (!container.settings.releaseNotices.first()) return true
            // 裏で起きたばかりなら、保存したログインを読み終えるのを待つ
            val auth = withTimeoutOrNull(30_000) { container.authManager.state.first { it !is AuthState.Restoring } }
            if (auth !is AuthState.LoggedIn) return true
            val artists =
                when (val result = container.music.subscribedArtists()) {
                    is CallResult.Ok -> result.value.take(MAX_ARTISTS)
                    is CallResult.Failed -> return false
                }
            val file = stateFile(context)
            var state = runCatching { file.readFully().decodeToString() }.getOrNull()?.let(ReleaseWatchState::decode) ?: ReleaseWatchState()
            state = state.retain(artists.map { it.browseId }.toSet())
            val found = mutableListOf<NewRelease>()
            for (artist in artists) {
                val page = (container.music.artist(artist.browseId) as? CallResult.Ok)?.value ?: continue
                val (fresh, next) = state.observe(artist.browseId, page)
                state = next
                found += fresh
                // まとめて叩かない
                delay(500)
            }
            val stream = file.startWrite()
            try {
                stream.write(ReleaseWatchState.encode(state).encodeToByteArray())
                file.finishWrite(stream)
            } catch (error: java.io.IOException) {
                file.failWrite(stream)
            }
            Log.i(WaveApplication.LOG_TAG, "release watch artists=${artists.size} new=${found.size}")
            found.take(MAX_NOTIFICATIONS).forEach { notify(context, it) }
            return true
        }

        private const val MAX_NOTIFICATIONS = 10

        private fun notify(
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
