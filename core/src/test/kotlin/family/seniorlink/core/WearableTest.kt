package family.seniorlink.core

import kotlin.test.*

class WearableTest {
    @Test fun `summaries retain range average last and timestamps without retaining every packet`() {
        val accumulator = WearableAccumulator("Fit3")
        for ((time, bpm) in listOf(100L to 60.0, 200L to 90.0, 300L to 75.0))
            accumulator.add(WearableReading(listOf(WearableValue(WearableMetric.HEART_RATE, bpm))), time)
        val summary = assertNotNull(accumulator.drain())
        summary.validate(300)
        assertEquals(WearableMetricSummary(WearableMetric.HEART_RATE, 100, 300, 3, 60.0, 90.0, 75.0, 75.0), summary.metrics.single())
        assertNull(accumulator.drain())
    }

    @Test fun `malformed metric values do not contaminate summaries`() {
        val accumulator = WearableAccumulator("Fit3")
        accumulator.add(WearableReading(listOf(WearableValue(WearableMetric.BATTERY, Double.NaN),
            WearableValue(WearableMetric.BATTERY, 255.0))), 100)
        assertNull(accumulator.drain())
    }

    @Test fun `new wire payload round trips and rejects unbounded or misattributed fields`() {
        val metric = WearableMetricSummary(WearableMetric.HEART_RATE, 100, 300, 3, 60.0, 90.0, 75.0, 75.0)
        val payload = WearableSummary("Fit3", listOf(metric), mapOf("Manufacturer" to "Samsung"))
        val event = Event(1, Kind.WEARABLE, 300, wearable = payload)
        event.validate()
        assertEquals(event, Wire.decode<Event>(Wire.encode(event), Wire.MAX_RESPONSE))
        assertFailsWith<IllegalArgumentException> { event.copy(kind = Kind.UNLOCK).validate() }
        assertFailsWith<IllegalArgumentException> { event.copy(wearable = null).validate() }
        assertFailsWith<IllegalArgumentException> { event.copy(sender = "bad").validate() }
        assertFailsWith<IllegalArgumentException> { payload.copy(metrics = listOf(metric, metric)).validate(300) }
        assertFailsWith<IllegalArgumentException> { payload.copy(information = mapOf("Bluetooth address" to "AA:BB:CC:DD:EE:FF")).validate(300) }
        for (bad in listOf(metric.copy(average = Double.NaN), metric.copy(count = 0), metric.copy(lastAt = 301),
            metric.copy(minimum = 91.0), metric.copy(latest = 99.0), metric.copy(firstAt = 0))) {
            assertFailsWith<IllegalArgumentException> { payload.copy(metrics = listOf(bad)).validate(300) }
        }
    }

    @Test fun `existing saved settings and events remain readable with wearable disabled`() {
        val settings = Wire.json.decodeFromString<Settings>("""{"role":"SHARER","unlock":true}""")
        assertFalse(settings.wearable)
        assertFalse(settings.allows(Kind.WEARABLE))
        assertTrue(settings.copy(wearable = true).allows(Kind.WEARABLE))
        val event = Wire.json.decodeFromString<Event>("""{"sequence":1,"kind":"UNLOCK","occurredAt":100}""")
        event.validate()
        assertNull(event.wearable)
        assertEquals(3, Wire.VERSION)
    }
}
