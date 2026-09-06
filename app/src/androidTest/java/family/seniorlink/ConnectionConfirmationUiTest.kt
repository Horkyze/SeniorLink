package family.seniorlink

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.view.WindowManager
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import family.seniorlink.pairing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionConfirmationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sharerComparesCodeAndConfirmsExactlyTheDisplayedRequest() {
        val state = mutableStateOf(PairingState(PairingStep.VERIFY, hosting = true,
            verification = "K7MP", peerName = "Anna", requestId = "exact-request"))
        var approved: String? = null
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PairingDialog(state.value, {
                    approved = it
                    state.value = state.value.copy(step = PairingStep.SAVING)
                }, { state.value = state.value.copy(step = PairingStep.DECLINED) }, {})
                }
            } }
        }
        compose.onNodeWithText("K7MP").assertExists()
        compose.onNodeWithText("Anna wants to connect as your caregiver.").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("K7MP").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Verification code clips at large font size", layouts.single().hasVisualOverflow)
        val confirmBounds = compose.onNodeWithText("Codes match — connect").fetchSemanticsNode().boundsInRoot
        val declineBounds = compose.onNodeWithText("Codes don't match").fetchSemanticsNode().boundsInRoot
        assertTrue("Confirmation and rejection overlap", confirmBounds.bottom <= declineBounds.top)
        screenshot("pairing-sharer-large-font")
        assertNull(approved)
        compose.onNodeWithText("Codes match — connect").performClick()
        assertEquals("exact-request", approved)
        compose.onNodeWithText("Finishing the connection…").assertExists()
        compose.onNodeWithText("You're connected").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test fun caregiverWaitsForSharerAndCanRejectMismatchedCode() {
        val state = mutableStateOf(PairingState(PairingStep.VERIFY, verification = "K7MP", peerName = "Grandad"))
        var rejected = false
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent { MaterialTheme {
                PairingDialog(state.value, { fail("Caregiver cannot grant sharing consent") }, {
                    rejected = true
                    state.value = state.value.copy(step = PairingStep.DECLINED)
                }, {})
            } }
        }
        compose.onNodeWithText("K7MP").assertExists()
        compose.onNodeWithText("Waiting for confirmation on the sharing phone…").assertExists()
        compose.onNodeWithText("Codes match — connect").assertDoesNotExist()
        screenshot("pairing-caregiver")
        compose.onNodeWithText("Codes don't match").performClick()
        assertTrue(rejected)
        compose.onNodeWithText("Connection declined").assertExists()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
