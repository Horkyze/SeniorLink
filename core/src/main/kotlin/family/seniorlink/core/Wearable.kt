package family.seniorlink.core

import kotlinx.serialization.Serializable

@Serializable
enum class WearableMetric(val label: String, val unit: String, val low: Double, val high: Double) {
    HEART_RATE("Heart rate", "bpm", 0.0, 65535.0),
    CONTACT("Sensor contact", "", 0.0, 1.0),
    RR_INTERVAL("Beat interval", "ms", 0.0, 64000.0),
    ENERGY("Energy expended", "kJ", 0.0, 65535.0),
    BATTERY("Battery", "%", 0.0, 100.0),
    OXYGEN("Blood oxygen", "%", 0.0, 100.0),
    OXYGEN_FAST("Blood oxygen (fast)", "%", 0.0, 100.0),
    OXYGEN_SLOW("Blood oxygen (slow)", "%", 0.0, 100.0),
    PULSE_RATE("Pulse rate", "bpm", 0.0, 65535.0),
    PULSE_FAST("Pulse rate (fast)", "bpm", 0.0, 65535.0),
    PULSE_SLOW("Pulse rate (slow)", "bpm", 0.0, 65535.0),
    TEMPERATURE("Temperature", "°C", -273.15, 1000.0),
    SYSTOLIC("Systolic pressure", "mmHg", 0.0, 2000.0),
    DIASTOLIC("Diastolic pressure", "mmHg", 0.0, 2000.0),
    MEAN_PRESSURE("Mean arterial pressure", "mmHg", 0.0, 2000.0),
    PERFUSION("Pulse amplitude index", "%", 0.0, 1000.0),
}

/** Times are receipt times on the sharing phone, not a reconstructed watch clock. */
@Serializable
data class WearableMetricSummary(
    val metric: WearableMetric,
    val firstAt: Long,
    val lastAt: Long,
    val count: Int,
    val minimum: Double,
    val maximum: Double,
    val average: Double,
    val latest: Double,
) {
    fun validate(through: Long) {
        require(firstAt > 0 && lastAt >= firstAt && lastAt <= through)
        require(count in 1..100_000)
        listOf(minimum, maximum, average, latest).forEach {
            require(it.isFinite() && it in metric.low..metric.high)
        }
        require(minimum <= maximum && average in minimum..maximum && latest in minimum..maximum)
        if (metric == WearableMetric.CONTACT) require(latest == 0.0 || latest == 1.0)
    }
}

@Serializable
data class WearableSummary(
    val deviceName: String,
    val metrics: List<WearableMetricSummary> = emptyList(),
    val information: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
    val deviceId: String = "legacy",
) {
    fun validate(through: Long) {
        require(deviceName.isNotBlank() && deviceName.length <= 80)
        require(Regex("[A-Za-z0-9-]{1,64}").matches(deviceId))
        require(metrics.size <= WearableMetric.entries.size && metrics.map { it.metric }.distinct().size == metrics.size)
        require(information.size <= INFO_KEYS.size && information.all { it.key in INFO_KEYS && it.value.length in 1..100 })
        require(notes.size <= 8 && notes.all { it.length in 1..160 })
        require(metrics.isNotEmpty() || information.isNotEmpty() || notes.isNotEmpty())
        metrics.forEach { it.validate(through) }
    }

    companion object {
        val INFO_KEYS = setOf("Manufacturer", "Model", "Firmware", "Hardware", "Software", "Sensor location", "Temperature site",
            "Device time (unverified)", "Pulse oximeter features", "Blood pressure features")
    }
}

data class WearableValue(val metric: WearableMetric, val value: Double)
data class WearableReading(
    val values: List<WearableValue> = emptyList(),
    val information: Map<String, String> = emptyMap(),
    val note: String? = null,
)

/** Bounded two-minute summaries keep a continuous stream from evicting the other safety events. */
class WearableAccumulator(private val name: String, private val deviceId: String = "legacy") {
    private val metrics = linkedMapOf<WearableMetric, WearableMetricSummary>()
    private val information = linkedMapOf<String, String>()
    private val notes = linkedSetOf<String>()

    fun add(reading: WearableReading, at: Long) {
        require(at > 0)
        reading.values.forEach { (metric, value) ->
            if (!value.isFinite() || value !in metric.low..metric.high) return@forEach
            val old = metrics[metric]
            if (old == null) metrics[metric] = WearableMetricSummary(metric, at, at, 1, value, value, value, value)
            else if (old.count < 100_000) metrics[metric] = old.copy(
                firstAt = minOf(old.firstAt, at), lastAt = maxOf(old.lastAt, at), count = old.count + 1,
                minimum = minOf(old.minimum, value), maximum = maxOf(old.maximum, value),
                average = (old.average + (value - old.average) / (old.count + 1)).coerceIn(minOf(old.minimum, value), maxOf(old.maximum, value)),
                latest = if (at >= old.lastAt) value else old.latest,
            )
        }
        reading.information.filter { it.key in WearableSummary.INFO_KEYS && it.value.isNotBlank() }
            .forEach { (key, value) -> information[key] = value.take(100) }
        reading.note?.takeIf { it.isNotBlank() && notes.size < 8 }?.let { notes += it.take(160) }
    }

    fun drain(): WearableSummary? {
        if (metrics.isEmpty() && information.isEmpty() && notes.isEmpty()) return null
        val result = WearableSummary(name.take(80).ifBlank { "Wearable" }, metrics.values.toList(), information.toMap(), notes.toList(), deviceId)
        metrics.clear(); information.clear(); notes.clear()
        return result
    }

    companion object { const val INTERVAL_MS = 120_000L }
}
