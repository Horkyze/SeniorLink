package family.seniorlink.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import family.seniorlink.R
import family.seniorlink.ui.Calm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import family.seniorlink.ScreenState
import family.seniorlink.core.Role
import family.seniorlink.core.WearableMetric
import family.seniorlink.formatTime
import family.seniorlink.wearable.WearableState
import family.seniorlink.wearable.formatWearable
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun rememberReadingTime(vararg readings: Any?): Long {
    var now by remember(*readings) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(*readings) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HealthOverview(
    state: ScreenState,
    live: WearableState,
    onWearable: () -> Unit,
    selectedSource: String? = null,
    onSelectSource: ((String) -> Unit)? = null,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val sharer = state.settings.role == Role.SHARER
    val source = if (sharer) state.publicId else state.peers.firstOrNull { it.id == (selectedSource ?: selected) }?.id ?: state.peers.firstOrNull()?.id
    val peer = state.peers.firstOrNull { it.id == source }
    val now = rememberReadingTime(state, live)
    val snapshot = remember(state, live, source) { wearableSnapshot(state, live, source) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (sharer) "YOUR UPDATES" else "FAMILY UPDATES", color = Calm.Muted,
                fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text(if (sharer) "Your health & devices" else peer?.let { "How is ${it.name}?" } ?: "Your family updates",
                fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Calm.Ink)
            if (!sharer) Text(if (peer == null || peer.lastContact == 0L) "No connection yet"
                else "Last connected at ${formatReadingTime(peer.lastContact, now)}", color = Calm.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (!sharer && state.peers.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.peers.forEach { family ->
                    FilterChip(selected = source == family.id, onClick = {
                        selected = family.id; onSelectSource?.invoke(family.id)
                    }, label = { Text(family.name) })
                }
            }
        }
        if (!sharer && state.peers.isEmpty()) Text("Connect a family phone in Phones to see shared readings.")
        key(source, snapshot.events.firstOrNull()?.event?.wearable?.deviceId) { HeartRateCard(snapshot, now) }
        Text("Device batteries", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = Calm.Ink)
        DeviceBatteries(state, source, snapshot, now)
        Text("Recorded readings · Heart-rate trend, not an ECG", Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, color = Calm.Muted, textAlign = TextAlign.Center)
        TextButton(onClick = onWearable, modifier = Modifier.align(Alignment.End)) { Text("View wearable details") }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HeartRateCard(snapshot: WearableSnapshot, now: Long) {
    var hours by rememberSaveable { mutableIntStateOf(1) }
    val start = now - hours * 3_600_000L
    val points = snapshot.points.filter { it.at in start..now }
    val pulse = snapshot.values.firstOrNull { it.metric == WearableMetric.HEART_RATE }
    val noContact = snapshot.values.any { it.metric == WearableMetric.CONTACT && it.value == 0.0 }
    Card(colors = CardDefaults.cardColors(containerColor = Calm.Heart, contentColor = Calm.Ink), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(painterResource(R.drawable.ic_heart_outline), null, Modifier.size(19.dp))
                    Text("Heart rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(snapshot.name, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            }
            val pulseText = pulse?.let { formatWearable(WearableMetric.HEART_RATE, it.value) }
            if (pulseText != null) Text(buildAnnotatedString {
                append(pulseText.removeSuffix(" bpm"))
                withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)) { append(" bpm") }
            }, fontSize = 64.sp, lineHeight = 70.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "Heart rate: $pulseText" })
            else Text("No reading", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { contentDescription = "Heart rate: no reading" })
            if (pulse != null) Text("Recorded at ${formatReadingTime(pulse.receivedAt, now)}" +
                if (now - pulse.receivedAt > 300_000) " • No recent reading" else "",
                style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            else Text(if (noContact) "Sensor contact not detected. Previous readings remain in the graph."
                else "Waiting for a heart-rate reading from the wearable.", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            if (points.isEmpty()) {
                Text("No recorded heart-rate readings in the last ${if (hours == 1) "hour" else "24 hours"}.",
                    modifier = Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyMedium)
            } else key(hours) { ReadingGraph(points, start, now, Calm.Chart, height = 160, interactive = true) }
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1 to "1 hour", 24 to "24 hours").forEach { (value, label) ->
                    Surface(shape = RoundedCornerShape(10.dp), color = if (hours == value) Color.White else Color.Transparent) {
                        Box(Modifier.testTag("heart-hours-$value").selectable(hours == value, role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { hours = value }).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, color = if (hours == value) Calm.Ink else Calm.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceBatteries(state: ScreenState, source: String?, snapshot: WearableSnapshot, now: Long,
    compact: Boolean = false, onPhone: (() -> Unit)? = null, onWatch: (() -> Unit)? = null) {
    val watch = snapshot.values.firstOrNull { it.metric == WearableMetric.BATTERY }
    val phoneMissing = when {
        state.settings.role == Role.SHARER && !state.settings.phoneBattery ->
            "Enable Share phone battery in Settings, save, then turn Sharing on."
        state.settings.role == Role.SHARER ->
            "No reading yet. Turn Sharing on in Settings to collect phone battery."
        source == null -> "Connect a family phone to receive its battery level."
        else -> "No reading received. On the sharing phone, check Share phone battery in Settings and keep Sharing on."
    }
    val watchMissing = if (state.settings.role == Role.SHARER && !state.settings.wearable)
        "Choose a wearable and enable Share wearable readings in Settings."
        else "No battery reading. Some watches do not share battery over Bluetooth."
    key(source) { PhoneBatteryCard(phoneBatteryEvents(state, source), now, phoneMissing, compact, onPhone) }
    BatteryCard("Smartwatch", R.drawable.ic_watch_outline, watch?.value?.roundToInt(), watch?.receivedAt, now, "",
        watchMissing, Modifier.fillMaxWidth(), compact, onWatch)
}

@Composable
private fun BatteryCard(title: String, icon: Int, percent: Int?, at: Long?, now: Long, detail: String, missing: String, modifier: Modifier,
    compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val low = percent != null && percent <= 20
    val color = if (low) Calm.Low else Calm.Green
    Card(if (onClick == null) modifier else modifier.clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
        containerColor = if (low) Calm.LowBackground else Color.White, contentColor = Calm.Ink,
    )) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                }
                Text(percent?.let { "$it%" } ?: "Unknown", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = color)
                Text(if (at != null) "Recorded ${formatReadingTime(at, now)}" else missing,
                    style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                if (percent != null && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                if (low) Text("Low battery", style = MaterialTheme.typography.labelMedium, color = Calm.Low)
                if (at != null && now - at > 600_000) Text("No recent reading", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            } else {
            Icon(painterResource(icon), null, Modifier.size(21.dp), tint = color)
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(percent?.let { "$it%" } ?: "Unknown", fontSize = if (percent == null) 24.sp else 32.sp,
                lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = if (low) Calm.Low else Calm.Ink)
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = color, trackColor = Calm.Soft, drawStopIndicator = {})
                Text(listOfNotNull(if (low) "Low battery" else null, detail.takeIf { it.isNotBlank() },
                    formatReadingTime(at ?: 0, now)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (low) Calm.Low else Calm.Muted)
                if (at != null && now - at > 600_000) Text("No recent reading", style = MaterialTheme.typography.labelMedium, color = Calm.Muted)
            } else Text(missing, style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            }
        }
    }
}

/** Show a date for older data so yesterday's reading can never look like today's. */
internal fun formatReadingTime(time: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val recorded = Instant.ofEpochMilli(time).atZone(zone)
    return if (time > 0 && recorded.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        DateTimeFormatter.ofPattern("HH:mm").format(recorded) else formatTime(time)
}
