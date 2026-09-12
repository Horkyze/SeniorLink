package family.seniorlink.monitor

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import family.seniorlink.core.PhoneBattery
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneBatteryMonitorTest {
    private fun intent(level: Int = 75, scale: Int = 100, status: Int = BatteryManager.BATTERY_STATUS_CHARGING) =
        Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale).putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test fun validBatteryHandlesNonPercentageScaleZeroAndUnknownCharging() {
        assertEquals(PhoneBattery(75, true), PhoneBatteryMonitor.decode(intent(150, 200)))
        assertEquals(PhoneBattery(0, false), PhoneBatteryMonitor.decode(intent(0, status = BatteryManager.BATTERY_STATUS_DISCHARGING)))
        assertEquals(PhoneBattery(100, true), PhoneBatteryMonitor.decode(intent(100, status = BatteryManager.BATTERY_STATUS_FULL)))
        assertEquals(PhoneBattery(75, null), PhoneBatteryMonitor.decode(intent(status = -1)))
    }

    @Test fun unavailableAndInvalidDataNeverBecomeZeroOrFull() {
        listOf(null, Intent(Intent.ACTION_BATTERY_CHANGED), intent(-1), intent(101), intent(scale = 0),
            intent().putExtra(BatteryManager.EXTRA_PRESENT, false), Intent("wrong")).forEach {
            assertNull(PhoneBatteryMonitor.decode(it))
        }
    }

    @Test fun disabledGateDoesNotEvenReadAndPauseStopsFurtherSamples() = runTest {
        var enabled = false
        var reads = 0
        val saved = mutableListOf<PhoneBattery>()
        val monitor = PhoneBatteryMonitor(300_000)
        val read = { reads++; PhoneBattery(75) }
        monitor.run({ enabled }, read, saved::add)
        assertEquals(0, reads)
        enabled = true
        val job = launch { monitor.run({ enabled }, read, saved::add) }
        runCurrent()
        assertEquals(1, saved.size)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, saved.size)
        enabled = false
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, reads)
        assertTrue(job.isCompleted)
    }

    @Test fun aCancelledSessionCannotPublishWhenTheGateReopens() = runTest {
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            PhoneBatteryMonitor(1).run({ true }, { job.cancel(); PhoneBattery(42) }, { fail("Cancelled session saved a reading") })
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun withdrawalDuringReadDropsTheResult() = runTest {
        var enabled = true
        PhoneBatteryMonitor(1).run({ enabled }, { enabled = false; PhoneBattery(42) }, { fail("Saved after withdrawal") })
    }
}
