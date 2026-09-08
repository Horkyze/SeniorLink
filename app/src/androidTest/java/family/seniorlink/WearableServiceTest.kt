package family.seniorlink

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import family.seniorlink.core.Role
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearableServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(*buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun unavailableBandDoesNotPreventSharingAndPauseClosesItsConnection() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { app.initializationError == null && app.publicId.isNotBlank() }
        val old = app.store.settings
        // This test uses only the emulator's synthetic app state and a nonexistent peripheral.
        assertTrue(old.role == Role.UNSET || old.role == Role.SHARER)
        val settings = old.copy(role = Role.SHARER, sms = false, location = false, wearable = true,
            wearableAddress = "AA:BB:CC:DD:EE:FF", wearableName = "Synthetic unavailable band")
        val intent = Intent(app, MonitorService::class.java)
        try {
            app.store.updateSettings(settings, app.publicId)
            compose.runOnUiThread { ContextCompat.startForegroundService(app, intent) }
            compose.waitUntil(10_000) { MonitorService.running.value }
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 })
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(MonitorService.running.value)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnUiThread {
                MonitorService.running.value = false
                app.stopService(intent)
            }
            compose.waitUntil(10_000) { app.wearableState.value.status == "Wearable collection is paused" }
            assertFalse(app.wearableState.value.connected)
            assertTrue(app.store.wearables(app.publicId).isEmpty())
        } finally {
            compose.runOnUiThread { MonitorService.running.value = false; app.stopService(intent) }
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }
}
