package family.seniorlink.wearable

import android.Manifest
import android.app.Application
import family.seniorlink.core.Role
import family.seniorlink.core.Settings
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BleAccessTest {
    @Test fun `modern discovery requests Nearby devices without location and monitoring needs only connect`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), BleAccess.missingScanPermissions(app).toSet())
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), BleAccess.missingConnectionPermissions(app))
        assertFalse(MonitorService.missingPermissions(app, Settings(role = Role.SHARER)).contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(MonitorService.missingPermissions(app, Settings(role = Role.SHARER, wearable = true)).contains(Manifest.permission.BLUETOOTH_CONNECT))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(BleAccess.missingConnectionPermissions(app).isEmpty())
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), BleAccess.missingScanPermissions(app))
    }

    @Test @Config(sdk = [30]) fun `legacy scanning needs fine location but reconnecting a selected device does not`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleAccess.missingScanPermissions(app))
        assertTrue(BleAccess.missingConnectionPermissions(app).isEmpty())
    }
}
