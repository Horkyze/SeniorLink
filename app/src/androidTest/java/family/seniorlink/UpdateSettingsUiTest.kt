package family.seniorlink

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import family.seniorlink.core.Role
import family.seniorlink.core.Settings
import family.seniorlink.updates.*
import kotlinx.coroutines.CompletableDeferred
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private var generation = 0
    private var original: ScreenState? = null
    private var main: MainViewModel? = null
    private val update = AppUpdate("0.1.10", "https://github.com/Horkyze/SeniorLink/releases/download/v0.1.10/SeniorLink-0.1.10-debug.apk")

    @Before fun setUp() {
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun tearDown() {
        compose.runOnUiThread {
            store.clear()
            original?.let { main?.screen?.value = it }
        }
        Intents.release()
    }

    @Test fun settingsShowsInstalledVersionAndManualCheckInBothRoles() {
        for (role in listOf(Role.SHARER, Role.CAREGIVER)) {
            var calls = 0
            showSettings(role) { calls++; UpdateCheckResult.UpToDate }
            compose.onNodeWithText("Current version: ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
            compose.onNodeWithText("Check for updates").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("You're up to date.").assertExists()
            assertEquals(1, calls)
            screenshot("update-settings-${role.name.lowercase()}")
        }
        Intents.assertNoUnverifiedIntents()
    }

    @Test fun manualCheckOpensExistingUpdatePromptAndOnlyDownloadsAfterAccepting() {
        showSettings(Role.CAREGIVER) { UpdateCheckResult.Available(update) }
        compose.onNodeWithText("Update SeniorLink?").assertDoesNotExist()
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        compose.onNodeWithText("Update SeniorLink?").assertIsDisplayed()
        Intents.assertNoUnverifiedIntents()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update SeniorLink?").assertDoesNotExist()
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        compose.onNodeWithText("Download update").performClick()
        Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(update.downloadUrl)))
    }

    @Test fun pendingCheckDisablesButtonAndFailureAllowsRetry() {
        val response = CompletableDeferred<UpdateCheckResult>()
        var calls = 0
        showSettings(Role.SHARER) {
            if (calls++ == 0) response.await() else UpdateCheckResult.UpToDate
        }
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        compose.onNodeWithText("Check for updates").assertIsNotEnabled()
        compose.onNodeWithText("Checking for updates…").assertExists()
        response.complete(UpdateCheckResult.Failed)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Couldn't check for updates.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("You're up to date.").assertDoesNotExist()
        compose.onNodeWithText("Check for updates").assertIsEnabled()
        screenshot("update-settings-failure")
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        compose.onNodeWithText("You're up to date.").assertExists()
        assertEquals(2, calls)
        Intents.assertNoUnverifiedIntents()
    }

    private fun showSettings(role: Role, check: suspend () -> UpdateCheckResult) {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        main = model
        compose.waitUntil(15_000) { model.screen.value.ready }
        compose.runOnUiThread {
            if (original == null) original = model.screen.value
            // Only replace visible state; preserve the emulator's actual settings/history.
            model.screen.value = ScreenState(ready = true, publicId = model.screen.value.publicId, settings = Settings(role = role))
            var startup = true
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel {
                    if (startup) { startup = false; UpdateCheckResult.UpToDate } else check()
                } as T
            }
            val updates = ViewModelProvider(store, factory)["settings-${generation++}", UpdateViewModel::class.java]
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent { MaterialTheme { SeniorScreen(model, updates) } }
        }
        compose.onNodeWithText("Settings", substring = false).performClick()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
