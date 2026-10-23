package xyz.mooma0750.wave.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.mooma0750.wave.WaveApplication

/**
 * 1日1回、裏で丸ごとのものを読み直し、スマートダウンロードを選び直し、取り残しを取る（ADR-0016 の 7・12）。
 * 使う通信は設定に従う（従量制でない通信だけ／どの通信でも）。アプリを閉じている間は OS が止めることがある。
 */
class DownloadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val downloads = (application as WaveApplication).container.downloads ?: return false
        job =
            scope.launch {
                try {
                    downloads.runInBackground(MAX_RUN_MS)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.i(WaveApplication.LOG_TAG, "download job failed type=${error::class.simpleName}")
                }
                Log.i(WaveApplication.LOG_TAG, "download job done pending=${downloads.state.value.pending}")
                jobFinished(params, false)
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
        private const val JOB_ID = 4402
        private const val INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** JobScheduler の1回の上限（10分）より少し短く。 */
        private const val MAX_RUN_MS = 9 * 60 * 1000L

        /** 予約する。anyNetwork が変わったら置き換える。enabled = false なら取り消す。 */
        fun schedule(
            context: Context,
            enabled: Boolean,
            anyNetwork: Boolean,
        ) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            val networkType = if (anyNetwork) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            @Suppress("DEPRECATION")
            if (scheduler.getPendingJob(JOB_ID)?.networkType == networkType) return
            val info =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, DownloadJob::class.java))
                    .setRequiredNetworkType(networkType)
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build()
            scheduler.schedule(info)
        }
    }
}
