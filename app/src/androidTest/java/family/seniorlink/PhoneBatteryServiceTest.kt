package family.seniorlink

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import family.seniorlink.core.*
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneBatteryServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test fun phoneBatteryRequiresOptInAndStopsWithPause() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { app.initializationError == null && app.publicId.isNotBlank() }
        val old = app.store.settings
        assertTrue(old.role == Role.UNSET || old.role == Role.SHARER)
        val intent = Intent(app, MonitorService::class.java)
        val baseline = old.copy(role = Role.SHARER, phoneBattery = false, wearable = false, location = false, sms = false, telegram = false)
        try {
            app.store.updateSettings(baseline, app.publicId)
            compose.runOnUiThread { ContextCompat.startForegroundService(app, intent) }
            compose.waitUntil(10_000) { MonitorService.running.value }
            assertNull(app.store.phoneBattery(app.publicId))
            stop(app, intent)
            app.store.updateSettings(baseline.copy(phoneBattery = true), app.publicId)
            compose.runOnUiThread { ContextCompat.startForegroundService(app, intent) }
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) != null }
            assertTrue(app.store.phoneBattery(app.publicId)!!.event.phoneBattery!!.percent in 0..100)
            stop(app, intent)
            val sequence = app.store.latest(app.publicId)
            app.record(Event(0, Kind.PHONE_BATTERY, System.currentTimeMillis(), phoneBattery = PhoneBattery(1)))
            assertEquals(sequence, app.store.latest(app.publicId))
            app.store.updateSettings(baseline, app.publicId)
            assertNull(app.store.phoneBattery(app.publicId))
        } finally {
            stop(app, intent)
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }

    private fun stop(app: SeniorApp, intent: Intent) {
        compose.runOnUiThread { MonitorService.running.value = false; app.stopService(intent) }
        compose.waitUntil(10_000) { app.monitorStatus.value.startsWith("Monitoring paused") }
    }
}
