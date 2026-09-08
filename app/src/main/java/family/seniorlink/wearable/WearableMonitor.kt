package family.seniorlink.wearable

import family.seniorlink.core.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

data class LiveWearableValue(val metric: WearableMetric, val value: Double, val receivedAt: Long)
data class WearableState(
    val connected: Boolean = false,
    val status: String = "Wearable collection is paused",
    val lastReceivedAt: Long = 0,
    val latest: List<LiveWearableValue> = emptyList(),
    val information: Map<String, String> = emptyMap(),
    val capabilities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
)

/** The same collection lifecycle is exercised with a simulated peripheral in tests. */
class WearableMonitor(
    private val linkFactory: (String) -> BleLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = WearableAccumulator.INTERVAL_MS,
    private val pollMs: Long = 300_000,
    private val retryMs: Long = 3_000,
) {
    suspend fun run(
        address: String,
        name: String,
        enabled: () -> Boolean,
        onState: (WearableState) -> Unit,
        onSummary: (WearableSummary, Long) -> Unit,
        deviceId: String = "legacy",
    ) {
        val guard = Any()
        var state = WearableState()
        val accumulator = WearableAccumulator(name, deviceId)
        var backoff = retryMs
        var firstReading = true
        val lastSaved = AtomicLong(0)

        fun update(change: (WearableState) -> WearableState) = synchronized(guard) {
            state = change(state); onState(state)
        }
        fun issue(message: String) = update { it.copy(issues = (it.issues + message.take(160)).distinct().takeLast(20)) }
        fun flush() = synchronized(guard) {
            if (enabled()) accumulator.drain()?.let {
                val through = maxOf(clock(), it.metrics.maxOfOrNull { metric -> metric.lastAt } ?: 0)
                onSummary(it, through)
                lastSaved.set(through)
            }
        }
        fun accept(field: BleField, bytes: ByteArray) = synchronized(guard) {
            if (!enabled()) return@synchronized
            val reading = BleMeasurements.decode(field, bytes)
            if (reading == null) { issue("${field.title}: malformed or unsupported measurement ignored"); return@synchronized }
            val at = clock()
            accumulator.add(reading, at)
            val live = state.latest.associateBy { it.metric }.toMutableMap()
            // Contact loss invalidates the current live pulse/beat interval, but retained history remains timestamped.
            if (reading.values.any { it.metric == WearableMetric.CONTACT && it.value == 0.0 }) {
                live.remove(WearableMetric.HEART_RATE); live.remove(WearableMetric.RR_INTERVAL)
            }
            reading.values.forEach { live[it.metric] = LiveWearableValue(it.metric, it.value, at) }
            update { it.copy(
                lastReceivedAt = if (reading.values.isNotEmpty()) at else it.lastReceivedAt,
                latest = live.values.toList(), information = it.information + reading.information,
                issues = reading.note?.let { note -> (it.issues + note).distinct().takeLast(20) } ?: it.issues,
            ) }
            // Send the first actual reading promptly; subsequent readings are summarized without flooding the feed.
            if (firstReading && reading.values.any { it.metric != WearableMetric.BATTERY }) { firstReading = false; flush() }
        }

        try {
            while (currentCoroutineContext().isActive && enabled()) {
                update { it.copy(connected = false, status = "Connecting to $name…") }
                val link = linkFactory(address)
                try {
                    coroutineScope {
                        val attributes = link.discover()
                        currentCoroutineContext().ensureActive()
                        if (!enabled()) return@coroutineScope
                        val supported = attributes.filter { it.field != null }.distinctBy { it.field }
                        val supportedKeys = supported.map { it.key }.toSet()
                        update { it.copy(connected = true,
                            status = "Connected. Discovering available measurements…",
                            capabilities = emptyList(), issues = emptyList(), information = emptyMap(),
                            services = attributes.map {
                                "${it.service} / ${it.uuid} (${buildList { if (it.readable) add("read"); if (it.notifiable) add("notify"); if (it.indicatable) add("indicate") }.joinToString()})"
                            }.take(256)) }
                        if (attributes.size != attributes.distinctBy { it.service to it.uuid }.size)
                            issue("Multiple instances found; using the first instance of each supported characteristic")
                        val receiving = launch {
                            for (value in link.values) if (value.attribute.key in supportedKeys) value.attribute.field?.let { accept(it, value.bytes) }
                            throw BleOperationException("Wearable disconnected")
                        }
                        val saving = launch {
                            while (isActive) {
                                delay(intervalMs)
                                flush()
                                update { it.copy(status = if (it.lastReceivedAt == 0L || clock() - it.lastReceivedAt > intervalMs * 2)
                                    "Connected; no recent measurements. Enable measurement on the band and check skin contact."
                                else "Connected; collecting wearable readings") }
                            }
                        }
                        val polling = mutableListOf<BleAttribute>()
                        for (attribute in supported) {
                            val field = requireNotNull(attribute.field)
                            var received = false
                            var subscribed = false
                            // Read device details before subscriptions where possible; reads never target unknown/vendor fields.
                            if (attribute.readable) {
                                try {
                                    accept(field, link.read(attribute)); received = true
                                    if (field.measurement) polling += attribute
                                } catch (e: TimeoutCancellationException) { throw e }
                                catch (e: BleOperationException) { issue("${field.title}: read unavailable. ${e.message}") }
                            }
                            if (field.measurement && (attribute.notifiable || attribute.indicatable)) {
                                try { link.subscribe(attribute); subscribed = true }
                                catch (e: TimeoutCancellationException) { throw e }
                                catch (e: BleOperationException) { issue("${field.title}: subscription unavailable. ${e.message}") }
                            }
                            update { it.copy(capabilities = it.capabilities + "${field.title}: ${when {
                                subscribed -> "listening"
                                received -> "read successfully"
                                else -> "present, access unavailable"
                            }}") }
                        }
                        if (supported.isEmpty()) issue("No supported standard measurements found. Vendor-specific services are listed below.")
                        if (attributes.none { it.field == BleField.HEART_RATE }) issue("This device did not expose the standard Bluetooth heart-rate service")
                        backoff = retryMs
                        update { it.copy(status = "Connected; listening for supported measurements") }
                        // Device details are useful even on devices which never emit a health sample.
                        if (lastSaved.get() == 0L) flush()
                        while (isActive && enabled()) {
                            delay(pollMs)
                            for (attribute in polling) {
                                try { accept(requireNotNull(attribute.field), link.read(attribute)) }
                                catch (e: TimeoutCancellationException) { throw e }
                                catch (e: BleOperationException) { issue("${attribute.field?.title}: refresh unavailable") }
                            }
                        }
                        receiving.cancel(); saving.cancel()
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    update { it.copy(connected = false, status = "Wearable connection timed out; retrying") }
                } catch (_: SecurityException) {
                    update { it.copy(connected = false, status = "Bluetooth permission was revoked. Pause sharing, grant permission, and start again.") }
                    return
                } catch (e: Exception) {
                    update { it.copy(connected = false, status = if (e is BleOperationException) "${e.message}. Retrying…"
                        else "Wearable unavailable. Check Bluetooth, range, and pairing. Retrying…") }
                } finally {
                    link.close()
                    // A flapping connection must not produce an event on every reconnect.
                    if (lastSaved.get() == 0L || clock() - lastSaved.get() >= intervalMs) flush()
                }
                if (enabled()) { delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000) }
            }
        } finally {
            // The caller closes consent before cancellation, so Pause never emits a buffered update afterwards.
            update { it.copy(connected = false, status = if (!enabled()) "Wearable collection is paused" else it.status) }
        }
    }
}
