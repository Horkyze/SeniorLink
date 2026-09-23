package family.seniorlink

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.*
import family.seniorlink.core.mailbox.*
import family.seniorlink.mailbox.MailboxSettings
import family.seniorlink.monitor.MonitorService
import family.seniorlink.ui.CalmTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MailboxSettingsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun configurationAndExplicitConsentPersistWithoutCollectingCaregiverData() {
        val model=ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { model.screen.value.ready }
        val app=model.app
        assumeTrue("Requires an explicitly selected isolated test installation", InstrumentationRegistry.getArguments().getString("seniorlink.mailboxFixture")=="true" && app.store.settings.role==Role.UNSET)
        val peer="b".repeat(64)
        app.store.updateSettings(Settings(role=Role.CAREGIVER),app.publicId)
        app.store.addPeer(peer,"Synthetic sharing phone",app.publicId)
        compose.waitUntil(15_000) { model.screen.value.peers.isNotEmpty() }
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent { CalmTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                val state by model.screen.collectAsState()
                MailboxSettings(state,model)
            } } } }
        }
        val config=MailboxService("https://mailbox.example","c".repeat(64))
        compose.onNodeWithText("Mailbox setup code").performTextInput("seniorlink-mailbox:"+MailboxWire.b64(MailboxWire.encode(config)))
        compose.onNodeWithText("Configure mailbox").performScrollTo().performClick()
        compose.waitUntil(10_000) { app.store.mailbox.service()==config }
        val toggle=compose.onNodeWithContentDescription("Queued delivery for Synthetic sharing phone")
        toggle.performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { app.store.mailbox.enabled(peer) }
        toggle.assertIsOn()
        compose.onNodeWithText("Mailbox deletion pending.",substring=true).assertDoesNotExist()
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null),"mailbox-settings.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        toggle.performClick()
        compose.waitUntil(10_000) { !app.store.mailbox.enabled(peer) }
        assertEquals(0L,app.store.latest(app.publicId))
        assertFalse(MonitorService.running.value)
        compose.runOnUiThread { MonitorService.pause(app) }
        app.store.removePeer(peer)
    }
}
