package family.seniorlink

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.SecretKey
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.monitor.MonitorService
import family.seniorlink.net.IrohSync
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run on a dedicated caregiver test installation. Uses synthetic data and public iroh discovery. */
@RunWith(AndroidJUnit4::class)
class CaregiverBackgroundServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test fun backgroundReceiverDeliversWithClosedScreenWithoutCollectingCaregiverData(): Unit = runBlocking(Dispatchers.IO) {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { app.initializationError == null && app.publicId.isNotBlank() }
        val caregiverFixture = InstrumentationRegistry.getArguments().getString("seniorlink.caregiverFixture") == "true"
        assumeTrue("Use a caregiver installation or explicitly select the isolated caregiver fixture",
            app.store.settings.role == Role.CAREGIVER || (caregiverFixture && app.store.settings.role == Role.UNSET))
        assumeTrue("Run the connection default check before choosing a background preference",
            !app.getSharedPreferences("background-session", 0).contains("role"))
        if (app.store.settings.role == Role.UNSET) app.store.updateSettings(Settings(role = Role.CAREGIVER), app.publicId)
        val key = SecretKey.generate().use { it.toBytes() }
        val endpoint = IrohSync.bind(key)
        val sourceId = endpoint.id().use { it.toString() }
        val source = Store(app, "background-source-${UUID.randomUUID()}")
        source.updateSettings(Settings(role = Role.SHARER), sourceId)
        source.addPeer(app.publicId, "Synthetic caregiver", sourceId)
        app.store.addPeer(sourceId, "Synthetic family phone", app.publicId)
        val server = launch { IrohSync.serveEndpoint(endpoint, source, { true }, {}) }
        try {
            withTimeout(30_000) { endpoint.online() }
            compose.waitUntil(10_000) { MonitorService.receiving.value }
            compose.onNodeWithText("Enable background updates").assertDoesNotExist()
            compose.onNodeWithText("Pause background updates").assertDoesNotExist()
            assertFalse(MonitorService.running.value)
            assertTrue(app.backgroundSession.enabled(Role.CAREGIVER))
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 })
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val now = System.currentTimeMillis()
            source.append(sourceId, Event(0, Kind.CHECK_IN, now), now)
            withTimeout(120_000) { while (app.store.cursor(sourceId) != 1L) delay(100) }
            assertEquals(Kind.CHECK_IN, app.store.recent().first { it.source == sourceId }.event.kind)
            assertEquals("Caregiver service must never collect local events", 0L, app.store.latest(app.publicId))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Receive in background").assertIsOn().performClick()
            compose.waitUntil(10_000) { !MonitorService.receiving.value }
            assertFalse(app.backgroundSession.enabled(Role.CAREGIVER))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Receive in background").assertIsOff()
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnUiThread { MonitorService.pause(app) }
            app.store.removePeer(sourceId)
            server.cancelAndJoin()
            source.close()
        }
    }
}
