package family.seniorlink.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import family.seniorlink.EventCard
import family.seniorlink.ScreenState
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.data.dayBounds
import family.seniorlink.ui.Calm
import family.seniorlink.wearable.WearableState
import family.seniorlink.wearable.formatWearable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ActivityDetails(screen: ScreenState, activity: ActivityState, source: String?, browser: ActivityBrowser,
    onLocation: (StoredEvent) -> Unit) {
    val request = activity.request
    if (!request.showing) return
    val allowed = source != null && (screen.settings.role == Role.SHARER && source == screen.publicId || screen.peers.any { it.id == source })
    val day = activity.day?.takeIf { allowed && it.source == source }
    val date = day?.date ?: request.date ?: LocalDate.now()
    val title = if (request.fullHistory) "Activity history" else request.kind?.activityLabel().orEmpty()
    val total = day?.counts?.filter { request.kind == null || it.kind == request.kind }?.sumOf { it.count } ?: 0
    val name = if (screen.settings.role == Role.SHARER) "This phone" else screen.peers.firstOrNull { it.id == source }?.name.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(source, date, request.kind, request.fullHistory) { listState.scrollToItem(0) }
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("$name · ${date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = browser::close) { Text("Close") }
        }
        if (request.fullHistory) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                DayNavigation(day, date, browser::date, browser::today)
                var choosingFilter by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { choosingFilter = true }) { Text("Type: ${request.kind?.activityLabel() ?: "All updates"} ▾") }
                    DropdownMenu(expanded = choosingFilter, onDismissRequest = { choosingFilter = false }) {
                        (listOf<Kind?>(null) + Kind.entries).forEach { kind ->
                            DropdownMenuItem(text = { Text(kind?.activityLabel() ?: "All updates") }, onClick = {
                                browser.filter(kind); choosingFilter = false
                            })
                        }
                    }
                }
            }
        }
        if (activity.loading && day == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        LazyColumn(Modifier.weight(1f).testTag("activity-records"), state = listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(if (activity.error) "Saved activity could not be loaded." else if (!allowed) "This phone is no longer connected."
                    else if (day == null && activity.loading) "Loading saved activity…" else "$total recorded updates",
                    style = MaterialTheme.typography.bodyMedium, color = Calm.Muted)
            }
            if (day != null && request.kind == Kind.WEARABLE && !request.fullHistory) item {
                val snapshot = remember(screen, day) { wearableSnapshot(screen.copy(wearables = day.wearableRecords), WearableState(), source) }
                val (start, end) = dayBounds(date, ZoneId.systemDefault())
                val points = snapshot.points.filter { it.at in start until end }
                if (points.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = Calm.Heart)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recorded heart rate", fontWeight = FontWeight.SemiBold)
                        Text(snapshot.name, style = MaterialTheme.typography.bodySmall)
                        key(source, date, snapshot.events.firstOrNull()?.event?.wearable?.deviceId) {
                            ReadingGraph(points, points.first().at, maxOf(points.first().at + 60_000, points.last().at),
                                Calm.Chart, height = 160, interactive = true)
                        }
                        if (total > 1000) Text("Chart uses the latest 1,000 saved wearable updates for this day.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (day != null && request.kind == Kind.PHONE_BATTERY && !request.fullHistory) item {
                val events = phoneBatteryEvents(screen.copy(phoneBatteries = day.batteryRecords), source)
                val points = batteryPoints(events)
                if (points.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recorded phone battery", fontWeight = FontWeight.SemiBold)
                        key(source, date) {
                            ReadingGraph(points, points.first().at, maxOf(points.first().at + 60_000, points.last().at),
                                Calm.Green, height = 160, interactive = true, metric = ChartMetric.PHONE_BATTERY)
                        }
                        Text("Charging status is shown as recorded at the selected time.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (day != null && day.records.isEmpty()) item { Text("No recorded updates for this day and type.") }
            items(day?.records.orEmpty(), key = { "${it.source}-${it.event.sequence}" }) { stored ->
                ActivityRecord(stored, screen, onLocation)
            }
            if (day != null && !request.fullHistory) item {
                TextButton(onClick = browser::expand) { Text("View all $total updates") }
            }
            if (day?.hasMore == true && request.fullHistory) item {
                OutlinedButton(onClick = browser::more, enabled = !activity.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (activity.loading) "Loading…" else "Load earlier")
                }
            }
            item {
                Text("Only retained updates are shown. History may be incomplete. Dates use this phone's time zone.",
                    style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
            }
        }
    }
    if (request.fullHistory) Dialog(onDismissRequest = browser::close, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = Calm.Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 12.dp)) { content() }
        }
    } else ModalBottomSheet(onDismissRequest = browser::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn), containerColor = Calm.Background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) { content() }
    }
}

@Composable
private fun ActivityRecord(stored: StoredEvent, screen: ScreenState, onLocation: (StoredEvent) -> Unit) {
    var expanded by remember(stored.source, stored.event.sequence) { mutableStateOf(false) }
    val event = stored.event
    val summary = when (event.kind) {
        Kind.CHECK_IN -> "I'm okay"
        Kind.UNLOCK -> "Phone unlocked"
        Kind.PHONE_BATTERY -> event.phoneBattery?.let { "${it.percent}%" + when (it.charging) { true -> " · Charging"; false -> " · Not charging"; null -> "" } }.orEmpty()
        Kind.LOCATION -> "Accuracy ±${event.accuracy?.toInt()} m"
        Kind.SMS -> "From ${event.sender}"
        Kind.WEARABLE -> event.wearable?.metrics?.take(2)?.joinToString(" · ") {
            "${it.metric.label}: ${formatWearable(it.metric, it.latest)}"
        }.orEmpty().ifBlank { "Device information · Tap for details" }
    }
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
        Column(Modifier.fillMaxWidth().testTag("activity-${event.sequence}")
            .clickable(onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.occurredAt)),
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.kind.activityLabel(), fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(if (expanded) "⌃" else "⌄", color = Calm.Muted)
            }
        }
        if (expanded) EventCard(stored, screen.peers) { onLocation(stored) }
    }
}
