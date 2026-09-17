package family.seniorlink

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import computer.iroh.SecretKey
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.monitor.MonitorService
import family.seniorlink.monitor.PhoneBatteryMonitor
import family.seniorlink.net.IrohSync
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test fun editsSurviveNavigationAndBatteryStartsWithoutPausingThenReachesCaregiver() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val app = model.app
        compose.waitUntil(15_000) { model.screen.value.ready }
        val old = app.store.settings
        assertTrue(old.role == Role.UNSET || old.role == Role.SHARER)
        try {
            prepare(model)
            val session = MonitorService.sessionToken
            compose.onNodeWithTag("navigation-3").performClick()
            compose.onNodeWithText("Share phone battery").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("navigation-0").performClick()
            compose.onNodeWithTag("navigation-3").performClick()
            compose.onNodeWithText("Share phone battery").performScrollTo().assertIsOn()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Share phone battery").performScrollTo().assertIsOn()
            compose.onNodeWithText("Save settings").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) != null }
            assertTrue(app.backgroundSession.enabled(Role.SHARER))
            assertTrue(MonitorService.running.value)
            assertSame("Applying features must keep the sharing session", session, MonitorService.sessionToken)
            val saved = app.store.phoneBattery(app.publicId)!!.event
            assertEquals(PhoneBatteryMonitor.read(app)?.percent, saved.phoneBattery?.percent)
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithTag("navigation-0").performClick()
            compose.onNodeWithText("${saved.phoneBattery!!.percent}%").performScrollTo().assertIsDisplayed()

            runBlocking(Dispatchers.IO) {
                withTimeout(90_000) {
                    val secret = SecretKey.generate().use { it.toBytes() }
                    val id = SecretKey.fromBytes(secret).use { it.public().use { key -> key.toString() } }
                    Store(app, "test-battery-inbox-${UUID.randomUUID()}").use { inbox ->
                        inbox.updateSettings(Settings(role = Role.CAREGIVER), id)
                        inbox.addPeer(app.publicId, "Synthetic source", id)
                        app.store.addPeer(id, "Synthetic caregiver", app.publicId)
                        val receiver = launch { IrohSync.receive(secret, inbox, { 500 }) { _, _ -> } }
                        try {
                            while (inbox.phoneBattery(app.publicId) == null) delay(100)
                            assertEquals(saved, inbox.phoneBattery(app.publicId)!!.event)
                        } finally {
                            receiver.cancelAndJoin()
                            app.store.removePeer(id)
                        }
                    }
                }
            }
            compose.runOnUiThread { model.save(app.store.settings.copy(phoneBattery = false, unlock = true, location = true), "") }
            compose.waitUntil(10_000) { !model.savingSettings.value && app.store.settings.location }
            assertNull(app.store.phoneBattery(app.publicId))
            assertTrue(MonitorService.running.value)
            assertSame(session, MonitorService.sessionToken)
            compose.runOnUiThread { model.save(app.store.settings.copy(phoneBattery = true, location = false), "") }
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) != null }
            assertTrue(MonitorService.running.value)
            assertSame(session, MonitorService.sessionToken)
        } finally {
            stop(model)
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }

    @Test fun deniedPermissionKeepsCurrentSharingAndSavingNeverUndoesPause() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val app = model.app
        compose.waitUntil(15_000) { model.screen.value.ready }
        val old = app.store.settings
        try {
            prepare(model)
            // Drive the permission result directly; no synthetic SMS is collected.
            compose.runOnUiThread { compose.activity.setContent {} }
            val before = app.store.settings
            val proposed = before.copy(sms = true, smsSenders = "Synthetic sender")
            compose.runOnUiThread { model.save(proposed, "") }
            compose.waitUntil(10_000) { model.permissionRequest.value.contains(Manifest.permission.RECEIVE_SMS) }
            assertEquals(before, app.store.settings)
            assertTrue(MonitorService.running.value)
            compose.runOnUiThread { model.permissionsLaunched(); model.permissionsUpdated() }
            compose.waitUntil(10_000) { !model.savingSettings.value }
            assertEquals(before, app.store.settings)
            assertTrue(app.backgroundSession.enabled(Role.SHARER))
            assertTrue(MonitorService.running.value)

            compose.runOnUiThread { model.save(proposed, "") }
            compose.waitUntil(10_000) { model.permissionRequest.value.isNotEmpty() }
            compose.runOnUiThread { model.pause() }
            assertFalse(model.savingSettings.value)
            assertTrue(model.permissionRequest.value.isEmpty())
            compose.runOnUiThread { model.permissionsUpdated() }
            compose.waitUntil(10_000) { !model.savingSettings.value }
            assertFalse(app.backgroundSession.enabled(Role.SHARER))
            assertFalse(MonitorService.running.value)
        } finally {
            stop(model)
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }

    @Test fun saveFinishingAfterBackgroundingStillAppliesBatteryAndDefersNewLocation() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { model.screen.value.ready }
        val app = model.app
        val old = app.store.settings
        try {
            prepare(model)
            compose.runOnUiThread {
                model.save(app.store.settings.copy(phoneBattery = true, location = true), "")
                model.foreground(false)
            }
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) != null && MonitorService.locationDeferred.value }
            assertTrue(MonitorService.running.value)
            assertTrue(app.backgroundSession.enabled(Role.SHARER))
            // The service also observes a committed edit if the editor disappears
            // before it can deliver any explicit resume/reconfigure command.
            app.store.updateSettings(app.store.settings.copy(phoneBattery = false, location = false), app.publicId)
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) == null && !MonitorService.locationDeferred.value }
            app.store.updateSettings(app.store.settings.copy(phoneBattery = true, location = true), app.publicId)
            compose.waitUntil(10_000) { app.store.phoneBattery(app.publicId) != null && MonitorService.locationDeferred.value }
            compose.runOnUiThread { model.foreground(true) }
            compose.waitUntil(10_000) { !MonitorService.locationDeferred.value }
        } finally {
            stop(model)
            app.store.updateSettings(old.copy(role = Role.SHARER), app.publicId)
        }
    }

    private fun prepare(model: MainViewModel) {
        stop(model)
        model.app.store.updateSettings(Settings(role = Role.SHARER), model.app.publicId)
        compose.waitUntil(10_000) { model.screen.value.settings == model.app.store.settings }
        compose.runOnUiThread { model.message(null); model.start() }
        compose.waitUntil(10_000) { MonitorService.running.value }
    }

    private fun stop(model: MainViewModel) {
        compose.runOnUiThread { model.pause() }
        @Suppress("DEPRECATION")
        compose.waitUntil(10_000) {
            !MonitorService.running.value && model.app.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE).none { it.service.className == MonitorService::class.java.name }
        }
    }
}
