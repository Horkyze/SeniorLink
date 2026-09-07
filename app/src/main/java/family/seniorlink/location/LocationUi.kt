package family.seniorlink.location

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import family.seniorlink.ScreenState
import family.seniorlink.core.Role
import family.seniorlink.data.StoredEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LocationContent(
    state: ScreenState,
    running: Boolean,
    initialSource: String?,
    initialSequence: Long?,
    onSettings: () -> Unit,
    onSelectLocation: () -> Unit,
) {
    val phones = if (state.settings.role == Role.SHARER) listOf(state.publicId to "This phone")
        else state.peers.map { it.id to it.name }
    var chosenSource by rememberSaveable(initialSource) { mutableStateOf(initialSource) }
    val source = chosenSource?.takeIf { selected -> phones.any { it.first == selected } } ?: phones.firstOrNull()?.first
    val name = phones.firstOrNull { it.first == source }?.second.orEmpty()
    val locations = remember(state.locations, source) {
        state.locations.filter { it.source == source }.sortedWith(
            compareByDescending<StoredEvent> { it.event.occurredAt }.thenByDescending { it.event.sequence },
        )
    }
    var selectedSequence by rememberSaveable(source, initialSequence) {
        mutableStateOf(initialSequence.takeIf { source == initialSource })
    }
    var historyVisible by rememberSaveable(source) { mutableIntStateOf(20) }
    var showAll by rememberSaveable(source) { mutableStateOf(false) }
    var cameraRequest by remember { mutableIntStateOf(0) }
    val selected = locations.firstOrNull { it.event.sequence == selectedSequence } ?: locations.firstOrNull()
    val latest = locations.firstOrNull()
    val select: (Long) -> Unit = { sequence ->
        selectedSequence = sequence.takeUnless { it == latest?.event?.sequence }
        showAll = false
        cameraRequest++
        onSelectLocation()
    }
    Text("Location", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (phones.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            phones.forEach { (id, label) ->
                FilterChip(selected = source == id, onClick = { chosenSource = id }, label = { Text(label) })
            }
        }
    }
    if (selected == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No location yet", style = MaterialTheme.typography.titleLarge)
                Text(when {
                    state.settings.role == Role.SHARER && !state.settings.location ->
                        "Turn on Share location in Settings, save, then start sharing to see your location here."
                    state.settings.role == Role.SHARER && !running ->
                        "Tap Start sharing in Updates and allow location access. The map appears after Android supplies a location."
                    state.settings.role == Role.SHARER ->
                        "Waiting for Android to provide a location. Check that location is enabled in Android settings."
                    phones.isEmpty() -> "Connect a sharing phone in Phones to see its location and history here."
                    else -> "No location received from $name. On that phone, enable Share location in Settings and start sharing. Keep both phones online to receive the first fix."
                })
                if (state.settings.role == Role.SHARER) {
                    OutlinedButton(onClick = onSettings) { Text("Location settings") }
                }
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (selected == latest) "Latest known location" else "Previous location",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(locationTime(selected.event.occurredAt))
                    Text("Accuracy ±${selected.event.accuracy?.toInt()} m • ${coordinates(selected)}")
                }
                LocationMap(
                    locations = locations, selected = selected, showAll = showAll,
                    cameraRequest = cameraRequest, onSelect = select,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Teal: latest known • Amber: previous • Ring: selected", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { select(latest!!.event.sequence) }) { Text("Latest location") }
                        OutlinedButton(onClick = { showAll = true; cameraRequest++ }) { Text("Show history") }
                    }
                    OpenInMaps(selected)
                }
            }
        }
        Text("Last recorded ${locationTime(latest!!.event.occurredAt)}. Locations update about every 15 minutes when available; this is not live tracking.",
            style = MaterialTheme.typography.bodyMedium)
        if (state.settings.role == Role.SHARER && !running) Text("Sharing is paused. This is a saved location.")
        val peer = state.peers.firstOrNull { it.id == source }
        if (peer != null) {
            Text("Last contact: ${if (peer.lastContact == 0L) "Never" else locationTime(peer.lastContact)}")
            if (peer.historyGap) Text("Some older history expired or was withdrawn before this phone received it.")
        }
        Text("Location history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${locations.size} saved ${if (locations.size == 1) "location" else "locations"} • newest first. Tap a location to show it on the map.")
        locations.take(historyVisible).forEachIndexed { index, location ->
            val isSelected = location == selected
            OutlinedCard(onClick = { select(location.event.sequence) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = if (isSelected)
                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (index == 0) "Latest known location" else "Previous location ${index}", fontWeight = FontWeight.SemiBold)
                    Text(locationTime(location.event.occurredAt))
                    Text("${coordinates(location)} • ±${location.event.accuracy?.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                    if (isSelected) Text("Selected on map", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (historyVisible < locations.size) {
            OutlinedButton(onClick = { historyVisible += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Show earlier locations") }
        }
        Text("Up to 1,000 retained locations per phone. History is kept for up to 7 days after it is stored on this phone.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OpenInMaps(location: StoredEvent) {
    val context = LocalContext.current
    var unavailable by remember(location) { mutableStateOf(false) }
    TextButton(onClick = {
        val point = "${location.event.latitude},${location.event.longitude}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point")))
        } catch (_: ActivityNotFoundException) { unavailable = true }
    }) { Text("Open in maps app") }
    if (unavailable) Text("No maps app is installed. You can still browse the map above.")
}

internal fun coordinates(location: StoredEvent): String = String.format(Locale.US, "%.5f, %.5f", location.event.latitude, location.event.longitude)
internal fun locationTime(time: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
