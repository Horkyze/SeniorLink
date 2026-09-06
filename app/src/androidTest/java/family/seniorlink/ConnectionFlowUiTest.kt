package family.seniorlink

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import computer.iroh.SecretKey
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.pairing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real UI approval/rejection, real iroh signatures/streams, synthetic caregiver. */
@RunWith(AndroidJUnit4::class)
class ConnectionFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun incomingRequestAppearsWithoutRescanAndBothPhonesFinishAfterOneConfirmation() {
        val app = compose.activity.application as SeniorApp
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Share my information").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Phones").fetchSemanticsNodes().isNotEmpty()
        }
        if (app.store.settings.role == Role.UNSET) compose.onNodeWithText("Share my information").performClick()
        compose.waitUntil(10_000) { app.store.settings.role != Role.UNSET }
        assumeTrue(app.store.settings.role == Role.SHARER)
        compose.onNodeWithText("Phones").performClick()
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        var lastInvitation = ""
        try {
            for (accept in listOf(false, true)) {
                compose.onAllNodesWithText("Connect a caregiver").filter(hasClickAction()).onFirst().performScrollTo().performClick()
                compose.waitUntil(40_000) { model.pairing.state.value.step == PairingStep.QR }
                val qr = model.pairing.state.value.qr
                assertNotEquals(lastInvitation, qr)
                lastInvitation = qr
                val identity = SecretKey.generate().use { it.toBytes() }
                val key = IrohPairingKeys(identity)
                Store(app, "ui-caregiver-${UUID.randomUUID()}").use { store ->
                    store.updateSettings(Settings(role = Role.CAREGIVER), key.publicId)
                    val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val remote = PairingController(remoteScope, store) { identity }
                    compose.runOnUiThread { remote.connect(qr, "Anna", key.publicId) }
                    try {
                        compose.waitUntil(20_000) { model.pairing.state.value.step == PairingStep.VERIFY && remote.state.value.step == PairingStep.VERIFY }
                        val code = remote.state.value.verification
                        assertEquals(code, model.pairing.state.value.verification)
                        compose.onNodeWithText(code).assertExists()
                        assertFalse(app.store.approved(key.publicId))
                        assertTrue(store.peers().isEmpty())
                        compose.activityRule.scenario.recreate()
                        compose.onNodeWithText(code).assertExists()
                        compose.onNodeWithText(if (accept) "Codes match — connect" else "Codes don't match").performClick()
                        compose.waitUntil(20_000) {
                            val expected = if (accept) PairingStep.CONNECTED else PairingStep.DECLINED
                            model.pairing.state.value.step == expected && remote.state.value.step == expected
                        }
                        assertEquals(accept, app.store.approved(key.publicId))
                        assertEquals(accept, store.approved(app.publicId))
                        compose.onNodeWithText("Done").performClick()
                    } finally {
                        runBlocking { remoteScope.coroutineContext[Job]!!.cancelAndJoin() }
                        app.store.removePeer(key.publicId)
                    }
                }
            }
        } finally {
            compose.runOnUiThread { model.pairing.reset() }
        }
    }
}
