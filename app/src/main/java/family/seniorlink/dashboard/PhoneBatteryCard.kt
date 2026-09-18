package family.seniorlink.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import family.seniorlink.data.StoredEvent
import family.seniorlink.ui.Calm

@Composable
internal fun PhoneBatteryCard(events: List<StoredEvent>, now: Long, missing: String,
    compact: Boolean = false, onClick: (() -> Unit)? = null) {
    var hours by rememberSaveable { mutableIntStateOf(1) }
    val latest = events.lastOrNull()?.event
    val battery = latest?.phoneBattery
    val start = now - (if (compact) 1 else hours) * 3_600_000L
    val points = batteryPoints(events).filter { it.at in start..now }
    val low = battery != null && battery.percent <= 20
    val accent = if (low) Calm.Low else Calm.Green
    val modifier = Modifier.fillMaxWidth().testTag("phone-battery-card")
    Card(if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
            containerColor = if (low) Calm.LowBackground else Color.White, contentColor = Calm.Ink)) {
        val value: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phone battery", fontWeight = FontWeight.SemiBold)
                Text(battery?.let { "${it.percent}%" } ?: "Unknown", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = accent)
                if (latest != null && battery != null) {
                    Text(chargingLabel(battery.charging), style = MaterialTheme.typography.bodyMedium)
                    Text("Recorded ${formatReadingTime(latest.occurredAt, now)}", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                    if (now - latest.occurredAt > 600_000) Text("No recent reading", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                    if (low) Text("Low battery", style = MaterialTheme.typography.labelMedium, color = Calm.Low)
                } else Text(missing, style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            }
        }
        val chart: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (points.isNotEmpty()) key(hours) {
                    ReadingGraph(points, start, now, accent, height = if (compact) 60 else 160,
                        labels = !compact, interactive = !compact, metric = ChartMetric.PHONE_BATTERY)
                }
                Text(if (points.isEmpty()) "No readings in the last ${if (compact || hours == 1) "hour" else "24 hours"}"
                    else "${if (compact || hours == 1) "Last hour" else "Last 24 hours"} · Saved trend",
                    style = MaterialTheme.typography.labelSmall, color = Calm.Muted)
            }
        }
        if (compact && LocalDensity.current.fontScale <= 1.3f) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { value() }
                Column(Modifier.weight(1f)) { chart() }
            }
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            value()
            chart()
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 hour", 24 to "24 hours").forEach { (value, label) ->
                    FilterChip(selected = hours == value, onClick = { hours = value }, label = { Text(label) },
                        modifier = Modifier.testTag("battery-hours-$value"))
                }
            }
        }
    }
}
