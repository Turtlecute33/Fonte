package app.fonte.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import app.fonte.app
import app.fonte.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The periodic check: JobScheduler, with a network, and not on low battery. */
class UpdateJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = app.scope.launch {
            try {
                app.runBackground(params.network)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val ID = 1
        private const val HOUR = 3600_000L

        /** Schedules or cancels the job. [replace]: also when it is scheduled already (the settings changed). */
        fun schedule(context: Context, settings: Settings, replace: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (!settings.background) return js.cancel(ID)
            val pending = js.getPendingJob(ID)
            // A job from Fonte 0.1.4 or older can wait for an unmetered network: replace it, so that checks use any network.
            val unmetered = pending?.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            if (!replace && pending != null && !unmetered) return
            val period = settings.intervalHours * HOUR
            val info = JobInfo.Builder(ID, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(period, period / 4)
                // Checks are small; [Settings.unmeteredOnly] holds back only the downloads.
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }
    }
}
