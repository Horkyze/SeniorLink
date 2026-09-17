package family.seniorlink.net

import family.seniorlink.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCatchUpTest {
    private val source = "a".repeat(64)
    private class MemoryInbox : Inbox {
        var saved = 0L
        var failSave = false
        override fun cursor(source: String) = saved
        override fun commit(batch: Batch, receivedAt: Long) {
            check(!failSave) { "Storage unavailable" }
            saved = batch.through
        }
    }

    private fun exchange(inbox: MemoryInbox, latest: Long, requests: MutableList<Long>,
        beforePull: suspend (Long) -> Unit = {}): Exchange = object : Exchange {
        override suspend fun pull(after: Long): Batch {
            requests += after
            beforePull(after)
            val through = minOf(after + Wire.PAGE_SIZE, latest)
            return Batch(source = source, through = through, latest = latest, earliest = 1,
                events = (after + 1..through).map { Event(it, Kind.CHECK_IN, 100) })
        }
        override suspend fun acknowledge(through: Long): Receipt {
            assertEquals("ACK before durable save", through, inbox.saved)
            return Receipt(through = through)
        }
    }

    @Test fun `whole history uses one exchange without an inter-page delay`() = runTest {
        val inbox = MemoryInbox()
        val pulls = mutableListOf<Long>()
        var progress = 0
        assertFalse(catchUpConnection(source, inbox, exchange(inbox, 10_000, pulls), { 200 }) { progress++ })
        assertEquals(10_000L, inbox.saved)
        assertEquals((0L until 10_000L step 20).toList(), pulls)
        assertEquals(499, progress)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `old server closing after each page reconnects from saved cursor`() = runTest {
        val inbox = MemoryInbox()
        val pulls = mutableListOf<Long>()
        var connections = 0
        do {
            var served = false
            val legacy = exchange(inbox, 45, pulls) {
                check(!served) { "Legacy server closed connection" }
                served = true
            }
            connections++
        } while (catchUpConnection(source, inbox, legacy))
        assertEquals(3, connections)
        assertEquals(45L, inbox.saved)
        assertEquals(listOf(0L, 20L, 20L, 40L, 40L), pulls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `failed fresh session propagates to reconnect backoff`() = runTest {
        val inbox = MemoryInbox()
        val failed = exchange(inbox, 40, mutableListOf()) { error("Offline") }
        try { catchUpConnection(source, inbox, failed); fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertEquals(0L, inbox.saved)
    }

    @Test fun `each page has its own timeout so a progressing history can exceed 35 seconds`() = runTest {
        val inbox = MemoryInbox()
        val slow = exchange(inbox, 60, mutableListOf()) { delay(20_000) }
        assertFalse(catchUpConnection(source, inbox, slow))
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(60L, inbox.saved)
    }

    @Test fun `stalled page times out and fresh session failure is not hidden`() = runTest {
        val inbox = MemoryInbox()
        val stalled = exchange(inbox, 40, mutableListOf()) { awaitCancellation() }
        try { catchUpConnection(source, inbox, stalled); fail("Expected timeout") }
        catch (_: TimeoutCancellationException) { }
        assertEquals(35_000L, testScheduler.currentTime)
    }

    @Test fun `lifecycle cancellation after progress never becomes a reconnect`() = runTest {
        val inbox = MemoryInbox()
        var returned = false
        val job = launch {
            catchUpConnection(source, inbox, exchange(inbox, 60, mutableListOf()) {
                if (it > 0) awaitCancellation()
            })
            returned = true
        }
        testScheduler.runCurrent()
        assertEquals(20L, inbox.saved)
        job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun `failed save after a page never acknowledges missing records`() = runTest {
        val inbox = MemoryInbox()
        val pulls = mutableListOf<Long>()
        assertTrue(catchUpConnection(source, inbox, exchange(inbox, 60, pulls)) { inbox.failSave = true })
        assertEquals(20L, inbox.saved)
        inbox.failSave = false
        assertFalse(catchUpConnection(source, inbox, exchange(inbox, 60, pulls)))
        assertEquals(listOf(0L, 20L, 20L, 40L), pulls)
    }
}
