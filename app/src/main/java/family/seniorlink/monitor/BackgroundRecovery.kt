package family.seniorlink.monitor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import family.seniorlink.MainActivity
import family.seniorlink.R
import family.seniorlink.SeniorApp

object BackgroundRecovery {
    private const val JOB = 1001
    private const val NOTICE = 1002
    private const val CHANNEL = "background-recovery"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(
            JobInfo.Builder(JOB, ComponentName(context, RecoveryJob::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build(),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB)
        clearNotice(context)
    }

    fun clearNotice(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(NOTICE) }

    fun resume(context: Context, visible: Boolean = false) {
        val app = context.applicationContext as SeniorApp
        if (!app.backgroundSession.enabled(app.store.settings.role)) { cancel(context); return }
        if (visible) app.backgroundSession.allowRecovery()
        else if (!mayRecoverAutomatically(app)) return
        if (app.initializationError != null) return
        if (MonitorService.missingPermissions(context, app.store.settings).isNotEmpty()) {
            MonitorService.pause(app)
            return
        }
        schedule(context)
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)
                .setAction(if (visible) MonitorService.ACTION_RESUME_VISIBLE else MonitorService.ACTION_RESUME))
        } catch (_: Exception) {
            notice(context)
        }
    }

    fun notice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background update recovery", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, NOTICE, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(NOTICE, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_seniorlink)
                .setContentTitle("Open SeniorLink to resume updates")
                .setContentText("Android restricted background startup. Your saved updates are still on this phone.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (_: SecurityException) { /* The notification permission may have been withdrawn. */ }
    }

    @RequiresApi(30)
    internal fun userStopped(reason: Int, exitedAt: Long, startedAt: Long): Boolean = exitedAt >= startedAt &&
        (reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == ApplicationExitInfo.REASON_USER_STOPPED)

    fun mayRecoverAutomatically(app: SeniorApp): Boolean {
        if (app.backgroundSession.recoverySuppressed) return false
        if (Build.VERSION.SDK_INT < 30) return true
        val exits = app.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(app.packageName, 0, 0)
        // Android also uses USER_REQUESTED for some OEM task removals. Favor the user's stop
        // over a watchdog restart; ordinary Recents removal leaves our sticky service running.
        if (exits.any { userStopped(it.reason, it.timestamp, app.backgroundSession.startedAt) }) {
            app.backgroundSession.suppressRecovery()
            cancel(app)
            return false
        }
        return true
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            BackgroundRecovery.resume(context)
    }
}

class RecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as SeniorApp
        if (!app.backgroundSession.enabled(app.store.settings.role)) BackgroundRecovery.cancel(this)
        else if (!MonitorService.running.value && !MonitorService.receiving.value)
            BackgroundRecovery.resume(this)
        return false
    }
    override fun onStopJob(params: JobParameters) = false
}
