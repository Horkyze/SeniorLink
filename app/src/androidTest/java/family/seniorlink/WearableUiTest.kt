package family.seniorlink

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.wearable.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WearableUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = System.currentTimeMillis()
    private val grandfather = "a".repeat(64)
    private val grandmother = "b".repeat(64)
    private fun event(source: String, sequence: Long, metric: WearableMetric, value: Double, at: Long) = StoredEvent(source,
        Event(sequence, Kind.WEARABLE, at, wearable = WearableSummary("Galaxy Fit3", listOf(
            WearableMetricSummary(metric, at, at, 1, value, value, value, value)))))
    private val state get() = ScreenState(ready = true, settings = Settings(role = Role.CAREGIVER),
        peers = listOf(Peer(grandfather, "Grandad"), Peer(grandmother, "Grandma")),
        wearables = listOf(event(grandfather, 1, WearableMetric.HEART_RATE, 72.0, now - 600_000),
            event(grandfather, 2, WearableMetric.BATTERY, 80.0, now),
            event(grandmother, 1, WearableMetric.HEART_RATE, 84.0, now)))

    @Test fun caregiverSeesPerMetricFreshnessAndSeparateFamilyReadings() {
        val visible = mutableStateOf(state)
        show { WearableContent(visible.value, WearableState(), {}) }
        compose.onNodeWithText("Heart rate: 72 bpm").assertExists()
        compose.onNodeWithText("No recent reading", substring = true).assertExists()
        compose.onNodeWithText("Battery: 80 %").assertExists()
        screenshot("wearable-caregiver")
        compose.onNodeWithText("Grandma").performClick()
        compose.onNodeWithText("Heart rate: 84 bpm").assertExists()
        compose.onNodeWithText("Heart rate: 72 bpm").assertDoesNotExist()
        compose.runOnUiThread { visible.value = state.copy(peers = emptyList(), wearables = emptyList()) }
        compose.onNodeWithText("Heart rate: 84 bpm").assertDoesNotExist()
        compose.onNodeWithText("No wearable readings yet.", substring = true).assertExists()
    }

    @Test fun contactLossDoesNotDisplayOldPulseAsCurrent() {
        show { WearableContent(state.copy(wearables = state.wearables +
            event(grandfather, 3, WearableMetric.CONTACT, 0.0, now)), WearableState(), {}) }
        compose.onNodeWithText("Heart rate: 72 bpm").assertDoesNotExist()
        compose.onNodeWithText("Sensor contact: Not detected").assertExists()
    }

    @Test fun aReplacementBandWithTheSameNameDoesNotInheritOldReadings() {
        val changed = state.copy(wearables = state.wearables.map { stored ->
            stored.copy(event = stored.event.copy(wearable = stored.event.wearable!!.copy(
                deviceId = if (stored.event.sequence == 2L) "new-band" else "old-band")))
        })
        show { WearableContent(changed, WearableState(), {}) }
        compose.onNodeWithText("Heart rate: 72 bpm").assertDoesNotExist()
        compose.onNodeWithText("Battery: 80 %").assertExists()
    }

    @Test fun sharingScreenShowsCapabilitiesAndExplainsMissingData() {
        val sharer = ScreenState(ready = true, publicId = grandfather,
            settings = Settings(role = Role.SHARER, wearable = true, wearableName = "Galaxy Fit3", wearableAddress = "AA:BB:CC:DD:EE:FF"))
        val live = WearableState(connected = true, status = "Connected; collecting wearable readings", lastReceivedAt = now,
            latest = listOf(LiveWearableValue(WearableMetric.HEART_RATE, 72.0, now), LiveWearableValue(WearableMetric.CONTACT, 1.0, now)),
            capabilities = listOf("Heart rate: listening", "Firmware: read successfully"),
            information = mapOf("Firmware" to "Test firmware"), services = listOf("180d / 2a37 (notify)"))
        show { WearableContent(sharer, live, {}) }
        compose.onNodeWithText("Heart rate: 72 bpm").assertExists()
        screenshot("wearable-sharing")
        compose.onNodeWithText("Show Bluetooth services").performScrollTo().performClick()
        compose.onNodeWithText("180d / 2a37 (notify)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sleep, steps, stress, ECG", substring = true).assertExists()
    }

    @Test fun settingsRequireExplicitEnableAndRespectRunningLock() {
        var draft by mutableStateOf(Settings(role = Role.SHARER))
        var editable by mutableStateOf(true)
        val scanner = WearableScanner(compose.activity.applicationContext)
        show { WearableSettings(draft, editable, scanner) { draft = it } }
        compose.onNodeWithText("Share wearable readings").performClick()
        assertTrue(draft.wearable)
        compose.runOnUiThread { editable = false }
        compose.onNodeWithText("Share wearable readings").assertIsNotEnabled()
        assertTrue(draft.wearable)
        assertFalse(scanner.state.value.scanning)
        screenshot("wearable-settings")
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            // Only synthetic data is rendered by this test.
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF14665B))) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
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
