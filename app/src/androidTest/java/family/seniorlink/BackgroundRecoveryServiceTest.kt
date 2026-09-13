package family.seniorlink

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import family.seniorlink.core.*
import family.seniorlink.monitor.BackgroundRecovery
import family.seniorlink.monitor.BackgroundSession
import family.seniorlink.monitor.MonitorService
import family.seniorlink.monitor.RecoveryReceiver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundRecoveryServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    @Test fun freshRecoveryDefersLocationUntilVisibleResumeWithoutDuplicatingTheSession() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { app.initializationError == null && app.publicId.isNotBlank() }
        val old = app.store.settings
        assertTrue(old.role == Role.UNSET || old.role == Role.SHARER)
        val start = Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        try {
            app.store.updateSettings(old.copy(role = Role.SHARER, location = true, wearable = false,
                phoneBattery = false, sms = false, telegram = false), app.publicId)
            compose.runOnUiThread { ContextCompat.startForegroundService(app, start) }
            compose.waitUntil(10_000) { MonitorService.running.value }
            assertFalse(MonitorService.locationDeferred.value)
            compose.runOnUiThread { app.stopService(start) }
            compose.waitUntil(10_000) { !MonitorService.running.value }
            compose.runOnUiThread {
                ContextCompat.startForegroundService(app, Intent(start).setAction(MonitorService.ACTION_RESUME))
            }
            compose.waitUntil(10_000) { MonitorService.running.value }
            if (Build.VERSION.SDK_INT >= 29) assertTrue(MonitorService.locationDeferred.value)
            val session = MonitorService.sessionToken
            compose.runOnUiThread { BackgroundRecovery.resume(app, visible = true) }
            compose.waitUntil(10_000) { !MonitorService.locationDeferred.value }
            assertSame(session, MonitorService.sessionToken)
        } finally {
            compose.runOnUiThread { MonitorService.pause(app) }
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }

    @Test fun sharingSurvivesClosedScreenAndRestoresSavedSessionButPausePreventsRecovery() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { app.initializationError == null && app.publicId.isNotBlank() }
        val old = app.store.settings
        assertTrue(old.role == Role.UNSET || old.role == Role.SHARER)
        val start = Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        val resume = Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_RESUME)
        try {
            compose.runOnUiThread { MonitorService.pause(app) }
            app.store.updateSettings(old.copy(role = Role.SHARER, phoneBattery = true,
                location = false, wearable = false, sms = false, telegram = false), app.publicId)
            compose.runOnUiThread { ContextCompat.startForegroundService(app, start) }
            compose.waitUntil(10_000) { MonitorService.running.value && app.store.phoneBattery(app.publicId) != null }
            assertTrue(BackgroundSession(app).enabled(Role.SHARER))
            assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isNotEmpty())
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(MonitorService.running.value)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 })
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

            // Controlled service destruction exercises persisted recovery, not an OS process kill.
            val priorSession = MonitorService.sessionToken
            val beforeRestart = app.store.latest(app.publicId)
            compose.runOnUiThread { app.stopService(start) }
            compose.waitUntil(10_000) { !MonitorService.running.value }
            assertTrue(BackgroundSession(app).enabled(Role.SHARER))
            compose.runOnUiThread { ContextCompat.startForegroundService(app, resume) }
            compose.waitUntil(10_000) { MonitorService.running.value && MonitorService.sessionToken !== priorSession &&
                app.store.latest(app.publicId) > beforeRestart }
            val sequence = app.store.latest(app.publicId)
            app.record(Event(0, Kind.CHECK_IN, System.currentTimeMillis()), priorSession)
            assertEquals("A cancelled session cannot publish into its replacement", sequence, app.store.latest(app.publicId))

            compose.runOnUiThread { MonitorService.pause(app) }
            compose.waitUntil(10_000) { app.monitorStatus.value.startsWith("Monitoring paused") }
            assertFalse(BackgroundSession(app).enabled(Role.SHARER))
            assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.none { it.id == 1001 })
            compose.runOnUiThread {
                RecoveryReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
                BackgroundRecovery.resume(app)
            }
            assertFalse(MonitorService.running.value)
            assertFalse(MonitorService.receiving.value)
            app.record(Event(0, Kind.CHECK_IN, System.currentTimeMillis()))
            assertEquals(sequence, app.store.latest(app.publicId))
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnUiThread { MonitorService.pause(app) }
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }
}
