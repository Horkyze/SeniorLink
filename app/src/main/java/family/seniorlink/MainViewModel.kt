package family.seniorlink

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.monitor.MonitorService
import family.seniorlink.monitor.BackgroundRecovery
import family.seniorlink.net.Telegram
import family.seniorlink.pairing.PairingController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class ScreenState(
    val ready: Boolean = false,
    val publicId: String = "",
    val settings: Settings = Settings(),
    val peers: List<Peer> = emptyList(),
    val locations: List<StoredEvent> = emptyList(),
    val inspectedLocation: StoredEvent? = null,
    val wearables: List<StoredEvent> = emptyList(),
    val phoneBatteries: List<StoredEvent> = emptyList(),
    val pendingTelegram: Long = 0,
    val tokenSaved: Boolean = false,
    val fatalError: String? = null,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SeniorApp
    val screen = MutableStateFlow(ScreenState())
    val pairing = PairingController(viewModelScope, app.store) { app.identity }
    val wearableScanner = family.seniorlink.wearable.WearableScanner(app)
    internal val activityBrowser = family.seniorlink.dashboard.ActivityBrowser(viewModelScope, app.store) { app.publicId }
    private val monitorIntent = Intent(app, MonitorService::class.java)
    private var visible = false
    @Volatile private var inspectedLocationKey: Pair<String, Long>? = null
    val permissionRequest = MutableStateFlow<List<String>>(emptyList())
    private var startRequested = false
    private var waitingForPermissions = false

    init {
        viewModelScope.launch {
            if (app.initializationError != null) {
                screen.update { it.copy(fatalError = app.initializationError) }
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { app.publicId }
                app.store.changes.collect {
                    val firstRefresh = !screen.value.ready
                    refresh()
                    reconcileReceiver()
                    reconcileBackground(resumeExisting = firstRefresh)
                }
            } catch (_: Exception) {
                screen.update {
                    it.copy(fatalError = "Local storage or identity could not be opened. Do not clear app data unless you intend to re-pair.")
                }
            }
        }
    }

    private suspend fun refresh() {
        val current = withContext(Dispatchers.IO) {
            val peers = app.store.peers()
            val sources = if (app.store.settings.role == Role.SHARER) listOf(app.publicId) else peers.map { it.id }
            ScreenState(
                ready = true, publicId = app.publicId, settings = app.store.settings,
                peers = peers,
                locations = sources.flatMap { app.store.locations(it) },
                inspectedLocation = inspectedLocationKey?.takeIf { it.first in sources }?.let { app.store.location(it.first, it.second) },
                wearables = sources.flatMap { app.store.wearables(it) },
                phoneBatteries = sources.mapNotNull { app.store.phoneBattery(it) },
                pendingTelegram = app.store.pendingTelegram(),
                tokenSaved = app.secrets.read("telegram")?.isNotEmpty() == true,
            )
        }
        screen.value = current.copy(message = screen.value.message)
    }

    fun foreground(active: Boolean) {
        visible = active
        if (!active) wearableScanner.stop()
        if (active) reconcileBackground(resumeExisting = true)
        reconcileReceiver()
    }

    private fun reconcileBackground(resumeExisting: Boolean = false) {
        if (!visible || !screen.value.ready) return
        if (app.backgroundSession.enableAfterConnection(screen.value.settings.role, screen.value.peers.isNotEmpty())) {
            startRequested = true
        }
        if (startRequested) continueStart()
        else if (resumeExisting) BackgroundRecovery.resume(app, visible = true)
    }

    private fun reconcileReceiver() {
        app.caregiverReceiver.visible(visible && screen.value.ready && app.store.settings.role == Role.CAREGIVER)
    }

    fun chooseRole(role: Role) = action {
        app.store.updateSettings(app.store.settings.copy(role = role), app.publicId)
    }

    fun save(settings: Settings, token: String) = action {
        check(!app.backgroundSession.enabled(Role.SHARER)) { "Pause monitoring before changing collection settings." }
        require(!settings.sms || settings.smsSenders.lineSequence().any { it.isNotBlank() && it.trim() != "*" }) {
            "Add at least one exact SMS sender before enabling SMS."
        }
        require(!settings.wearable || android.bluetooth.BluetoothAdapter.checkBluetoothAddress(settings.wearableAddress)) {
            "Choose a Bluetooth wearable before enabling wearable sharing."
        }
        require(!settings.wearable || family.seniorlink.wearable.BleAccess.supported(app)) {
            "Bluetooth LE is not available on this phone."
        }
        if (token.isNotBlank()) {
            require(Telegram.validToken(token.trim())) { "The Telegram bot token format is invalid." }
        }
        if (settings.telegram) {
            require(Telegram.validChat(settings.telegramChat.trim())) { "Enter a numeric chat ID or @channel name." }
            require(token.isNotBlank() || app.secrets.read("telegram")?.isNotEmpty() == true) { "Enter a bot token." }
        }
        if (token.isNotBlank()) app.secrets.write("telegram", token.trim().toByteArray())
        val oldWearableAddress = app.store.settings.wearableAddress
        app.store.updateSettings(settings.copy(telegramChat = settings.telegramChat.trim()), app.publicId)
        if (!settings.wearable || settings.wearableAddress != oldWearableAddress)
            app.wearableState.value = family.seniorlink.wearable.WearableState()
        message("Settings saved")
    }

    fun showPairingQr(name: String) {
        try { pairing.showQr(name) } catch (e: IllegalArgumentException) { message(e.message) }
    }

    fun scanPairing(code: String, name: String) {
        try { pairing.connect(code, name, app.publicId) }
        catch (e: IllegalArgumentException) { message(e.message) }
        catch (_: Exception) { message("This QR could not be read. Show a new QR on the sharing phone and try again.") }
    }

    fun removePeer(id: String) = action { app.store.removePeer(id) }

    fun inspectLocation(stored: StoredEvent) = action {
        require(stored.event.kind == Kind.LOCATION)
        inspectedLocationKey = stored.source to stored.event.sequence
    }

    fun start() {
        if (!screen.value.ready || app.store.settings.role == Role.UNSET) return
        app.backgroundSession.enable(app.store.settings.role)
        startRequested = true
        continueStart()
    }

    private fun continueStart() {
        if (!app.backgroundSession.enabled(app.store.settings.role)) {
            startRequested = false
            permissionRequest.value = emptyList()
            return
        }
        if (!visible || !startRequested || waitingForPermissions) return
        val missing = MonitorService.missingPermissions(app, app.store.settings)
        if (missing.isNotEmpty()) {
            waitingForPermissions = true
            permissionRequest.value = missing
            return
        }
        startRequested = false
        try {
            // Authorization is already saved; a queued start must not undo a later Pause.
            ContextCompat.startForegroundService(app, Intent(monitorIntent).setAction(MonitorService.ACTION_RESUME_VISIBLE))
        } catch (_: Exception) {
            message("Android prevented startup. Keep this screen open and review app permissions.")
        }
    }

    fun permissionsLaunched() { permissionRequest.value = emptyList() }

    fun permissionsUpdated() {
        waitingForPermissions = false
        if (!app.backgroundSession.enabled(app.store.settings.role)) startRequested = false
        if (!startRequested) return
        if (MonitorService.missingPermissions(app, app.store.settings).isNotEmpty()) {
            pause()
            message("Background updates are paused because a required permission was not granted. You can turn them on again in Settings.")
        } else continueStart()
    }

    fun pause() {
        startRequested = false
        permissionRequest.value = emptyList()
        // Close the authorization gate before asynchronous service cleanup.
        MonitorService.pause(app)
        app.wearableState.value = app.wearableState.value.copy(connected = false, status = "Wearable collection is paused")
    }

    fun checkIn() = action {
        check(MonitorService.running.value) { "Turn on Sharing in Settings before sending a check-in." }
        app.record(Event(0, Kind.CHECK_IN, System.currentTimeMillis()))
        message("I'm okay — check-in saved for your caregivers")
    }

    fun testTelegram() = action {
        require(app.store.settings.role == Role.SHARER)
        val token = app.secrets.read("telegram")?.toString(Charsets.UTF_8).orEmpty()
        val result = Telegram.send(token, app.store.settings.telegramChat, "SeniorLink test — Telegram is configured.")
        app.telegramStatus.value = result.status
        message(result.status)
    }

    fun message(text: String?) { screen.update { it.copy(message = text) } }

    override fun onCleared() { app.caregiverReceiver.visible(false); wearableScanner.stop(); super.onCleared() }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                refresh()
            } catch (e: IllegalArgumentException) {
                message(e.message ?: "Check your input")
            } catch (_: IllegalStateException) {
                message("Action unavailable. Check permissions and pause monitoring before editing settings.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message("The action could not be completed. Your stored information has not been reset.")
            }
        }
    }
}
