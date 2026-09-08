package family.seniorlink.core

import kotlin.math.pow

/** Bluetooth SIG service/characteristic pairs; a matching characteristic in another service is not trusted. */
enum class BleField(val service: Int, val characteristic: Int, val title: String, val measurement: Boolean = false) {
    HEART_RATE(0x180d, 0x2a37, "Heart rate", true),
    SENSOR_LOCATION(0x180d, 0x2a38, "Sensor location"),
    BATTERY(0x180f, 0x2a19, "Battery", true),
    MANUFACTURER(0x180a, 0x2a29, "Manufacturer"),
    MODEL(0x180a, 0x2a24, "Model"),
    FIRMWARE(0x180a, 0x2a26, "Firmware"),
    HARDWARE(0x180a, 0x2a27, "Hardware"),
    SOFTWARE(0x180a, 0x2a28, "Software"),
    TEMPERATURE(0x1809, 0x2a1c, "Temperature", true),
    INTERMEDIATE_TEMPERATURE(0x1809, 0x2a1e, "Intermediate temperature", true),
    OXYGEN_SPOT(0x1822, 0x2a5e, "Spot-check oxygen", true),
    OXYGEN_CONTINUOUS(0x1822, 0x2a5f, "Continuous oxygen", true),
    OXYGEN_FEATURES(0x1822, 0x2a60, "Pulse oximeter features"),
    BLOOD_PRESSURE(0x1810, 0x2a35, "Blood pressure", true),
    BLOOD_PRESSURE_FEATURES(0x1810, 0x2a49, "Blood pressure features");

    companion object {
        fun uuid(short: Int) = "0000%04x-0000-1000-8000-00805f9b34fb".format(short)
        fun find(service: String, characteristic: String) = entries.firstOrNull {
            uuid(it.service).equals(service, true) && uuid(it.characteristic).equals(characteristic, true)
        }
    }
}

/** No proprietary commands, calibration, control-point resets, or medical interpretation. */
object BleMeasurements {
    fun decode(field: BleField, bytes: ByteArray): WearableReading? {
        if (bytes.isEmpty() || bytes.size > 512) return null
        return try {
            val p = Packet(bytes)
            when (field) {
                BleField.HEART_RATE -> heartRate(p)
                BleField.BATTERY -> {
                    val value = p.u8(); p.end()
                    if (value > 100) null else WearableReading(listOf(WearableValue(WearableMetric.BATTERY, value.toDouble())))
                }
                BleField.SENSOR_LOCATION -> {
                    val location = p.u8(); p.end()
                    val labels = listOf("Other", "Chest", "Wrist", "Finger", "Hand", "Ear lobe", "Foot")
                    WearableReading(information = mapOf(field.title to (labels.getOrNull(location) ?: "Unknown ($location)")))
                }
                BleField.MANUFACTURER, BleField.MODEL, BleField.FIRMWARE, BleField.HARDWARE, BleField.SOFTWARE -> {
                    val text = bytes.toString(Charsets.UTF_8).filter { !it.isISOControl() }.trim().take(100)
                    if (text.isEmpty()) null else WearableReading(information = mapOf(field.title to text))
                }
                BleField.TEMPERATURE, BleField.INTERMEDIATE_TEMPERATURE -> temperature(p, field)
                BleField.OXYGEN_SPOT, BleField.OXYGEN_CONTINUOUS -> oxygen(p, field)
                BleField.BLOOD_PRESSURE -> pressure(p)
                BleField.OXYGEN_FEATURES, BleField.BLOOD_PRESSURE_FEATURES ->
                    WearableReading(information = mapOf(field.title to bytes.take(32).joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
        } catch (_: IllegalArgumentException) { null }
    }

    private fun heartRate(p: Packet): WearableReading {
        val flags = p.u8()
        require(flags and 0xe0 == 0)
        val heart = if (flags and 1 != 0) p.u16() else p.u8()
        val contact = if (flags and 4 != 0) flags and 2 != 0 else null
        val values = mutableListOf<WearableValue>()
        contact?.let { values += WearableValue(WearableMetric.CONTACT, if (it) 1.0 else 0.0) }
        // An off-wrist packet must not become a reassuring heart-rate reading.
        if (contact != false) values += WearableValue(WearableMetric.HEART_RATE, heart.toDouble())
        if (flags and 8 != 0) values += WearableValue(WearableMetric.ENERGY, p.u16().toDouble())
        if (flags and 16 != 0) {
            require(p.remaining >= 2 && p.remaining % 2 == 0)
            while (p.remaining > 0) {
                val rr = p.u16() * 1000.0 / 1024.0
                if (contact != false && rr > 0) values += WearableValue(WearableMetric.RR_INTERVAL, rr)
            }
        }
        p.end()
        return WearableReading(values, note = if (contact == false) "Heart-rate sensor reports no skin contact" else null)
    }

    private fun temperature(p: Packet, field: BleField): WearableReading {
        val flags = p.u8(); require(flags and 0xf8 == 0)
        var value = p.float()
        if (flags and 1 != 0) value = value?.let { (it - 32.0) * 5.0 / 9.0 }
        val info = linkedMapOf<String, String>()
        if (flags and 2 != 0) info["Device time (unverified)"] = p.dateTime()
        if (flags and 4 != 0) {
            val site = p.u8()
            info["Temperature site"] = listOf("Unknown", "Armpit", "Body", "Ear", "Finger", "Gastrointestinal", "Mouth", "Rectum", "Toe", "Tympanum")
                .getOrNull(site) ?: "Unknown ($site)"
        }
        p.end()
        val values = mutableListOf<WearableValue>()
        values.addFinite(WearableMetric.TEMPERATURE, value)
        return WearableReading(values, info, when {
            values.isEmpty() -> "Thermometer did not provide a usable temperature"
            field == BleField.INTERMEDIATE_TEMPERATURE -> "Intermediate temperature; measurement is still in progress"
            else -> null
        })
    }

    private fun oxygen(p: Packet, field: BleField): WearableReading {
        val flags = p.u8(); require(flags and 0xe0 == 0)
        val values = mutableListOf<WearableValue>()
        values.addFinite(WearableMetric.OXYGEN, p.sfloat())
        values.addFinite(WearableMetric.PULSE_RATE, p.sfloat())
        val info = linkedMapOf<String, String>()
        val spot = field == BleField.OXYGEN_SPOT
        if (spot && flags and 1 != 0) info["Device time (unverified)"] = p.dateTime()
        if (!spot && flags and 1 != 0) {
            values.addFinite(WearableMetric.OXYGEN_FAST, p.sfloat())
            values.addFinite(WearableMetric.PULSE_FAST, p.sfloat())
        }
        if (!spot && flags and 2 != 0) {
            values.addFinite(WearableMetric.OXYGEN_SLOW, p.sfloat())
            values.addFinite(WearableMetric.PULSE_SLOW, p.sfloat())
        }
        val status = if (flags and (if (spot) 2 else 4) != 0) p.u16() else 0
        val sensor = if (flags and (if (spot) 4 else 8) != 0) p.u24() else 0
        if (flags and (if (spot) 8 else 16) != 0) values.addFinite(WearableMetric.PERFUSION, p.sfloat())
        p.end()
        // Test/demo/calibration, unavailable, questionable, invalid and poor/off-user sensor signals.
        val unusable = status and 0xfc00 != 0 || sensor and 0xe81a != 0
        val note = if (status != 0 || sensor != 0)
            "Oximeter status 0x${status.toString(16)}, sensor 0x${sensor.toString(16)}${if (unusable) "; readings withheld" else ""}"
        else if (spot && flags and 16 != 0) "Oximeter clock is not set"
        else if (values.isEmpty()) "Oximeter did not provide usable readings" else null
        return WearableReading(if (unusable) emptyList() else values, info, note)
    }

    private fun pressure(p: Packet): WearableReading {
        val flags = p.u8(); require(flags and 0xe0 == 0)
        val factor = if (flags and 1 != 0) 7.500616827 else 1.0
        val values = mutableListOf<WearableValue>()
        values.addFinite(WearableMetric.SYSTOLIC, p.sfloat()?.times(factor))
        values.addFinite(WearableMetric.DIASTOLIC, p.sfloat()?.times(factor))
        values.addFinite(WearableMetric.MEAN_PRESSURE, p.sfloat()?.times(factor))
        val info = linkedMapOf<String, String>()
        if (flags and 2 != 0) info["Device time (unverified)"] = p.dateTime()
        if (flags and 4 != 0) values.addFinite(WearableMetric.PULSE_RATE, p.sfloat())
        val user = if (flags and 8 != 0) p.u8() else null
        val status = if (flags and 16 != 0) p.u16() else 0
        p.end()
        // Multi-user monitors cannot safely be attributed to the wearer without user selection.
        val unusable = status and 0x23 != 0 || user != null && user != 255
        val note = when {
            user != null && user != 255 -> "Blood pressure belongs to device user $user; readings withheld until user attribution is supported"
            status != 0 -> "Blood-pressure status 0x${status.toString(16)}${if (unusable) "; readings withheld" else ""}"
            values.isEmpty() -> "Blood-pressure monitor did not provide usable readings"
            else -> null
        }
        return WearableReading(if (unusable) emptyList() else values, info, note)
    }

    private fun MutableList<WearableValue>.addFinite(metric: WearableMetric, value: Double?) {
        if (value != null && value.isFinite() && value in metric.low..metric.high) add(WearableValue(metric, value))
    }

    private class Packet(private val bytes: ByteArray) {
        private var offset = 0
        val remaining get() = bytes.size - offset
        fun u8(): Int { require(remaining >= 1); return bytes[offset++].toInt() and 255 }
        fun u16() = u8() or (u8() shl 8)
        fun u24() = u16() or (u8() shl 16)
        fun sfloat(): Double? {
            val raw = u16()
            if (raw in setOf(0x07fe, 0x07ff, 0x0800, 0x0801, 0x0802)) return null
            val mantissa = (raw and 0xfff).let { if (it and 0x800 != 0) it - 0x1000 else it }
            val exponent = (raw ushr 12).let { if (it >= 8) it - 16 else it }
            return mantissa * 10.0.pow(exponent)
        }
        fun float(): Double? {
            val mantissaRaw = u24()
            val exponentRaw = u8()
            if (exponentRaw == 0 && mantissaRaw in setOf(0x7ffffe, 0x7fffff, 0x800000, 0x800001, 0x800002)) return null
            val mantissa = if (mantissaRaw and 0x800000 != 0) mantissaRaw - 0x1000000 else mantissaRaw
            val exponent = if (exponentRaw >= 128) exponentRaw - 256 else exponentRaw
            return mantissa * 10.0.pow(exponent)
        }
        fun dateTime(): String {
            val year = u16(); val month = u8(); val day = u8(); val hour = u8(); val minute = u8(); val second = u8()
            require(month in 0..12 && day in 0..31 && hour in 0..23 && minute in 0..59 && second in 0..59)
            return "%04d-%02d-%02d %02d:%02d:%02d (no timezone)".format(year, month, day, hour, minute, second)
        }
        fun end() { require(remaining == 0) }
    }
}
