package family.seniorlink

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.monitor.MonitorService
import family.seniorlink.net.IrohSync
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
    val events: List<StoredEvent> = emptyList(),
    val pendingTelegram: Long = 0,
    val tokenSaved: Boolean = false,
    val fatalError: String? = null,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SeniorApp
    val screen = MutableStateFlow(ScreenState())
    val pairing = PairingController(viewModelScope, app.store) { app.identity }
    private val monitorIntent = Intent(app, MonitorService::class.java)
    private var visible = false
    private var receiverJob: Job? = null

    init {
        viewModelScope.launch {
            if (app.initializationError != null) {
                screen.update { it.copy(fatalError = app.initializationError) }
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { app.publicId }
                app.store.changes.collect {
                    refresh()
                    reconcileReceiver()
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
            ScreenState(
                ready = true, publicId = app.publicId, settings = app.store.settings,
                peers = app.store.peers(), events = app.store.recent(),
                pendingTelegram = app.store.pendingTelegram(),
                tokenSaved = app.secrets.read("telegram")?.isNotEmpty() == true,
            )
        }
        screen.value = current.copy(message = screen.value.message)
    }

    fun foreground(active: Boolean) {
        visible = active
        reconcileReceiver()
    }

    private fun reconcileReceiver() {
        if (!visible || !screen.value.ready || app.store.settings.role != Role.CAREGIVER) {
            receiverJob?.cancel()
            receiverJob = null
            return
        }
        if (receiverJob?.isActive == true) return
        receiverJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    IrohSync.receiveWhileOpen(app.identity, app.store) { peer, status ->
                        app.peerStatus.update { it + (peer to status) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    app.peerStatus.value = app.store.peers().associate { it.id to "Network unavailable — retrying" }
                    delay(15_000)
                }
            }
        }
    }

    fun chooseRole(role: Role) = action {
        app.store.updateSettings(app.store.settings.copy(role = role), app.publicId)
    }

    fun save(settings: Settings, token: String) = action {
        check(!MonitorService.running.value) { "Pause monitoring before changing collection settings." }
        require(!settings.sms || settings.smsSenders.lineSequence().any { it.isNotBlank() && it.trim() != "*" }) {
            "Add at least one exact SMS sender before enabling SMS."
        }
        if (token.isNotBlank()) {
            require(Telegram.validToken(token.trim())) { "The Telegram bot token format is invalid." }
        }
        if (settings.telegram) {
            require(Telegram.validChat(settings.telegramChat.trim())) { "Enter a numeric chat ID or @channel name." }
            require(token.isNotBlank() || app.secrets.read("telegram")?.isNotEmpty() == true) { "Enter a bot token." }
        }
        if (token.isNotBlank()) app.secrets.write("telegram", token.trim().toByteArray())
        app.store.updateSettings(settings.copy(telegramChat = settings.telegramChat.trim()), app.publicId)
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

    fun start() {
        if (MonitorService.missingPermissions(app, app.store.settings).isNotEmpty()) {
            message("Grant the selected permissions, then tap Start again.")
            return
        }
        try {
            ContextCompat.startForegroundService(app, monitorIntent)
        } catch (_: Exception) {
            message("Android prevented startup. Keep this screen open and review app permissions.")
        }
    }

    fun pause() {
        // Close the authorization gate before asynchronous service cleanup.
        MonitorService.running.value = false
        app.stopService(monitorIntent)
    }

    fun checkIn() = action {
        check(MonitorService.running.value) { "Start visible sharing before sending a check-in." }
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
