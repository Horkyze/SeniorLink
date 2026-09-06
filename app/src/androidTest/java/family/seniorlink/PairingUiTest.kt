package family.seniorlink

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.client.android.Intents as ScanIntents
import family.seniorlink.core.Pairing
import family.seniorlink.core.Role
import family.seniorlink.pairing.PairingScannerActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real activity-result delivery and persistence, with synthetic scan results. */
@RunWith(AndroidJUnit4::class)
class PairingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun simpleRoleSpecificSetupAndCameraFallback() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Share my information").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Phones").fetchSemanticsNodes().isNotEmpty()
        }
        if (app.store.settings.role == Role.UNSET) {
            val caregiver = InstrumentationRegistry.getArguments().getString("pairingRole") == "CAREGIVER"
            compose.onNodeWithText(if (caregiver) "I'm a caregiver" else "Share my information").performClick()
        }
        compose.onNodeWithText("Phones").performClick()
        val before = app.store.peers()
        compose.onNodeWithText("Your name (optional)").performTextInput("Anna")
        if (app.store.settings.role == Role.SHARER) {
            compose.onAllNodesWithText("Connect a caregiver").filter(hasClickAction()).onFirst().performScrollTo().performClick()
            compose.waitUntil(40_000) {
                compose.onAllNodesWithContentDescription("Connection QR code").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Show this QR to the caregiver").assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Connection QR code").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Your name (optional)").assertTextContains("Anna")
            compose.onNodeWithText("Scan QR").assertDoesNotExist()
        } else {
            scan(Activity.RESULT_CANCELED)
            compose.onNodeWithText("Your name (optional)").assertTextContains("Anna")
            scan(Activity.RESULT_OK, "https://example.com/unrelated")
            compose.onNodeWithText("Scan the QR shown under Connect a caregiver on the sharing phone.").assertExists()
            compose.onNodeWithText("OK").performClick()
            scan(Activity.RESULT_OK, Pairing.code("b".repeat(64)))
            compose.onNodeWithText("That is an older QR code. Update SeniorLink on both phones, then tap Connect a caregiver for a new one.").assertExists()
            compose.onNodeWithText("OK").performClick()
            scan(Activity.RESULT_CANCELED, missingPermission = true)
            compose.onNodeWithText("Open camera permission settings").assertExists()
            compose.onNodeWithText("Use a shared invitation instead").performScrollTo().performClick()
            compose.onNodeWithText("Connection invitation").performTextInput("draft")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Connection invitation").assertTextContains("draft")
            compose.onNodeWithText("Your name (optional)").assertTextContains("Anna")
        }
        assertEquals(before, app.store.peers())
    }

    private fun scan(resultCode: Int, text: String? = null, missingPermission: Boolean = false) {
        Intents.init()
        try {
            val result = Intent().apply {
                text?.let { putExtra(ScanIntents.Scan.RESULT, it) }
                putExtra(ScanIntents.Scan.MISSING_CAMERA_PERMISSION, missingPermission)
            }
            val target = hasComponent(PairingScannerActivity::class.java.name)
            Intents.intending(target).respondWith(ActivityResult(resultCode, result))
            compose.onNodeWithText("Scan QR").performScrollTo().performClick()
            compose.waitForIdle()
            Intents.intended(target)
        } finally {
            Intents.release()
        }
    }
}
