package family.seniorlink.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import family.seniorlink.core.PhoneBattery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Samples only inside the existing visible sharing session, with explicit feature consent. */
internal class PhoneBatteryMonitor(private val intervalMs: Long = 300_000) {
    suspend fun run(enabled: () -> Boolean, read: () -> PhoneBattery?, onReading: (PhoneBattery) -> Unit) {
        while (currentCoroutineContext().isActive && enabled()) {
            read()?.let { if (currentCoroutineContext().isActive && enabled()) onReading(it) }
            delay(intervalMs)
        }
    }

    companion object {
        fun read(context: Context): PhoneBattery? = decode(
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)),
        )

        fun decode(intent: Intent?): PhoneBattery? {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED ||
                !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale <= 0 || level !in 0..scale) return null
            val charging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
            return PhoneBattery((level * 100.0 / scale).roundToInt(), charging)
        }
    }
}
