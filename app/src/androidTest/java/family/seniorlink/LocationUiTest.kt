package family.seniorlink

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.location.LocationContent
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = System.currentTimeMillis()
    private val grandfather = "a".repeat(64)
    private val grandmother = "b".repeat(64)
    private fun fix(source: String, sequence: Long, lat: Double, lon: Double) = StoredEvent(source,
        Event(sequence, Kind.LOCATION, now - (3 - sequence) * 900_000,
            latitude = lat, longitude = lon, accuracy = 12f))
    private val state get() = ScreenState(ready = true, settings = Settings(role = Role.CAREGIVER),
        peers = listOf(Peer(grandfather, "Grandad", lastContact = now), Peer(grandmother, "Grandma", lastContact = now)),
        locations = listOf(fix(grandfather, 3, 48.14860, 17.10770), fix(grandfather, 2, 48.14350, 17.10970),
            fix(grandfather, 1, 48.14210, 17.10040), fix(grandmother, 1, 48.15000, 17.12000)))

    @Test fun embeddedMapSelectsHistoryAndSeparatesPhones() {
        show(state)
        compose.onNodeWithTag("location-map").assertExists()
        compose.onNodeWithText("3 saved locations • newest first. Tap a location to show it on the map.").assertExists()
        compose.onNodeWithText("Previous location 1").performScrollTo().performClick()
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.14350, 17.10970", substring = true)
        compose.onNodeWithText("Previous location", substring = false).assertExists()
        screenshot("location-history-selected")
        compose.onNodeWithText("Latest location", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.14860, 17.10770", substring = true)
        compose.onNodeWithText("Show history").performScrollTo().performClick()
        compose.onNodeWithText("© OpenStreetMap contributors").assertExists()
        compose.onNodeWithText("Grandma", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("1 saved location • newest first. Tap a location to show it on the map.").assertExists()
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.15000, 17.12000", substring = true)
        compose.onNodeWithText("Previous location 1").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("location-map").assertExists()
        compose.onNodeWithText("Grandad", substring = false).performScrollTo().performClick()
        screenshot("location-latest")
    }

    @Test fun emptyStatesExplainHowToGetLocationWithoutCreatingAMap() {
        show(state.copy(locations = emptyList()))
        compose.onNodeWithText("No location yet").assertIsDisplayed()
        compose.onNodeWithText("No location received from Grandad.", substring = true).assertExists()
        compose.onNodeWithTag("location-map").assertDoesNotExist()
        show(ScreenState(ready = true, publicId = grandfather, settings = Settings(role = Role.SHARER)))
        compose.onNodeWithText("Turn on Share location", substring = true).assertExists()
        compose.onNodeWithText("Location settings").assertExists()
        compose.onNodeWithTag("location-map").assertDoesNotExist()
    }

    @Test fun removalOfSelectedPhoneClearsItsMapAndHistory() {
        val visible = mutableStateOf(state)
        showContent { visible.value }
        compose.onNodeWithText("Grandma", substring = false).performScrollTo().performClick()
        compose.runOnUiThread { visible.value = state.copy(peers = listOf(state.peers.first()), locations = state.locations.filter { it.source == grandfather }) }
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.14860, 17.10770", substring = true)
        compose.onNodeWithText("Grandma").assertDoesNotExist()
        compose.runOnUiThread { visible.value = state.copy(peers = emptyList(), locations = emptyList()) }
        compose.onNodeWithTag("location-map").assertDoesNotExist()
        compose.onNodeWithText("No location yet").assertExists()
    }

    @Test fun openingAnOlderUpdateDoesNotSelectAnotherPhonesMatchingSequence() {
        val sharedSequences = state.copy(locations = state.locations + listOf(
            fix(grandmother, 2, 48.15100, 17.12100), fix(grandmother, 3, 48.15200, 17.12200)))
        showContent(grandfather, 2) { sharedSequences }
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.14350, 17.10970", substring = true)
        compose.onNodeWithText("Grandma", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.15200, 17.12200", substring = true)
    }

    @Test fun offlineMapKeepsHistoryAndSelectionAvailable() {
        val wifi = shell("settings get global wifi_on").trim()
        val mobile = shell("settings get global mobile_data").trim()
        try {
            show(state)
            shell("svc wifi disable")
            shell("svc data disable")
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("You're offline.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Previous location 1").performScrollTo().performClick()
            compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.14350, 17.10970", substring = true)
            compose.onNodeWithText("Retry map").assertExists()
            screenshot("location-offline")
        } finally {
            shell("svc wifi ${if (wifi == "0") "disable" else "enable"}")
            shell("svc data ${if (mobile == "0") "disable" else "enable"}")
        }
    }

    private fun show(state: ScreenState) = showContent { state }
    private fun showContent(initialSource: String? = null, initialSequence: Long? = null, state: () -> ScreenState) {
        compose.runOnUiThread {
            // Only synthetic data; production continues to prohibit screenshots.
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent {
                MaterialTheme {
                    val scroll = remember { ScrollState(0) }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(scroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LocationContent(state(), false, initialSource, initialSequence, {}, { scope.launch { scroll.animateScrollTo(0) } })
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Loading map…").fetchSemanticsNodes().isEmpty() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
