package family.seniorlink

import android.app.Application
import computer.iroh.IrohAndroid
import computer.iroh.SecretKey
import family.seniorlink.core.Event
import family.seniorlink.core.Role
import family.seniorlink.data.Secrets
import family.seniorlink.data.Store
import family.seniorlink.monitor.MonitorService
import family.seniorlink.monitor.BackgroundSession
import family.seniorlink.net.CaregiverReceiver
import family.seniorlink.net.IrohSync
import family.seniorlink.net.networkWindows
import family.seniorlink.net.whileAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class SeniorApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val monitorStatus = MutableStateFlow("Monitoring paused")
    val telegramStatus = MutableStateFlow("Telegram is optional and disabled by default")
    val peerStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val mailbox by lazy { family.seniorlink.mailbox.MailboxCoordinator(this) }
    val wearableState = MutableStateFlow(family.seniorlink.wearable.WearableState())
    lateinit var store: Store
        private set
    lateinit var secrets: Secrets
        private set
    lateinit var backgroundSession: BackgroundSession
        private set
    internal val caregiverReceiver by lazy {
        CaregiverReceiver(scope) { pollMs ->
            while (currentCoroutineContext().isActive) {
                try {
                    networkWindows(this).whileAvailable(
                        waiting = { peerStatus.value = store.peers().associate { it.id to "Waiting for network or Android sleep to end — showing saved updates" } },
                    ) {
                        while (currentCoroutineContext().isActive) {
                            try {
                                coroutineScope {
                                    launch { mailbox.receiverLoop(pollMs) }
                                    IrohSync.receive(identity, store, pollMs, setup = mailbox::setup) { peer, status -> peerStatus.update { it + (peer to status) } }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { peerStatus.value = store.peers().associate { it.id to "Network unavailable — retrying" } }
                            delay(15_000)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { delay(15_000) }
            }
        }
    }
    var initializationError: String? = null
        private set
    val identity: ByteArray by lazy {
        secrets.read("identity") ?: SecretKey.generate().use { key ->
            key.toBytes().also { secrets.write("identity", it) }
        }
    }
    val publicId: String by lazy { SecretKey.fromBytes(identity).use { key -> key.public().use { it.toString() } } }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        secrets = Secrets(this)
        backgroundSession = BackgroundSession(this)
        try {
            IrohAndroid.installAndroidContext(this)
        } catch (_: Throwable) {
            initializationError = "The iroh native library could not initialize. Reinstall a complete APK."
        }
        if (initializationError == null) scope.launch {
            // Revocation cleanup does not restart sharing or renew an active lease.
            networkWindows(this@SeniorApp).whileAvailable(waiting = {}) {
                while (isActive) {
                    try { mailbox.cleanup() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    delay(15_000)
                }
            }
        }
    }

    fun record(event: Event, session: Any? = MonitorService.sessionToken) {
        synchronized(store) {
            if (session != null && session === MonitorService.sessionToken && MonitorService.running.value &&
                backgroundSession.enabled(Role.SHARER) && store.settings.role == Role.SHARER && store.settings.allows(event.kind)) {
                try {
                    store.append(publicId, event, System.currentTimeMillis())
                } catch (_: IllegalArgumentException) {
                    // The sender or feature may have been disabled while a broadcast was being assembled.
                } catch (_: Exception) {
                    monitorStatus.value = "Could not save an update. Check available phone storage."
                }
            }
        }
    }
}
