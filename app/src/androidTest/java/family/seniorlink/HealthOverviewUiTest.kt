package family.seniorlink

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.*
import family.seniorlink.dashboard.HealthOverview
import family.seniorlink.data.StoredEvent
import family.seniorlink.wearable.WearableState
import org.junit.Assert.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import family.seniorlink.ui.CalmTheme
import family.seniorlink.updates.UpdateViewModel
import family.seniorlink.updates.UpdateCheckResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HealthOverviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val source = "a".repeat(64)
    private val other = "b".repeat(64)
    private val now = System.currentTimeMillis()
    private fun metric(metric: WearableMetric, value: Double, at: Long) = WearableMetricSummary(metric, at, at, 1, value, value, value, value)
    private fun state(): ScreenState {
        val rates = listOf(70.0, 73.0, 72.0, 75.0, 76.0, 74.0, 71.0, 72.0, 74.0, 73.0,
            70.0, 71.0, 75.0, 76.0, 74.0, 72.0, 75.0, 79.0, 81.0, 77.0,
            70.0, 73.0, 72.0, 78.0, 83.0, 76.0, 74.0, 71.0, 75.0, 72.0)
        return ScreenState(ready = true, settings = Settings(role = Role.CAREGIVER),
            peers = listOf(Peer(source, "Grandad", lastContact = now - 60_000), Peer(other, "Grandma")),
            phoneBatteries = listOf(StoredEvent(source, Event(40, Kind.PHONE_BATTERY, now - 60_000, phoneBattery = PhoneBattery(64, true)))),
            wearables = rates.mapIndexed { index, bpm ->
                val at = now - (rates.size - index) * 120_000
                StoredEvent(source, Event(index + 1L, Kind.WEARABLE, at, wearable = WearableSummary("Galaxy Fit3", listOf(metric(WearableMetric.HEART_RATE, bpm, at)), deviceId = "watch")))
            } + StoredEvent(source, Event(31, Kind.WEARABLE, now - 60_000,
                wearable = WearableSummary("Galaxy Fit3", listOf(metric(WearableMetric.BATTERY, 18.0, now - 60_000)), deviceId = "watch")))
        )
    }

    @Test fun calmNavigationReachesEveryScreenAndKeepsSelectedFamily() {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { model.screen.value.ready }
        val original = model.screen.value
        val viewModels = ViewModelStore()
        try {
            compose.runOnUiThread {
                model.screen.value = state().copy(peers = state().peers.take(1))
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel { UpdateCheckResult.UpToDate } as T
                }
                val updates = ViewModelProvider(viewModels, factory)[UpdateViewModel::class.java]
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                compose.activity.setContent { CalmTheme { SeniorScreen(model, updates) } }
            }
            compose.onNodeWithText("How is Grandad?").assertIsDisplayed()
            compose.onNodeWithText("72 bpm").assertExists()
            screenshot("calm-overview")
            compose.onNodeWithText("64%").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Low battery", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("calm-device-batteries")
            compose.onNodeWithTag("navigation-1").performClick().assertIsSelected()
            compose.onNodeWithText("No location yet").assertIsDisplayed()
            compose.onNodeWithTag("navigation-4").performClick().assertIsSelected()
            compose.onNodeWithText("Wearable readings").assertIsDisplayed()
            compose.onNodeWithTag("navigation-2").performClick().assertIsSelected()
            compose.onNodeWithText("Connect to your family").assertIsDisplayed()
            compose.onNodeWithTag("navigation-3").performClick().assertIsSelected()
            compose.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
            screenshot("calm-settings")
            compose.onNodeWithTag("navigation-0").performClick().assertIsSelected()
            compose.runOnUiThread { model.screen.value = state() }
            compose.onNode(hasText("Grandma") and hasClickAction()).performClick()
            compose.onNodeWithText("How is Grandma?").assertExists()
            compose.onNodeWithText("72 bpm").assertDoesNotExist()
            compose.onNodeWithTag("navigation-4").performClick()
            compose.onNode(hasText("Grandma") and hasClickAction()).assertIsSelected()
            compose.onNodeWithText("Heart rate: 72 bpm").assertDoesNotExist()
            compose.onNodeWithTag("navigation-3").performClick()
            compose.onNodeWithTag("navigation-0").performClick()
            compose.onNodeWithText("How is Grandma?").assertExists()
            compose.runOnUiThread { model.screen.value = state().copy(peers = emptyList()) }
            compose.onNodeWithText("Your family updates").assertExists()
            compose.onNodeWithText("64%").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { viewModels.clear(); model.screen.value = original }
        }
    }

    @Test fun graphBatteryLevelsFamilySelectionAndRemoval() {
        val visible = mutableStateOf(state())
        var details = false
        show { HealthOverview(visible.value, WearableState(), { details = true }) }
        compose.onNodeWithText("72 bpm").assertExists()
        compose.onNodeWithContentDescription("Heart-rate graph", substring = true).assertExists()
        screenshot("health-graph")
        compose.onNodeWithText("24 hours").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("64%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("18%").assertExists()
        compose.onNodeWithText("Charging", substring = true).assertExists()
        compose.onNodeWithText("Low battery", substring = true).assertExists()
        compose.onNodeWithText("Charging", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("health-batteries")
        compose.onNodeWithText("Low battery", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("health-watch-battery")
        compose.onNodeWithText("View wearable details").performScrollTo().performClick()
        assertTrue(details)
        compose.onNodeWithText("Grandma").performScrollTo().performClick()
        compose.onNodeWithText("72 bpm").assertDoesNotExist()
        compose.onNodeWithText("64%").assertDoesNotExist()
        compose.onNodeWithText("18%").assertDoesNotExist()
        compose.onNodeWithText("No reading").assertExists()
        compose.onNodeWithText("Grandad").performClick()
        compose.runOnUiThread { visible.value = visible.value.copy(peers = emptyList()) }
        compose.onNodeWithText("72 bpm").assertDoesNotExist()
        compose.onNodeWithText("64%").assertDoesNotExist()
    }

    @Test fun stalePulseIsOutsideOneHourGraphButVisibleInDayHistory() {
        val old = now - 2 * 3_600_000
        val state = state().copy(wearables = listOf(StoredEvent(source, Event(1, Kind.WEARABLE, old,
            wearable = WearableSummary("Watch", listOf(metric(WearableMetric.HEART_RATE, 69.0, old)))))))
        show { HealthOverview(state, WearableState(), {}) }
        compose.onNodeWithText("69 bpm").assertExists()
        compose.onNodeWithText("No recent reading", substring = true).assertExists()
        compose.onNodeWithContentDescription("Heart-rate graph", substring = true).assertDoesNotExist()
        compose.onNodeWithText("24 hours").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Heart-rate graph, 1 saved readings", substring = true).assertExists()
    }

    @Test fun missingBatteryIsUnknownAndZeroIsARealReading() {
        val state = state().copy(phoneBatteries = listOf(StoredEvent(source,
            Event(40, Kind.PHONE_BATTERY, now, phoneBattery = PhoneBattery(0)))), wearables = emptyList())
        show { HealthOverview(state, WearableState(), {}) }
        compose.onNodeWithText("0%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("No battery reading.", substring = true).assertExists()
        screenshot("health-unknown-battery")
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent {
                CalmTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
