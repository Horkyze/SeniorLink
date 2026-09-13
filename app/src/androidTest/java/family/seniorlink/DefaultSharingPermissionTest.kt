package family.seniorlink

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.Role
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh isolated installation: exercises the real Android notification permission dialog. */
@RunWith(AndroidJUnit4::class)
class DefaultSharingPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun denialKeepsUpdatesPausedAndGrantContinuesSettingsStartupAutomatically() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Share my information").fetchSemanticsNodes().isNotEmpty() }
        assumeTrue("Use a fresh isolated installation without notification permission",
            !app.getSharedPreferences("background-session", 0).contains("role") &&
                MonitorService.missingPermissions(app, app.store.settings.copy(role = Role.SHARER)).isNotEmpty())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun permissionButton(id: String): AccessibilityNodeInfo? = automation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")?.firstOrNull()
        fun respond(id: String) {
            compose.waitUntil(15_000) { permissionButton(id) != null }
            assertTrue(permissionButton(id)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val peer = "c".repeat(64)
        val secondPeer = "d".repeat(64)
        try {
            compose.onNodeWithText("Share my information").performClick()
            compose.waitUntil(10_000) { app.store.settings.role == Role.SHARER }
            app.store.addPeer(peer, "Synthetic caregiver", app.publicId)
            respond("permission_deny_button")
            compose.waitUntil(10_000) { !app.backgroundSession.enabled(Role.SHARER) }
            assertFalse(MonitorService.running.value)
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Sharing").assertIsOff()
            app.store.addPeer(secondPeer, "Another synthetic caregiver", app.publicId)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Sharing").assertIsOff()
            assertFalse(MonitorService.running.value)
            compose.onNodeWithText("Sharing").performClick()
            respond("permission_allow_button")
            compose.waitUntil(10_000) { MonitorService.running.value }
            compose.onNodeWithText("Sharing").assertIsOn()
        } finally {
            compose.runOnUiThread { MonitorService.pause(app) }
            app.store.removePeer(peer)
            app.store.removePeer(secondPeer)
        }
    }
}
