package family.seniorlink.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun rememberReadingTime(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
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
    val now = rememberReadingTime()
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
        HeartRateCard(snapshot, now)
        Text("Device batteries", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = Calm.Ink)
        DeviceBatteries(state, source, snapshot, now)
        Text("Saved readings · Heart-rate trend, not an ECG", Modifier.fillMaxWidth(),
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
            } else HeartGraph(points, start, now, Calm.Chart)
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1 to "1 hour", 24 to "24 hours").forEach { (value, label) ->
                    Surface(shape = RoundedCornerShape(10.dp), color = if (hours == value) Color.White else Color.Transparent) {
                        Box(Modifier.selectable(hours == value, role = androidx.compose.ui.semantics.Role.Tab,
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
private fun HeartGraph(points: List<HeartPoint>, start: Long, end: Long, accent: Color) {
    val low = (floor(points.minOf { it.bpm } / 10) * 10 - 10).coerceAtLeast(0.0)
    val high = maxOf(ceil(points.maxOf { it.bpm } / 10) * 10, low + 20)
    val grid = Calm.Line
    val description = "Heart-rate graph, ${points.size} saved readings. " +
        "${formatTime(points.first().at)} to ${formatTime(points.last().at)}. " +
        "Lowest ${points.minOf { it.bpm }.roundToInt()}, highest ${points.maxOf { it.bpm }.roundToInt()} beats per minute."
    Row(Modifier.fillMaxWidth().height(124.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(high.toInt().toString(), style = MaterialTheme.typography.labelSmall)
            Text(((high + low) / 2).toInt().toString(), style = MaterialTheme.typography.labelSmall)
            Text(low.toInt().toString(), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp).semantics { contentDescription = description }) {
            val inset = 4.dp.toPx()
            fun position(p: HeartPoint) = Offset(
                inset + ((p.at - start).toDouble() / (end - start) * (size.width - inset * 2)).toFloat(),
                inset + ((high - p.bpm) / (high - low) * (size.height - inset * 2)).toFloat(),
            )
            listOf(inset, size.height / 2, size.height - inset).forEach { y ->
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            // Fill each uninterrupted run independently, so the area never bridges missing readings.
            val runs = mutableListOf<MutableList<Offset>>()
            points.forEach { point ->
                if (runs.isEmpty() || point.breakBefore) runs += mutableListOf<Offset>()
                runs.last() += position(point)
            }
            runs.filter { it.size > 1 }.forEach { run ->
                val area = Path().apply {
                    moveTo(run.first().x, size.height - inset)
                    run.forEach { lineTo(it.x, it.y) }
                    lineTo(run.last().x, size.height - inset)
                    close()
                }
                drawPath(area, accent.copy(alpha = 0.07f))
            }
            points.forEachIndexed { index, point ->
                val at = position(point)
                if (index > 0 && !point.breakBefore) drawLine(accent, position(points[index - 1]), at, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(accent, if (points.size < 60) 2.5.dp.toPx() else 1.5.dp.toPx(), at)
            }
        }
    }
    val fullDay = end - start >= 86_400_000
    val formatter = remember(fullDay) {
        DateTimeFormatter.ofPattern(if (fullDay) "d MMM\nHH:mm" else "HH:mm").withZone(ZoneId.systemDefault())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatter.format(Instant.ofEpochMilli(start)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("Time on phone", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        Text(formatter.format(Instant.ofEpochMilli(end)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
    }
}

@Composable
internal fun DeviceBatteries(state: ScreenState, source: String?, snapshot: WearableSnapshot, now: Long) {
    val phone = state.phoneBatteries.filter { it.source == source &&
        (state.settings.role == Role.SHARER && source == state.publicId || state.peers.any { peer -> peer.id == source }) }
        .maxByOrNull { it.event.occurredAt }?.event
    val watch = snapshot.values.firstOrNull { it.metric == WearableMetric.BATTERY }
    val phoneMissing = if (state.settings.role == Role.SHARER && !state.settings.phoneBattery)
        "Enable Share phone battery in Settings." else "No phone battery shared yet."
    val phoneStatus = when (phone?.phoneBattery?.charging) {
        true -> "Charging"; false -> "Not charging"; null -> "Charging state unavailable"
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || fontScale > 1.3f
        val cards: @Composable (Modifier) -> Unit = { modifier ->
            BatteryCard("Phone", R.drawable.ic_phone_outline, phone?.phoneBattery?.percent, phone?.occurredAt, now, phoneStatus, phoneMissing, modifier)
            BatteryCard("Smartwatch", R.drawable.ic_watch_outline, watch?.value?.roundToInt(), watch?.receivedAt, now, "",
                "No battery reading. Some watches do not share battery over Bluetooth.", modifier)
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { cards(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cards(Modifier.weight(1f)) }
    }
}

@Composable
private fun BatteryCard(title: String, icon: Int, percent: Int?, at: Long?, now: Long, detail: String, missing: String, modifier: Modifier) {
    val low = percent != null && percent <= 20
    val color = if (low) Calm.Low else Calm.Green
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
        containerColor = if (low) Calm.LowBackground else Color.White, contentColor = Calm.Ink,
    )) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** Show a date for older data so yesterday's reading can never look like today's. */
internal fun formatReadingTime(time: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val recorded = Instant.ofEpochMilli(time).atZone(zone)
    return if (time > 0 && recorded.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        DateTimeFormatter.ofPattern("HH:mm").format(recorded) else formatTime(time)
}
