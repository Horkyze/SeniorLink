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
import family.seniorlink.net.networkWindows
import family.seniorlink.net.whileAvailable
import family.seniorlink.wearable.AndroidBleLink
import family.seniorlink.wearable.BleAccess
import family.seniorlink.wearable.WearableMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileDescriptor
import java.io.PrintWriter

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as SeniorApp
    private val token = Any()
    private var active = false
    private var registered = false
    private var locationManager: LocationManager? = null
    private var lastLocationRecorded = 0L
    @Volatile private var wearableActive = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && app.store.settings.unlock) {
                scope.launch { app.record(Event(0, Kind.UNLOCK, System.currentTimeMillis()), token) }
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
                ), token)
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

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        // ADB diagnostics contain state only: never identities, readings, locations or messages.
        writer.println("role=${app.store.settings.role}")
        writer.println("collectionActive=${sessionEnabled()} backgroundReceiving=${receiving.value}")
        writer.println("locationDeferred=${locationDeferred.value}")
        writer.println("transport=${app.monitorStatus.value}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            pause(app)
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = app.store.settings
        if (settings.role == Role.UNSET || app.initializationError != null || missingPermissions(this, settings).isNotEmpty()) {
            pause(app)
            app.monitorStatus.value = "Monitoring paused — check permissions and turn on Sharing in Settings"
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) app.backgroundSession.enable(settings.role)
        if (!app.backgroundSession.enabled(settings.role)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent is Android's sticky recreation, a while-in-use permission exception.
        // Fresh boot/job starts must not activate location without background permission.
        val locationAllowed = locationManager != null || canStartLocation(this, intent?.action, stickyRestart = intent == null)
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Visible safety monitoring", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val pause = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_seniorlink)
                .setContentTitle(if (settings.role == Role.SHARER) "SeniorLink sharing is active" else "SeniorLink background updates are active")
                .setContentText(if (settings.role == Role.SHARER) "Approved caregivers can catch up. Tap to review or pause."
                    else "Receiving family updates when reachable. Tap to review or pause.")
                .setContentIntent(open).setOngoing(true)
                .addAction(0, if (settings.role == Role.SHARER) "Pause sharing" else "Pause background updates", pause).build()
            var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (settings.role == Role.SHARER && settings.location && locationAllowed && Build.VERSION.SDK_INT >= 29)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (settings.role == Role.SHARER && settings.wearable && Build.VERSION.SDK_INT >= 29)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, 1, notification, types)
            BackgroundRecovery.schedule(this)
            BackgroundRecovery.clearNotice(this)
            locationDeferred.value = settings.role == Role.SHARER && settings.location && !locationAllowed
            if (active) {
                if (settings.role == Role.SHARER && settings.location && locationAllowed && locationManager == null) startLocation()
                return START_STICKY
            }
            active = true
            if (settings.role == Role.CAREGIVER) {
                receiving.value = true
                app.caregiverReceiver.background(true)
                watchPermissions()
                return START_STICKY
            }
            sessionToken = token
            running.value = true
            app.monitorStatus.value = "Monitoring active; starting encrypted sharing…"
            // USER_PRESENT is a protected system broadcast, but SystemUI can send it
            // from a privileged non-system UID. NOT_EXPORTED drops those real unlocks.
            ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
            registered = true
            if (settings.location && locationAllowed) startLocation()
            if (settings.phoneBattery) scope.launch {
                PhoneBatteryMonitor().run(
                    enabled = { sessionEnabled() && app.store.settings.phoneBattery },
                    read = { PhoneBatteryMonitor.read(this@MonitorService) },
                    onReading = { battery -> app.record(Event(0, Kind.PHONE_BATTERY, System.currentTimeMillis(), phoneBattery = battery), token) },
                )
            }
            if (settings.wearable) {
                wearableActive = true
                scope.launch {
                    WearableMonitor({ address -> AndroidBleLink(this@MonitorService, address) }).run(
                        settings.wearableAddress, settings.wearableName.ifBlank { "Wearable" },
                        enabled = { wearableActive && sessionEnabled() && app.store.settings.wearable &&
                            app.store.settings.wearableAddress == settings.wearableAddress },
                        onState = { if (wearableActive && running.value) app.wearableState.value = it },
                        onSummary = { summary, at -> app.record(Event(0, Kind.WEARABLE, at, wearable = summary), token) },
                        deviceId = settings.wearableId,
                    )
                }
            }
            scope.launch {
                while (isActive) {
                    try {
                        networkWindows(this@MonitorService).whileAvailable(
                            waiting = { app.monitorStatus.value = "Collecting locally; waiting for network or Android sleep to end" },
                        ) {
                            coroutineScope {
                                launch { if (settings.telegram) Telegram.run(app) }
                                while (isActive && sessionEnabled()) {
                                    try {
                                        IrohSync.serve(app.identity, app.store, ::sessionEnabled) { app.monitorStatus.value = it }
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { app.monitorStatus.value = "Collecting locally; sharing connection will retry" }
                                    delay(15_000)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        app.monitorStatus.value = "Collecting locally; sharing connection will retry"
                    }
                    delay(15_000)
                }
            }
            watchPermissions()
        } catch (_: Exception) {
            app.monitorStatus.value = "Android restricted background startup. Open SeniorLink to resume."
            running.value = false
            receiving.value = false
            BackgroundRecovery.notice(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun sessionEnabled() = running.value && sessionToken === token && app.backgroundSession.enabled(Role.SHARER)

    private fun watchPermissions() {
        scope.launch {
            while (isActive) {
                if (missingPermissions(this@MonitorService, app.store.settings).isNotEmpty()) {
                    withContext(Dispatchers.Main) { pause(app) }
                    break
                }
                delay(60_000)
            }
        }
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
        if (sessionToken === token) { running.value = false; sessionToken = null }
        if (active && app.store.settings.role == Role.CAREGIVER) {
            receiving.value = false
            app.caregiverReceiver.background(false)
        }
        locationDeferred.value = false
        scope.cancel()
        if (registered) unregisterReceiver(unlockReceiver)
        locationManager?.removeUpdates(locationListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        app.monitorStatus.value = if (app.backgroundSession.enabled(app.store.settings.role))
            "Monitoring interrupted — Android will be asked to resume; open the app if updates stay delayed"
        else "Monitoring paused — turn on Sharing in Settings to resume"
        app.wearableState.value = app.wearableState.value.copy(connected = false, status = "Wearable collection is paused")
        super.onDestroy()
    }

    companion object {
        val running = MutableStateFlow(false)
        val receiving = MutableStateFlow(false)
        val locationDeferred = MutableStateFlow(false)
        @Volatile internal var sessionToken: Any? = null
        const val ACTION_START = "family.seniorlink.START"
        const val ACTION_RESUME = "family.seniorlink.RESUME"
        const val ACTION_RESUME_VISIBLE = "family.seniorlink.RESUME_VISIBLE"
        const val ACTION_PAUSE = "family.seniorlink.PAUSE"
        private const val CHANNEL = "monitoring"

        fun pause(app: SeniorApp) {
            synchronized(app.store) {
                running.value = false
                sessionToken = null
                receiving.value = false
                try { app.backgroundSession.pause() }
                finally {
                    app.caregiverReceiver.background(false)
                    BackgroundRecovery.cancel(app)
                    val stopIntent = Intent(app, MonitorService::class.java)
                    app.stopService(stopIntent)
                }
            }
        }

        internal fun canStartLocation(context: Context, action: String?, stickyRestart: Boolean = false): Boolean =
            stickyRestart || action == ACTION_START || action == ACTION_RESUME_VISIBLE || Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun missingPermissions(context: Context, settings: Settings): List<String> = buildList {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (settings.role != Role.SHARER) return@buildList
            if (settings.location && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (settings.sms && !granted(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (settings.wearable) addAll(BleAccess.missingConnectionPermissions(context))
        }
    }
}
