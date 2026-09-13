package family.seniorlink.net

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkRecoveryTest {
    @Test fun callbacksHandleValidationBlockingHandoverAndDozeMaintenance() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val states = mutableListOf<NetworkWindow>()
        val job = backgroundScope.launch { networkWindows(app).collect { states += it } }
        runCurrent()
        val callback = connectivity.networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(1)
        val mobile = ShadowNetwork.newInstance(2)
        val valid = NetworkCapabilities().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        callback.onAvailable(wifi)
        runCurrent()
        assertFalse(states.last().permitted)
        callback.onCapabilitiesChanged(wifi, valid)
        runCurrent()
        assertTrue(states.last().permitted)
        callback.onBlockedStatusChanged(wifi, true)
        runCurrent()
        assertFalse(states.last().permitted)
        callback.onBlockedStatusChanged(wifi, false)
        runCurrent()
        assertTrue(states.last().permitted)
        callback.onAvailable(mobile)
        callback.onCapabilitiesChanged(mobile, valid)
        callback.onLost(wifi)
        runCurrent()
        assertEquals(NetworkWindow(mobile, true), states.last())

        fun idleChanged() {
            app.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
        }
        power.setIsDeviceIdleMode(true)
        idleChanged()
        runCurrent()
        assertFalse(states.last().permitted)
        power.setIsDeviceIdleMode(false) // Android exposes maintenance as a non-idle window.
        idleChanged()
        runCurrent()
        assertTrue(states.last().permitted)
        power.setIsDeviceIdleMode(true)
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        idleChanged()
        runCurrent()
        assertTrue(states.last().permitted)
        callback.onLost(mobile)
        runCurrent()
        assertFalse(states.last().permitted)
        job.cancel()
        runCurrent()
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }
}
