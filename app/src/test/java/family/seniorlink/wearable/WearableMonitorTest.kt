package family.seniorlink.wearable

import family.seniorlink.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableMonitorTest {
    private val hr = BleAttribute("hr", BleField.uuid(0x180d), BleField.uuid(0x2a37), 16)
    private val battery = BleAttribute("battery", BleField.uuid(0x180f), BleField.uuid(0x2a19), 2)
    private val model = BleAttribute("model", BleField.uuid(0x180a), BleField.uuid(0x2a24), 2)
    private val vendor = BleAttribute("vendor", "11111111-1111-1111-1111-111111111111", BleField.uuid(0x2a37), 18)

    @Test fun `discovers only known pairs receives live samples and saves bounded summaries`() = runTest {
        val link = FakeLink(listOf(hr, battery, model, vendor))
        val saved = mutableListOf<WearableSummary>()
        var state = WearableState()
        val monitor = WearableMonitor({ link }, { 1000 + testScheduler.currentTime }, intervalMs = 1000, pollMs = 5000)
        val job = launch { monitor.run("AA:BB:CC:DD:EE:FF", "Fit3", { true }, { state = it }, { data, _ -> saved += data }) }
        runCurrent()
        assertEquals(listOf(hr), link.subscriptions)
        assertEquals(listOf(battery, model), link.reads)
        assertTrue(state.connected)
        assertEquals("Test band", state.information["Model"])
        link.send(hr, 6, 72)
        runCurrent()
        assertEquals(72.0, state.latest.single { it.metric == WearableMetric.HEART_RATE }.value, 0.0)
        link.send(hr, 6, 90)
        link.send(hr, 6, 60)
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        val metric = saved.flatMap { it.metrics }.last { it.metric == WearableMetric.HEART_RATE }
        assertEquals(2, metric.count)
        assertEquals(60.0, metric.minimum, 0.0)
        assertEquals(90.0, metric.maximum, 0.0)
        assertEquals(75.0, metric.average, 0.0)
        job.cancelAndJoin()
        assertTrue(link.closed)
    }

    @Test fun `pause closes the link and discards any unsaved private readings`() = runTest {
        val link = FakeLink(listOf(hr))
        val saved = mutableListOf<WearableSummary>()
        var enabled = true
        var state = WearableState()
        val job = launch {
            WearableMonitor({ link }, { 1000 + testScheduler.currentTime }, intervalMs = 1000)
                .run("AA:BB:CC:DD:EE:FF", "Fit3", { enabled }, { state = it }, { data, _ -> saved += data })
        }
        runCurrent()
        link.send(hr, 6, 72); runCurrent()
        assertEquals(1, saved.size)
        link.send(hr, 6, 99); runCurrent()
        enabled = false
        job.cancelAndJoin()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, saved.size)
        assertTrue(link.closed)
        assertFalse(state.connected)
        assertEquals("Wearable collection is paused", state.status)
    }

    @Test fun `disconnect retries with a new connection and ignores old callbacks`() = runTest {
        val links = listOf(FakeLink(listOf(hr)), FakeLink(listOf(hr)))
        var count = 0
        var state = WearableState()
        val job = launch {
            WearableMonitor({ links[count++] }, { 1000 + testScheduler.currentTime }, retryMs = 100)
                .run("AA:BB:CC:DD:EE:FF", "Fit3", { true }, { state = it }, { _, _ -> })
        }
        runCurrent()
        links[0].values.close(BleOperationException("Link lost")); runCurrent()
        assertTrue(links[0].closed)
        assertFalse(state.connected)
        advanceTimeBy(100); runCurrent()
        assertEquals(2, count)
        links[1].send(hr, 6, 75); runCurrent()
        assertTrue(state.connected)
        assertEquals(75.0, state.latest.single { it.metric == WearableMetric.HEART_RATE }.value, 0.0)
        assertTrue(links[0].values.trySend(BleValue(hr, byteArrayOf(6, 200.toByte()))).isFailure)
        job.cancelAndJoin()
        assertTrue(links[1].closed)
    }

    @Test fun `bad packets and failed optional reads do not stop valid heart rate collection`() = runTest {
        val link = FakeLink(listOf(battery, hr)).apply { failReads = true }
        var state = WearableState()
        val job = launch {
            WearableMonitor({ link }).run("AA:BB:CC:DD:EE:FF", "Fit3", { true }, { state = it }, { _, _ -> })
        }
        runCurrent()
        link.send(hr, 0x11, 50); runCurrent()
        assertTrue(state.latest.isEmpty())
        assertTrue(state.issues.any { it.contains("malformed") })
        link.send(hr, 6, 80); runCurrent()
        assertEquals(80.0, state.latest.single { it.metric == WearableMetric.HEART_RATE }.value, 0.0)
        link.send(hr, 4, 80); runCurrent()
        assertFalse(state.latest.any { it.metric == WearableMetric.HEART_RATE })
        assertEquals(0.0, state.latest.single { it.metric == WearableMetric.CONTACT }.value, 0.0)
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }

    @Test fun `revoked permission stops retry and closes the device`() = runTest {
        val link = FakeLink(listOf(hr)).apply { denied = true }
        var count = 0
        var state = WearableState()
        WearableMonitor({ count++; link }).run("AA:BB:CC:DD:EE:FF", "Fit3", { true }, { state = it }, { _, _ -> })
        assertEquals(1, count)
        assertTrue(link.closed)
        assertFalse(state.connected)
        assertTrue(state.status.contains("permission was revoked"))
    }

    private inner class FakeLink(private val attributes: List<BleAttribute>) : BleLink {
        override val values = Channel<BleValue>(256)
        val reads = mutableListOf<BleAttribute>()
        val subscriptions = mutableListOf<BleAttribute>()
        var closed = false
        var failReads = false
        var denied = false
        override suspend fun discover(): List<BleAttribute> {
            if (denied) throw SecurityException()
            return attributes
        }
        override suspend fun read(attribute: BleAttribute): ByteArray {
            reads += attribute
            if (failReads) throw BleOperationException("Read unavailable")
            return when (attribute) { battery -> byteArrayOf(80); model -> "Test band".toByteArray(); else -> error("Unknown read") }
        }
        override suspend fun subscribe(attribute: BleAttribute) { subscriptions += attribute }
        fun send(attribute: BleAttribute, vararg bytes: Int) { check(values.trySend(BleValue(attribute, bytes.map { it.toByte() }.toByteArray())).isSuccess) }
        override fun close() { closed = true; values.close() }
    }
}
