package family.seniorlink.wearable

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

object BleAccess {
    fun missingConnectionPermissions(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 31) missing(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)) else emptyList()

    fun missingScanPermissions(context: Context): List<String> = missing(context,
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION))

    private fun missing(context: Context, permissions: List<String>) = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun supported(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}

data class WearableDevice(val address: String, val name: String, val bonded: Boolean)
data class WearableScanState(
    val scanning: Boolean = false,
    val status: String = "Scan for a nearby wearable or select an already paired device.",
    val devices: List<WearableDevice> = emptyList(),
)

/** Scans only while the device chooser is visible. Never bonds, resets, or connects on discovery. */
@SuppressLint("MissingPermission")
class WearableScanner(private val context: Context) {
    val state = MutableStateFlow(WearableScanState())
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private val stopTask = Runnable { stop() }

    fun start() {
        stop()
        if (!BleAccess.supported(context)) { state.value = WearableScanState(status = "Bluetooth LE is not available on this phone"); return }
        if (BleAccess.missingScanPermissions(context).isNotEmpty()) {
            state.value = WearableScanState(status = "Allow Bluetooth discovery permissions, then scan again"); return
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { state.value = WearableScanState(status = "Turn on Bluetooth, then scan again"); return }
            val paired = adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
                .take(50).map { WearableDevice(it.address, safeName(it.name), true) }.sortedBy { it.name }
            state.value = WearableScanState(devices = paired)
            if (Build.VERSION.SDK_INT <= 30 && context.getSystemService(LocationManager::class.java)?.let {
                    !it.isProviderEnabled(LocationManager.GPS_PROVIDER) && !it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } == true) {
                state.value = state.value.copy(status = "This Android version needs Location turned on to scan. Already paired devices can still be selected.")
                return
            }
            val scanner = adapter.bluetoothLeScanner ?: run {
                state.value = state.value.copy(status = "Bluetooth scanner is unavailable"); return
            }
            val listener = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (callback !== this) return@post
                        try {
                            val device = WearableDevice(result.device.address,
                                safeName(result.scanRecord?.deviceName ?: result.device.name), result.device.bondState == BluetoothDevice.BOND_BONDED)
                            val devices = state.value.devices
                            if (devices.size < 50 || devices.any { it.address == device.address }) {
                                state.value = state.value.copy(devices = (devices.filterNot { it.address == device.address } + device)
                                    .sortedWith(compareByDescending<WearableDevice> { it.bonded }.thenBy { it.name }))
                            }
                        } catch (_: SecurityException) { stop("Bluetooth permission was revoked") }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (callback === this) stop("Bluetooth scan failed ($errorCode). Wait a moment and try again.") }
                }
            }
            callback = listener
            state.value = state.value.copy(scanning = true, status = "Scanning for 15 seconds… Choose your wearable by name and address.")
            // Do not filter by advertised service: Fit3 and other bands may omit it from advertisements.
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), listener)
            handler.postDelayed(stopTask, 15_000)
        } catch (_: Exception) { stop("Could not scan. Check Bluetooth and app permissions.") }
    }

    fun stop(message: String? = null) {
        handler.removeCallbacks(stopTask)
        val old = callback
        callback = null
        if (old != null) runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(old) }
        state.value = state.value.copy(scanning = false, status = message ?: if (old != null) "Scan complete. Select your wearable or scan again." else state.value.status)
    }

    companion object {
        fun safeName(name: String?) = name.orEmpty().filter { !it.isISOControl() }.trim().take(80).ifBlank { "Unnamed Bluetooth device" }
    }
}
