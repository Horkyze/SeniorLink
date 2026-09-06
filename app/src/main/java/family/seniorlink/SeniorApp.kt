package family.seniorlink

import android.app.Application
import computer.iroh.IrohAndroid
import computer.iroh.SecretKey
import family.seniorlink.core.Event
import family.seniorlink.core.Role
import family.seniorlink.data.Secrets
import family.seniorlink.data.Store
import family.seniorlink.monitor.MonitorService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class SeniorApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val monitorStatus = MutableStateFlow("Monitoring paused")
    val telegramStatus = MutableStateFlow("Telegram is optional and disabled by default")
    val peerStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    lateinit var store: Store
        private set
    lateinit var secrets: Secrets
        private set
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
        try {
            IrohAndroid.installAndroidContext(this)
        } catch (_: Throwable) {
            initializationError = "The iroh native library could not initialize. Reinstall a complete APK."
        }
    }

    fun record(event: Event) {
        synchronized(store) {
            if (MonitorService.running.value && store.settings.role == Role.SHARER && store.settings.allows(event.kind)) {
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
