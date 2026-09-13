package family.seniorlink.monitor

import android.Manifest
import android.app.Application
import android.app.ApplicationExitInfo
import family.seniorlink.core.Role
import family.seniorlink.core.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackgroundSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun noCollectionBeforeAnApprovedConnection() {
        val session = BackgroundSession(app, UUID.randomUUID().toString())
        assertFalse(session.enableAfterConnection(Role.UNSET, true))
        assertFalse(session.enableAfterConnection(Role.SHARER, false))
        Role.entries.forEach { assertFalse(session.enabled(it)) }
    }

    @Test fun approvedConnectionEnablesEachRoleOnceIncludingExistingPairedInstallations() {
        for (role in listOf(Role.SHARER, Role.CAREGIVER)) {
            val name = UUID.randomUUID().toString()
            val session = BackgroundSession(app, name)
            assertTrue(session.enableAfterConnection(role, true))
            assertTrue(BackgroundSession(app, name).enabled(role))
            val startedAt = session.startedAt
            assertFalse(session.enableAfterConnection(role, true))
            assertEquals(startedAt, session.startedAt)
        }
    }

    @Test fun explicitPauseBeforeOrAfterPairingSurvivesMoreConnectionsAndProcessRecreation() {
        for (previouslyEnabled in listOf(false, true)) {
            val name = UUID.randomUUID().toString()
            val session = BackgroundSession(app, name)
            if (previouslyEnabled) session.enableAfterConnection(Role.SHARER, true)
            session.pause()
            val reopened = BackgroundSession(app, name)
            assertFalse(reopened.enableAfterConnection(Role.SHARER, true))
            assertFalse(reopened.enabled(Role.SHARER))
            reopened.enable(Role.SHARER)
            assertTrue(reopened.enabled(Role.SHARER))
        }
    }

    @Test fun enabledRoleSurvivesProcessRecreationAndPauseIsDurable() {
        val name = UUID.randomUUID().toString()
        val session = BackgroundSession(app, name)
        session.enable(Role.SHARER)
        val recreated = BackgroundSession(app, name)
        assertTrue(recreated.enabled(Role.SHARER))
        assertFalse(recreated.enabled(Role.CAREGIVER))
        assertTrue(recreated.startedAt > 0)
        recreated.pause()
        Role.entries.forEach { assertFalse(BackgroundSession(app, name).enabled(it)) }
        recreated.enable(Role.CAREGIVER)
        assertTrue(BackgroundSession(app, name).enabled(Role.CAREGIVER))
        assertFalse(BackgroundSession(app, name).enabled(Role.SHARER))
    }

    @Test fun backgroundLocationRecoveryRequiresSeparateGrantButStickyAndVisibleStartsDoNot() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertFalse(MonitorService.canStartLocation(app, MonitorService.ACTION_RESUME))
        assertFalse(MonitorService.canStartLocation(app, null))
        assertTrue(MonitorService.canStartLocation(app, null, stickyRestart = true))
        assertTrue(MonitorService.canStartLocation(app, MonitorService.ACTION_START))
        assertTrue(MonitorService.canStartLocation(app, MonitorService.ACTION_RESUME_VISIBLE))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(MonitorService.canStartLocation(app, MonitorService.ACTION_RESUME))
    }

    @Test fun caregiverDoesNotRequestCollectionPermissionsEvenWithStaleFeatureSettings() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(MonitorService.missingPermissions(app, Settings(role = Role.CAREGIVER,
            location = true, sms = true, wearable = true)).isEmpty())
        assertTrue(MonitorService.missingPermissions(app, Settings(role = Role.SHARER,
            location = true, sms = true)).contains(Manifest.permission.RECEIVE_SMS))
    }

    @Test fun watchdogHonorsUserStopButCanRecoverSystemKillsAndNewExplicitStarts() {
        assertTrue(BackgroundRecovery.userStopped(ApplicationExitInfo.REASON_USER_REQUESTED, 200, 100))
        assertTrue(BackgroundRecovery.userStopped(ApplicationExitInfo.REASON_USER_STOPPED, 200, 100))
        assertFalse(BackgroundRecovery.userStopped(ApplicationExitInfo.REASON_USER_REQUESTED, 200, 300))
        assertFalse(BackgroundRecovery.userStopped(ApplicationExitInfo.REASON_LOW_MEMORY, 200, 100))
        assertFalse(BackgroundRecovery.userStopped(ApplicationExitInfo.REASON_CRASH, 200, 100))
    }

    @Test fun observedAndroidStopSurvivesFurtherProcessDeathsUntilVisibleResume() {
        val name = UUID.randomUUID().toString()
        BackgroundSession(app, name).enable(Role.SHARER)
        BackgroundSession(app, name).suppressRecovery()
        val laterJobProcess = BackgroundSession(app, name)
        assertTrue(laterJobProcess.enabled(Role.SHARER))
        assertTrue(laterJobProcess.recoverySuppressed)
        laterJobProcess.allowRecovery()
        assertFalse(BackgroundSession(app, name).recoverySuppressed)
    }
}
