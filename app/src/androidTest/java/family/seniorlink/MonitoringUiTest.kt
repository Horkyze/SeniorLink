package family.seniorlink

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import family.seniorlink.core.Kind
import family.seniorlink.core.Role
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test fun visibleSharingCheckInBackgroundAndPause() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Share my information").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Start sharing").fetchSemanticsNodes().isNotEmpty()
        }
        if (app.store.settings.role == Role.UNSET) compose.onNodeWithText("Share my information").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Start sharing").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(Role.SHARER, app.store.settings.role)
        assertFalse(app.store.settings.sms)
        assertFalse(app.store.settings.smsBodies)
        assertFalse(app.store.settings.location)
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        try {
            compose.onNodeWithText("Start sharing").performClick()
            compose.waitUntil(10_000) { MonitorService.running.value }
            compose.onNodeWithText("I'm okay — check in").performClick()
            compose.waitUntil(10_000) { app.store.recent().any { it.event.kind == Kind.CHECK_IN } }
            compose.onNodeWithText("OK").performClick()
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 })
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue("Sharing must outlive the sharing screen", MonitorService.running.value)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Pause sharing").performClick()
            compose.waitUntil(10_000) { !MonitorService.running.value }
            compose.onNodeWithText("Start sharing").assertExists()
        } finally {
            compose.activity.stopService(android.content.Intent(app, MonitorService::class.java))
        }
    }
}
