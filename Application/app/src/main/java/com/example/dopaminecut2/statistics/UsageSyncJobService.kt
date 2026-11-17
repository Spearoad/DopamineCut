package com.example.dopaminecut2.statistics

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.example.dopaminecut2.DopamineCutApplication
import kotlinx.coroutines.*

/** Persisted one-shot jobs survive ordinary process death/reboot; Android may delay execution. */
class AndroidUsageSyncSchedule(context: Context) : UsageSyncSchedule {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("usage_sync_schedule", Context.MODE_PRIVATE)
    private val jobs = this.context.getSystemService(JobScheduler::class.java)
    override fun nextAt(uid: String): Long = prefs.getLong(uid, 0L)
    override fun defer(uid: String, nextAt: Long) { check(prefs.edit().putLong(uid, nextAt).commit()) }
    override fun ensure(uid: String) {
        if (nextAt(uid) == 0L) defer(uid, System.currentTimeMillis() + UsageSyncCoordinator.DAY_MS)
        val due = nextAt(uid)
        val existing = jobs.getPendingJob(JOB_ID)
        if (existing?.extras?.getString("uid") == uid && existing.extras.getLong("due") == due) return
        val extras = PersistableBundle().apply { putString("uid", uid); putLong("due", due) }
        check(jobs.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UsageSyncJobService::class.java))
            .setExtras(extras).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency((due - System.currentTimeMillis()).coerceAtLeast(0L)).build()) == JobScheduler.RESULT_SUCCESS)
    }
    override fun cancel() { jobs.cancel(JOB_ID) }
    companion object { const val JOB_ID = 17024 }
}

class UsageSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val dependencies = (application as DopamineCutApplication).dependenciesOrNull() ?: return false
        val uid = params.extras.getString("uid") ?: return false
        if (dependencies.authRepository.currentUserId() != uid) return false
        work = scope.launch {
            try { withContext(Dispatchers.IO) { withTimeout(120_000L) { dependencies.usageSync.sync(uid) } }
            } catch (_: TimeoutCancellationException) {
                dependencies.usageSyncSchedule.defer(uid, System.currentTimeMillis() + UsageSyncCoordinator.RETRY_MS)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { dependencies.usageSyncSchedule.defer(uid, System.currentTimeMillis() + UsageSyncCoordinator.RETRY_MS) }
            jobFinished(params, false)
            if (dependencies.authRepository.currentUserId() == uid) dependencies.usageSyncSchedule.ensure(uid)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
