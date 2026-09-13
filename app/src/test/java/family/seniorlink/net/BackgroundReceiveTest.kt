package family.seniorlink.net

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundReceiveTest {
    @Test fun screenAndServiceShareOneReceiverAndBackgroundPollingIsSlower() = runTest {
        var starts = 0
        var stops = 0
        var poll: (() -> Long)? = null
        val receiver = CaregiverReceiver(backgroundScope) { interval ->
            starts++
            poll = interval
            try { awaitCancellation() } finally { stops++ }
        }
        runCurrent()
        assertEquals(0, starts)
        receiver.visible(true)
        runCurrent()
        assertEquals(15_000L, poll!!())
        receiver.background(true)
        receiver.visible(false)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(0, stops)
        assertEquals(60_000L, poll!!())
        receiver.visible(true)
        receiver.background(false)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(15_000L, poll!!())
        receiver.visible(false)
        runCurrent()
        assertEquals(1, stops)
    }

    @Test fun idleAndOfflineWindowsStopTransportAndRecoveryWaitsForCleanup() = runTest {
        val windows = MutableStateFlow(NetworkWindow(null, false))
        var active = 0
        var starts = 0
        var waiting = 0
        backgroundScope.launch {
            windows.whileAvailable(waiting = { waiting++ }) {
                starts++
                active++
                assertEquals("No overlapping endpoints", 1, active)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(100); active-- } }
            }
        }
        runCurrent()
        assertEquals(1, waiting)
        assertEquals(0, starts)
        windows.value = NetworkWindow(null, true)
        runCurrent()
        assertEquals(1, starts)
        windows.value = NetworkWindow(null, true)
        runCurrent()
        assertEquals(1, starts)
        windows.value = NetworkWindow(null, false)
        runCurrent()
        windows.value = NetworkWindow(null, true)
        runCurrent()
        assertEquals(1, starts)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, active)
        windows.value = NetworkWindow(null, false)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, active)
    }
}
