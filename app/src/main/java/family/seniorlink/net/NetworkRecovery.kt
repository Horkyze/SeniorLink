package family.seniorlink.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class NetworkWindow(val network: Network?, val permitted: Boolean)

/** React to real network/idle changes instead of repeatedly dialing a sleeping/offline phone. */
internal fun networkWindows(context: Context): Flow<NetworkWindow> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    val guard = Any()
    var network: Network? = null
    var validated = false
    var blocked = false
    fun publish() = synchronized(guard) {
        val idle = power.isDeviceIdleMode || (Build.VERSION.SDK_INT >= 33 && power.isDeviceLightIdleMode)
        trySend(NetworkWindow(network, network != null && validated && !blocked &&
            (!idle || power.isIgnoringBatteryOptimizations(context.packageName))))
        Unit
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) = synchronized(guard) {
            network = available
            validated = false
            blocked = false
            publish()
        }
        override fun onLost(lost: Network) = synchronized(guard) {
            if (network == lost) { network = null; validated = false }
            publish()
        }
        override fun onCapabilitiesChanged(changed: Network, caps: NetworkCapabilities) = synchronized(guard) {
            if (network == changed) validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            publish()
        }
        override fun onBlockedStatusChanged(changed: Network, isBlocked: Boolean) = synchronized(guard) {
            if (network == changed) blocked = isBlocked
            publish()
        }
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = publish()
    }
    connectivity.registerDefaultNetworkCallback(callback)
    try {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    } catch (error: Exception) {
        connectivity.unregisterNetworkCallback(callback)
        throw error
    }
    publish()
    awaitClose { connectivity.unregisterNetworkCallback(callback); context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/** collectLatest waits for old endpoint cleanup before binding a replacement. */
internal suspend fun Flow<NetworkWindow>.whileAvailable(waiting: () -> Unit, connected: suspend () -> Unit) {
    distinctUntilChanged().collectLatest { window ->
        if (window.permitted) connected() else waiting()
    }
}
