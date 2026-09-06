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

    @Test fun scanReviewCancelValidationAndApproval() {
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
        val addLabel = if (app.store.settings.role == Role.SHARER) "Approve phone" else "Add phone"
        val approval = if (app.store.settings.role == Role.SHARER)
            "I approve this phone to read enabled information, including retained history"
        else "I verified this is the sharing phone's code"
        val other = "b".repeat(64)
        val replacement = "c".repeat(64)
        val before = app.store.peers()
        try {
            compose.onNodeWithText("Show my QR code").performClick()
            compose.onNodeWithContentDescription("Public pairing code QR").assertExists()
            compose.onNodeWithText(Pairing.code(app.publicId)).assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Phone name").performTextInput("Test caregiver")
            scan(Activity.RESULT_OK, Pairing.code(other))
            compose.onNodeWithText("Other phone's pairing code").assertTextContains(Pairing.code(other))
            compose.onNodeWithText(addLabel).performScrollTo().assertIsNotEnabled()
            assertEquals(before, app.store.peers())

            compose.onNodeWithText(approval).performScrollTo().performClick()
            compose.onNodeWithText(addLabel).assertIsEnabled()
            // Cancel, unrelated QR, and self-scan neither replace the draft nor write peers.
            scan(Activity.RESULT_CANCELED)
            compose.onNodeWithText("Other phone's pairing code").assertTextContains(Pairing.code(other))
            scan(Activity.RESULT_OK, "https://example.com/not-a-pairing-code")
            compose.onNodeWithText("Scan or paste the full SeniorLink public pairing code.").assertExists()
            compose.onNodeWithText("OK").performClick()
            scan(Activity.RESULT_OK, Pairing.code(app.publicId))
            compose.onNodeWithText("This is your own code. Scan or paste the other phone's code.").assertExists()
            compose.onNodeWithText("OK").performClick()
            assertEquals(before, app.store.peers())

            scan(Activity.RESULT_CANCELED, missingPermission = true)
            compose.onNodeWithText("Open camera permission settings").assertExists()
            compose.onNodeWithText("Other phone's pairing code").assertTextContains(Pairing.code(other))
            scan(Activity.RESULT_CANCELED)
            compose.onNodeWithText("Open camera permission settings").assertDoesNotExist()
            // A new scan clears previous approval, even after returning from another Activity.
            scan(Activity.RESULT_OK, Pairing.code(replacement))
            compose.onNodeWithText(addLabel).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(approval).performScrollTo().performClick()
            compose.onNodeWithText("Other phone's pairing code").performTextReplacement(Pairing.code(other))
            compose.onNodeWithText(addLabel).assertIsNotEnabled()
            compose.onNodeWithText(approval).performScrollTo().performClick()

            // Android may recreate the form while the camera is open.
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Phone name").assertTextContains("Test caregiver")
            compose.onNodeWithText("Other phone's pairing code").assertTextContains(Pairing.code(other))
            compose.onNodeWithText(addLabel).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { app.store.approved(other) }
            compose.onNodeWithText("OK").performClick()
            assertEquals("Test caregiver", app.store.peers().single { it.id == other }.name)
        } finally {
            if (before.none { it.id == other }) app.store.removePeer(other)
        }
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
            compose.onNodeWithText("Scan other phone's QR").performScrollTo().performClick()
            compose.waitForIdle()
            Intents.intended(target)
        } finally {
            Intents.release()
        }
    }
}
