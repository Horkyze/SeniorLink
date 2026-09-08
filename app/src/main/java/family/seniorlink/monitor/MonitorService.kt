package family.seniorlink.monitor

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import family.seniorlink.MainActivity
import family.seniorlink.R
import family.seniorlink.SeniorApp
import family.seniorlink.core.*
import family.seniorlink.net.IrohSync
import family.seniorlink.net.Telegram
import family.seniorlink.wearable.AndroidBleLink
import family.seniorlink.wearable.BleAccess
import family.seniorlink.wearable.WearableMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as SeniorApp
    private var registered = false
    private var locationManager: LocationManager? = null
    private var lastLocationRecorded = 0L
    @Volatile private var wearableActive = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && app.store.settings.unlock) {
                scope.launch { app.record(Event(0, Kind.UNLOCK, System.currentTimeMillis())) }
            }
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (lastLocationRecorded != 0L && elapsed - lastLocationRecorded < 10 * 60_000) return
            if (!location.hasAccuracy() || location.time <= 0) return
            lastLocationRecorded = elapsed
            scope.launch {
                app.record(Event(
                    0, Kind.LOCATION, location.time,
                    latitude = location.latitude, longitude = location.longitude, accuracy = location.accuracy,
                ))
            }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            app.monitorStatus.value = "Monitoring active; location provider is disabled"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.value) return START_NOT_STICKY
        val settings = app.store.settings
        if (settings.role != Role.SHARER || app.initializationError != null || missingPermissions(this, settings).isNotEmpty()) {
            app.monitorStatus.value = "Monitoring not started — check permissions"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Visible safety monitoring", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val pause = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_seniorlink)
                .setContentTitle("SeniorLink sharing is active")
                .setContentText("Approved caregivers can catch up. Tap to review or pause.")
                .setContentIntent(open).setOngoing(true)
                .addAction(0, "Pause sharing", pause).build()
            var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (settings.location && Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (settings.wearable && Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, 1, notification, types)
            running.value = true
            app.monitorStatus.value = "Monitoring active; starting encrypted sharing…"
            // USER_PRESENT is a protected system broadcast, but SystemUI can send it
            // from a privileged non-system UID. NOT_EXPORTED drops those real unlocks.
            ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
            registered = true
            if (settings.location) startLocation()
            if (settings.wearable) {
                wearableActive = true
                scope.launch {
                    WearableMonitor({ address -> AndroidBleLink(this@MonitorService, address) }).run(
                        settings.wearableAddress, settings.wearableName.ifBlank { "Wearable" },
                        enabled = { wearableActive && running.value && app.store.settings.role == Role.SHARER && app.store.settings.wearable &&
                            app.store.settings.wearableAddress == settings.wearableAddress },
                        onState = { if (wearableActive && running.value) app.wearableState.value = it },
                        onSummary = { summary, at -> app.record(Event(0, Kind.WEARABLE, at, wearable = summary)) },
                        deviceId = settings.wearableId,
                    )
                }
            }
            scope.launch {
                while (isActive) {
                    try {
                        IrohSync.serve(app.identity, app.store, { running.value }, { app.monitorStatus.value = it })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        app.monitorStatus.value = "Collecting locally; sharing connection will retry"
                    }
                    delay(15_000)
                }
            }
            scope.launch { Telegram.run(app) }
        } catch (_: Exception) {
            app.monitorStatus.value = "Android could not start monitoring. Open app settings to review permissions."
            running.value = false
            stopSelf()
        }
        // Never silently re-enable collection after a restart or user stop.
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        val manager = getSystemService(LocationManager::class.java)
        locationManager = manager
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            app.monitorStatus.value = "Monitoring active; enable location in Android settings"
            return
        }
        manager.requestLocationUpdates(provider, 15 * 60_000L, 0f, locationListener, Looper.getMainLooper())
    }

    override fun onDestroy() {
        // A cancelled old service must never publish into a newly started collection session.
        wearableActive = false
        running.value = false
        scope.cancel()
        if (registered) unregisterReceiver(unlockReceiver)
        locationManager?.removeUpdates(locationListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        app.monitorStatus.value = "Monitoring paused — open the app and tap Start to resume"
        app.wearableState.value = app.wearableState.value.copy(connected = false, status = "Wearable collection is paused")
        super.onDestroy()
    }

    companion object {
        val running = MutableStateFlow(false)
        const val ACTION_PAUSE = "family.seniorlink.PAUSE"
        private const val CHANNEL = "monitoring"
        fun missingPermissions(context: Context, settings: Settings): List<String> = buildList {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (settings.location && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (settings.sms && !granted(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (settings.wearable) addAll(BleAccess.missingConnectionPermissions(context))
        }
    }
}
