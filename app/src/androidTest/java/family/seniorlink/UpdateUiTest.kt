package family.seniorlink

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import family.seniorlink.updates.AppUpdate
import family.seniorlink.updates.UpdateDialog
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val update = AppUpdate("0.1.10", "https://github.com/Horkyze/SeniorLink/releases/tag/v0.1.10")

    @Before fun setUp() {
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
    }
    @After fun tearDown() { Intents.release() }

    @Test fun opensDetectedReleasePageOnlyAfterUserAccepts() {
        showPrompt()
        compose.onNodeWithText("Update SeniorLink?").assertIsDisplayed()
        compose.onNodeWithText("Version 0.1.10 is available. You have ${BuildConfig.VERSION_NAME}.").assertIsDisplayed()
        screenshot("update-prompt")
        Intents.assertNoUnverifiedIntents()
        compose.onNodeWithText("View release").performClick()
        Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(update.releaseUrl)))
        compose.onNodeWithText("Update SeniorLink?").assertDoesNotExist()
    }

    @Test fun laterDismissesWithoutOpeningReleasePage() {
        showPrompt()
        compose.onNodeWithText("View release").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        screenshot("update-prompt-later")
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update SeniorLink?").assertDoesNotExist()
        Intents.assertNoUnverifiedIntents()
    }

    @Test fun missingBrowserKeepsPromptAndProvidesCopyableLink() {
        showPrompt(canOpenRelease = false)
        compose.onNodeWithText("View release").performClick()
        compose.onNodeWithText("Update SeniorLink?").assertIsDisplayed()
        compose.onNodeWithText(update.releaseUrl).assertExists()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update SeniorLink?").assertDoesNotExist()
        Intents.assertNoUnverifiedIntents()
    }

    private fun showPrompt(canOpenRelease: Boolean = true) {
        val visible = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val context = if (canOpenRelease) compose.activity else object : ContextWrapper(compose.activity) {
                override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
            }
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalContext provides context) {
                        if (visible.value) UpdateDialog(update) { visible.value = false }
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
