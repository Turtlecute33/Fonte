package app.fonte.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.os.PersistableBundle
import app.fonte.FonteApp
import app.fonte.app
import app.fonte.install.Installer
import app.fonte.source.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An install that the user starts in the app, as a user-initiated data transfer job: the download goes on when the
 * user leaves Fonte (a cached process gets no CPU), and a notification shows its progress. One job for each app.
 */
class DownloadJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val appId = params.extras.getString(EXTRA_APP) ?: return false
        val a = app
        // The channel must exist before the first notification, also when the system starts the process for this job.
        a.notifier.setUp()
        fun show(task: Installer.Task?) =
            setNotification(params, params.jobId, a.notifier.download(a.store.app(appId)?.name, task), JOB_END_NOTIFICATION_POLICY_REMOVE)
        show(null)
        work = a.scope.launch {
            try {
                a.store.awaitLoaded()
                if (withContext(Dispatchers.IO) { needed(a, appId) }) coroutineScope {
                    // At most one notification update a second: the system drops faster ones.
                    val progress = launch {
                        a.installer.tasks.map { it[appId] }.distinctUntilChanged().collect {
                            show(it)
                            delay(UPDATE_MS)
                        }
                    }
                    a.installer.install(appId, background = false)
                    progress.cancel()
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The user stopped it (Task Manager): done. Other stops (no network): the system runs it again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    companion object {
        private const val NAMESPACE = "downloads"
        private const val EXTRA_APP = "app"
        private const val UPDATE_MS = 1_000L

        /**
         * Schedules the install of [appId]. Call while the app is visible. False when no job can do it: the system
         * refused it, or there is no network (a job would wait with no message; a direct install fails at once).
         */
        fun schedule(context: Context, appId: String, bytes: Long): Boolean {
            if (context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) return false
            val js = context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
            val id = appId.hashCode()
            if (js.getPendingJob(id) != null) return true
            val info = JobInfo.Builder(id, ComponentName(context, DownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(if (bytes > 0) bytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0)
                .setExtras(PersistableBundle().apply { putString(EXTRA_APP, appId) })
                .build()
            return runCatching { js.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        }

        /**
         * False when the app is installed and up to date. The system runs a job again when the process ends while
         * it runs, and the install of Fonte itself ends the process.
         */
        private fun needed(a: FonteApp, appId: String): Boolean {
            val t = a.store.app(appId) ?: return false
            val info = t.packageName?.let(a.packages::info) ?: return true
            return Versions.hasUpdate(t, info.versionCode, info.versionName)
        }
    }
}
