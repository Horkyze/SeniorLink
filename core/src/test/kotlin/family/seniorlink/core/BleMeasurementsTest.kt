package family.seniorlink.core

import kotlin.test.*

class BleMeasurementsTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun decode(field: BleField, vararg values: Int) = assertNotNull(BleMeasurements.decode(field, bytes(*values)))
    private fun WearableReading.value(metric: WearableMetric) = values.single { it.metric == metric }.value

    @Test fun `Fit3 heart rate packet decodes contact correctly`() {
        val result = decode(BleField.HEART_RATE, 0x06, 84)
        assertEquals(84.0, result.value(WearableMetric.HEART_RATE))
        assertEquals(1.0, result.value(WearableMetric.CONTACT))
        val unsupported = decode(BleField.HEART_RATE, 0x02, 72)
        assertFalse(unsupported.values.any { it.metric == WearableMetric.CONTACT })
        val offWrist = decode(BleField.HEART_RATE, 0x14, 72, 0x00, 0x04)
        assertEquals(listOf(WearableValue(WearableMetric.CONTACT, 0.0)), offWrist.values)
        assertNotNull(offWrist.note)
    }

    @Test fun `16 bit HR energy and RR offsets are decoded without overwriting pulse`() {
        val result = decode(BleField.HEART_RATE, 0x1f, 0x02, 0x01, 0xff, 0xff, 0x00, 0x04, 0x00, 0x03)
        assertEquals(258.0, result.value(WearableMetric.HEART_RATE))
        assertEquals(65535.0, result.value(WearableMetric.ENERGY))
        assertEquals(listOf(1000.0, 750.0), result.values.filter { it.metric == WearableMetric.RR_INTERVAL }.map { it.value })
    }

    @Test fun `all HR optional field combinations require complete packets`() {
        for (flags in 0..31) {
            val packet = mutableListOf(flags, 72)
            if (flags and 1 != 0) packet += 0
            if (flags and 8 != 0) packet += listOf(12, 0)
            if (flags and 16 != 0) packet += listOf(0, 4)
            assertNotNull(BleMeasurements.decode(BleField.HEART_RATE, bytes(*packet.toIntArray())), "flags $flags")
            assertNull(BleMeasurements.decode(BleField.HEART_RATE, bytes(*packet.dropLast(1).toIntArray())), "truncated flags $flags")
        }
        assertNull(BleMeasurements.decode(BleField.HEART_RATE, bytes(0x10, 72, 1)))
        assertNull(BleMeasurements.decode(BleField.HEART_RATE, bytes(0x20, 72)))
        assertNull(BleMeasurements.decode(BleField.HEART_RATE, bytes(0, 72, 1)))
    }

    @Test fun `battery RFU values and extra bytes are not percentages`() {
        assertEquals(100.0, decode(BleField.BATTERY, 100).value(WearableMetric.BATTERY))
        for (value in listOf(101, 255)) assertNull(BleMeasurements.decode(BleField.BATTERY, bytes(value)))
        assertNull(BleMeasurements.decode(BleField.BATTERY, bytes(80, 0)))
    }

    @Test fun `IEEE decimal temperature handles signed exponents mantissas and Fahrenheit`() {
        assertEquals(36.4, decode(BleField.TEMPERATURE, 0, 0x6c, 1, 0, 0xff).value(WearableMetric.TEMPERATURE), 0.00001)
        assertEquals(-1.0, decode(BleField.TEMPERATURE, 0, 0xf6, 0xff, 0xff, 0xff).value(WearableMetric.TEMPERATURE), 0.00001)
        assertEquals(37.0, decode(BleField.TEMPERATURE, 1, 0xda, 3, 0, 0xff).value(WearableMetric.TEMPERATURE), 0.00001)
        for (raw in listOf(0x7ffffe, 0x7fffff, 0x800000, 0x800001, 0x800002)) {
            val reading = decode(BleField.TEMPERATURE, 0, raw and 255, (raw shr 8) and 255, (raw shr 16) and 255, 0)
            assertTrue(reading.values.isEmpty())
            assertNotNull(reading.note)
        }
        // A reserved mantissa with a nonzero exponent is a finite decimal number.
        assertEquals(83.88607, decode(BleField.TEMPERATURE, 0, 0xff, 0xff, 0x7f, 0xfb).value(WearableMetric.TEMPERATURE), 0.00001)
    }

    @Test fun `temperature timestamp and site are retained without fabricating timezone`() {
        val packet = bytes(6, 0x6c, 1, 0, 0xff, 0xea, 7, 9, 8, 12, 30, 0, 6)
        val result = assertNotNull(BleMeasurements.decode(BleField.TEMPERATURE, packet))
        assertEquals("Mouth", result.information["Temperature site"])
        assertEquals("2026-09-08 12:30:00 (no timezone)", result.information["Device time (unverified)"])
        for (length in 0 until packet.size) assertNull(BleMeasurements.decode(BleField.TEMPERATURE, packet.copyOf(length)))
        assertNotNull(decode(BleField.INTERMEDIATE_TEMPERATURE, 0, 0x6c, 1, 0, 0xff).note)
    }

    @Test fun `PLX continuous flags preserve fast slow and amplitude values`() {
        val result = decode(BleField.OXYGEN_CONTINUOUS, 0x1f,
            98, 0, 70, 0, 97, 0, 71, 0, 99, 0, 69, 0,
            0x80, 0, 1, 0, 0, 25, 0xf0)
        assertEquals(98.0, result.value(WearableMetric.OXYGEN))
        assertEquals(71.0, result.value(WearableMetric.PULSE_FAST))
        assertEquals(99.0, result.value(WearableMetric.OXYGEN_SLOW))
        assertEquals(2.5, result.value(WearableMetric.PERFUSION))
    }

    @Test fun `PLX invalid test demonstration and off-user signals are withheld`() {
        for (status in listOf(0x0400, 0x0800, 0x1000, 0x2000, 0x4000, 0x8000)) {
            val result = decode(BleField.OXYGEN_CONTINUOUS, 4, 98, 0, 70, 0, status and 255, status shr 8)
            assertTrue(result.values.isEmpty(), "status $status")
            assertContains(assertNotNull(result.note), "withheld")
        }
        val offUser = decode(BleField.OXYGEN_SPOT, 4, 98, 0, 70, 0, 0, 8, 0)
        assertTrue(offUser.values.isEmpty())
        // Device status is 24-bit: third byte must not be mistaken for amplitude index.
        val withStatus = decode(BleField.OXYGEN_SPOT, 0xc, 98, 0, 70, 0, 0, 0, 1, 25, 0xf0)
        assertEquals(2.5, withStatus.value(WearableMetric.PERFUSION))
    }

    @Test fun `PLX optional flags reject every truncation atomically`() {
        for (flags in 0..31) {
            for (field in listOf(BleField.OXYGEN_SPOT, BleField.OXYGEN_CONTINUOUS)) {
                val packet = mutableListOf(flags, 98, 0, 70, 0)
                if (field == BleField.OXYGEN_SPOT) {
                    if (flags and 1 != 0) packet += listOf(0xea, 7, 9, 8, 12, 0, 0)
                    if (flags and 2 != 0) packet += listOf(0, 0)
                    if (flags and 4 != 0) packet += listOf(0, 0, 0)
                    if (flags and 8 != 0) packet += listOf(25, 0xf0)
                } else {
                    if (flags and 1 != 0) packet += listOf(97, 0, 71, 0)
                    if (flags and 2 != 0) packet += listOf(99, 0, 69, 0)
                    if (flags and 4 != 0) packet += listOf(0, 0)
                    if (flags and 8 != 0) packet += listOf(0, 0, 0)
                    if (flags and 16 != 0) packet += listOf(25, 0xf0)
                }
                val data = bytes(*packet.toIntArray())
                assertNotNull(BleMeasurements.decode(field, data), "$field flags $flags")
                for (length in 0 until data.size) assertNull(BleMeasurements.decode(field, data.copyOf(length)), "$field flags $flags length $length")
            }
        }
    }

    @Test fun `SFLOAT special values are omitted independently`() {
        for (raw in listOf(0x07fe, 0x07ff, 0x0800, 0x0801, 0x0802)) {
            val result = decode(BleField.OXYGEN_CONTINUOUS, 0, raw and 255, raw shr 8, 70, 0)
            assertEquals(listOf(WearableValue(WearableMetric.PULSE_RATE, 70.0)), result.values)
        }
        assertEquals(20.47, decode(BleField.OXYGEN_CONTINUOUS, 0, 0xff, 0xe7, 70, 0).value(WearableMetric.OXYGEN), 0.00001)
    }

    @Test fun `blood pressure kPa is normalized and quality or attributed users are respected`() {
        val result = decode(BleField.BLOOD_PRESSURE, 1, 16, 0, 10, 0, 12, 0)
        assertEquals(120.009869232, result.value(WearableMetric.SYSTOLIC), 0.0001)
        assertTrue(decode(BleField.BLOOD_PRESSURE, 0x10, 120, 0, 80, 0, 90, 0, 1, 0).values.isEmpty())
        assertTrue(decode(BleField.BLOOD_PRESSURE, 8, 120, 0, 80, 0, 90, 0, 2).values.isEmpty())
        assertEquals(3, decode(BleField.BLOOD_PRESSURE, 8, 120, 0, 80, 0, 90, 0, 255).values.size)
        for (flags in 0..31) {
            val packet = mutableListOf(flags, 120, 0, 80, 0, 90, 0)
            if (flags and 2 != 0) packet += listOf(0xea, 7, 9, 8, 12, 0, 0)
            if (flags and 4 != 0) packet += listOf(70, 0)
            if (flags and 8 != 0) packet += 255
            if (flags and 16 != 0) packet += listOf(0, 0)
            val data = bytes(*packet.toIntArray())
            assertNotNull(BleMeasurements.decode(BleField.BLOOD_PRESSURE, data))
            for (length in 0 until data.size) assertNull(BleMeasurements.decode(BleField.BLOOD_PRESSURE, data.copyOf(length)))
        }
    }

    @Test fun `discovery matches service as well as characteristic and text is bounded`() {
        assertEquals(BleField.HEART_RATE, BleField.find(BleField.uuid(0x180d), BleField.uuid(0x2a37)))
        assertNull(BleField.find(BleField.uuid(0x180f), BleField.uuid(0x2a37)))
        assertNull(BleField.find("arbitrary-service", BleField.uuid(0x2a37)))
        val info = assertNotNull(BleMeasurements.decode(BleField.MANUFACTURER, ("Samsung\u0000\n" + "A".repeat(200)).toByteArray()))
        assertEquals(100, info.information.getValue("Manufacturer").length)
        assertFalse(info.information.getValue("Manufacturer").any { it.isISOControl() })
        assertNull(BleMeasurements.decode(BleField.MODEL, ByteArray(513)))
    }
}
