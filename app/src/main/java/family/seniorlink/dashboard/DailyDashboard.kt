package family.seniorlink.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import family.seniorlink.R
import family.seniorlink.ScreenState
import family.seniorlink.core.*
import family.seniorlink.data.ActivityDay
import family.seniorlink.ui.Calm
import family.seniorlink.wearable.WearableState
import family.seniorlink.wearable.formatWearable
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun Kind.activityLabel() = when (this) {
    Kind.CHECK_IN -> "Check-ins"
    Kind.WEARABLE -> "Wearable"
    Kind.LOCATION -> "Location"
    Kind.UNLOCK -> "Phone activity"
    Kind.SMS -> "Messages"
    Kind.PHONE_BATTERY -> "Phone battery"
}

@Composable
internal fun DailyDashboard(
    screen: ScreenState, live: WearableState, activity: ActivityState, source: String?,
    onSource: (String) -> Unit, onDay: (LocalDate) -> Unit, onToday: () -> Unit, onOpen: (Kind?) -> Unit,
    onLatest: (Kind, Long?) -> Unit,
    running: Boolean, enabled: Boolean, status: String, locationDeferred: Boolean, onCheckIn: () -> Unit,
) {
    val sharer = screen.settings.role == Role.SHARER
    val peer = screen.peers.firstOrNull { it.id == source }
    var choosingPhone by remember { mutableStateOf(false) }
    val now = rememberReadingTime(screen, live)
    val snapshot = remember(screen, live, source) { wearableSnapshot(screen, live, source) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!sharer && peer != null) Box {
            TextButton(onClick = { choosingPhone = true }, modifier = Modifier.testTag("summary-family")) {
                Text("${peer.name}  ▾", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = choosingPhone, onDismissRequest = { choosingPhone = false }) {
                screen.peers.forEach { family ->
                    DropdownMenuItem(text = { Text(family.name) }, onClick = { onSource(family.id); choosingPhone = false })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Daily summary", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Calm.Ink)
        if (sharer) {
            Text(if (running) "Sharing is active" else if (enabled) "Sharing is waiting to resume" else "Sharing is paused in Settings",
                style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            if (running) Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) { Text("I'm okay — check in") }
            if (locationDeferred) Text("Location is waiting for permission. Review Settings.", style = MaterialTheme.typography.bodySmall)
            if (!running && enabled) Text(status, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(if (peer == null || peer.lastContact == 0L) "No contact yet" else "Last contact ${formatReadingTime(peer.lastContact, now)}",
                style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            if (peer == null) Text("Connect a family phone in Phones to see shared updates.")
            else {
                if (!enabled) Text("Background updates paused · Receive while open", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                if (status.isNotBlank() && status !in listOf("Connecting…", "Up to date")) Text(status, style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                if (peer.historyGap) Text("Some history expired or was withdrawn before this phone received it.", style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        Text("Latest readings", style = MaterialTheme.typography.labelLarge, color = Calm.Muted)
        CompactHeart(snapshot, now) { onLatest(Kind.WEARABLE, snapshot.values.firstOrNull { it.metric == WearableMetric.HEART_RATE }?.receivedAt) }
        DeviceBatteries(screen, source, snapshot, now, compact = true,
            onPhone = { onLatest(Kind.PHONE_BATTERY, screen.phoneBatteries.firstOrNull { it.source == source }?.event?.occurredAt) },
            onWatch = { onLatest(Kind.WEARABLE, snapshot.values.firstOrNull { it.metric == WearableMetric.BATTERY }?.receivedAt) })
        val day = activity.day?.takeIf { it.source == source }
        DayNavigation(day, activity.request.date ?: LocalDate.now(), onDay, onToday)
        if (activity.error) Text("Saved activity could not be loaded. Reopen this screen to retry.")
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
            val kinds = listOf(Kind.CHECK_IN, Kind.WEARABLE, Kind.LOCATION, Kind.UNLOCK) +
                if (day?.counts?.any { it.kind == Kind.SMS && it.count > 0 } == true) listOf(Kind.SMS) else emptyList()
            kinds.forEachIndexed { index, kind ->
                val count = day?.counts?.firstOrNull { it.kind == kind }
                Row(Modifier.fillMaxWidth().testTag("digest-${kind.name}").clickable(enabled = day != null && !activity.loading) { onOpen(kind) }
                    .heightIn(min = 70.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val icon = when (kind) {
                        Kind.WEARABLE -> R.drawable.ic_heart_outline
                        Kind.LOCATION -> R.drawable.ic_location_outline
                        Kind.UNLOCK, Kind.PHONE_BATTERY -> R.drawable.ic_phone_outline
                        Kind.CHECK_IN -> R.drawable.ic_check_outline
                        else -> R.drawable.ic_message_outline
                    }
                    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (kind == Kind.WEARABLE) Calm.Chart else Calm.Green)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(kind.activityLabel(), fontWeight = FontWeight.SemiBold)
                        Text(if (activity.loading) "Loading…" else if (count == null) "No recorded updates" else
                            "${count.count} recorded · Last ${DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(count.lastAt))}",
                            style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                    }
                    Text("›", color = Calm.Muted)
                }
                if (index < kinds.lastIndex) HorizontalDivider(color = Calm.Line, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        TextButton(onClick = { onOpen(null) }, enabled = day != null, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Open full history")
        }
        Text("Counts reflect saved updates, not a complete activity log.", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
    }
}

@Composable
private fun CompactHeart(snapshot: WearableSnapshot, now: Long, onClick: () -> Unit) {
    val pulse = snapshot.values.firstOrNull { it.metric == WearableMetric.HEART_RATE }
    val points = snapshot.points.filter { it.at in (now - 3_600_000)..now }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Calm.Heart), shape = RoundedCornerShape(20.dp)) {
        val value: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Heart rate", fontWeight = FontWeight.SemiBold)
                Text(pulse?.let { formatWearable(WearableMetric.HEART_RATE, it.value) } ?: "No reading", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(pulse?.let { "Recorded ${formatReadingTime(it.receivedAt, now)}" } ?: "Waiting for a saved reading",
                    style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
                if (pulse != null && now - pulse.receivedAt > 300_000) Text("No recent reading", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            value()
            if (points.isNotEmpty()) ReadingGraph(points, now - 3_600_000, now, Calm.Chart, height = 60)
            Text(if (points.isEmpty()) "No readings in the last hour" else "Last hour · Recorded trend", style = MaterialTheme.typography.labelSmall, color = Calm.Muted)
        } else Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { value() }
            Column(Modifier.weight(1f)) {
                if (points.isNotEmpty()) ReadingGraph(points, now - 3_600_000, now, Calm.Chart, height = 55, labels = false)
                Text(if (points.isEmpty()) "No readings in the last hour" else "Last hour · Recorded trend", style = MaterialTheme.typography.labelSmall, color = Calm.Muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayNavigation(day: ActivityDay?, selected: LocalDate, onDay: (LocalDate) -> Unit, onToday: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onDay(selected.minusDays(1)) }, enabled = day != null && selected > day.firstDate,
            modifier = Modifier.testTag("previous-day").semantics { contentDescription = "Previous day" }) { Text("‹") }
        TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f).testTag("choose-day")) {
            Text((if (selected == today) "Today, " else "") + selected.format(DateTimeFormatter.ofPattern("d MMM yyyy")), fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = { onDay(selected.plusDays(1)) }, enabled = day != null && selected < day.lastDate,
            modifier = Modifier.testTag("next-day").semantics { contentDescription = "Next day" }) { Text("›") }
    }
    if (selected != today) TextButton(onClick = onToday) { Text("Back to today") }
    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(1970, selected.year)..maxOf(2100, selected.year))
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                picking = false
            }) { Text("Show day") }
        }, dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}
