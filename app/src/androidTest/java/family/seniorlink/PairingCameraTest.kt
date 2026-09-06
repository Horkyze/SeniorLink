package family.seniorlink

import android.Manifest
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import family.seniorlink.pairing.PairingScannerActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingCameraTest {
    @get:Rule val camera = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun cameraClosesOnBackgroundAndCancel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = PairingScannerActivity.options().createScanIntent(context)
        ActivityScenario.launch<PairingScannerActivity>(intent).use { scenario ->
            lateinit var view: DecoratedBarcodeView
            scenario.onActivity {
                view = it.findViewById(R.id.pairing_barcode_scanner)
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            awaitPreview(scenario, view)
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("Camera must close when backgrounded", view.barcodeView.isPreviewActive)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPreview(scenario, view)
            onView(withId(R.id.cancel_scan)).perform(click())
            val deadline = System.nanoTime() + 10_000_000_000L
            while (scenario.state != Lifecycle.State.DESTROYED && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertFalse("Camera must close after Cancel", view.barcodeView.isPreviewActive)
        }
    }

    private fun awaitPreview(scenario: ActivityScenario<PairingScannerActivity>, view: DecoratedBarcodeView) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var active = false
        while (!active && System.nanoTime() < deadline) {
            scenario.onActivity { active = view.barcodeView.isPreviewActive }
            if (!active) Thread.sleep(50)
        }
        assertTrue("Camera preview did not start", active)
    }
}
