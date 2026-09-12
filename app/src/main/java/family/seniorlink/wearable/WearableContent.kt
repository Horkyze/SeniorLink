package family.seniorlink.wearable

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import family.seniorlink.dashboard.*
import family.seniorlink.Panel
import family.seniorlink.ScreenState
import family.seniorlink.Toggle
import family.seniorlink.core.*
import family.seniorlink.formatTime
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun WearableSettings(draft: Settings, enabled: Boolean, scanner: WearableScanner, change: (Settings) -> Unit) {
    val context = LocalContext.current
    val scan by scanner.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (choosing) scanner.start()
    }
    fun scan() {
        val missing = BleAccess.missingScanPermissions(context)
        if (missing.isEmpty()) scanner.start() else permissions.launch(missing.toTypedArray())
    }
    Panel("Bluetooth wearable") {
        Toggle("Share wearable readings", draft.wearable, { change(draft.copy(wearable = it)) }, enabled)
        Text("Connect directly over Bluetooth while sharing is active. Samsung Health is not needed for collection.")
        Text(if (draft.wearableAddress.isBlank()) "No wearable selected" else "${draft.wearableName}\n${draft.wearableAddress}")
        OutlinedButton(onClick = { choosing = true; scan() }, enabled = enabled && BleAccess.supported(context)) { Text("Choose wearable") }
        if (!BleAccess.supported(context)) Text("This phone does not support Bluetooth LE.")
        if (draft.wearableAddress.isNotBlank()) TextButton(onClick = {
            change(draft.copy(wearable = false, wearableAddress = "", wearableName = ""))
        }, enabled = enabled) { Text("Forget wearable") }
        Text("Collect heart rate and other supported readings. The Wearable tab shows what your device provides.")
        Text("On a Fit3, enable continuous heart-rate measurement on the band. Keep it near this phone. Missed Bluetooth readings cannot be downloaded later.")
        Text("Save settings to apply changes. Disabling wearable sharing or changing devices removes retained wearable data on this phone.")
    }
    if (choosing) {
        DisposableEffect(Unit) { onDispose { scanner.stop() } }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Choose Bluetooth wearable") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scan.status)
                    Text("Choose only your own wearable. A nearby device may not support health measurements.")
                    scan.devices.forEach { device ->
                        OutlinedButton(onClick = {
                            change(draft.copy(wearableAddress = device.address, wearableName = device.name))
                            choosing = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${device.name}\n${device.address}${if (device.bonded) " • paired" else ""}")
                        }
                    }
                    if (scan.devices.isEmpty() && !scan.scanning) Text("No devices found. Wake the band and check Bluetooth. A companion app may already hold its connection.")
                    Text("Android 11 and earlier require Location permission and the Location setting for scanning; this does not enable location sharing.")
                }
            },
            confirmButton = { TextButton(onClick = { if (scan.scanning) scanner.stop() else scan() }) { Text(if (scan.scanning) "Stop scan" else "Scan again") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WearableContent(
    state: ScreenState,
    live: WearableState,
    onSettings: () -> Unit,
    selectedSource: String? = null,
    onSelectSource: ((String) -> Unit)? = null,
) {
    val sharer = state.settings.role == Role.SHARER
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val source = if (sharer) state.publicId else state.peers.firstOrNull { it.id == (selectedSource ?: selected) }?.id ?: state.peers.firstOrNull()?.id
    val events = state.wearables.filter { it.source == source }.sortedByDescending { it.event.occurredAt }
    val snapshot = remember(state, live, source) { wearableSnapshot(state, live, source) }
    val deviceEvents = snapshot.events
    Panel("Wearable readings") {
        if (sharer) {
            Text(if (state.settings.wearableAddress.isBlank()) "No wearable selected" else state.settings.wearableName)
            Text(live.status)
            TextButton(onClick = onSettings) { Text("Wearable settings") }
            if (!state.settings.wearable) Text("Enable Share wearable readings in Settings, save, then start sharing from Updates.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.peers.forEach { peer ->
                    FilterChip(selected = peer.id == source, onClick = { selected = peer.id; onSelectSource?.invoke(peer.id) }, label = { Text(peer.name) })
                }
            }
            Text("Saved readings update when this app and the sharing phone can connect.")
            if (events.isNotEmpty()) Text(events.first().event.wearable?.deviceName.orEmpty())
        }
    }
    HeartRateCard(snapshot, now)
    DeviceBatteries(state, source, snapshot, now)
    Panel("Latest measurements") {
        Text("Times show Bluetooth receipt on the sharing phone; the band's measurement time may differ.")
        val values = snapshot.values
        if (values.isEmpty()) Text("No wearable readings yet. Available measurements depend on the device's Bluetooth services.")
        values.sortedBy { it.metric.ordinal }.forEach { value ->
            Text("${value.metric.label}: ${formatWearable(value.metric, value.value)}", fontWeight = FontWeight.SemiBold)
            Text("Received ${formatTime(value.receivedAt)}${if (now - value.receivedAt > 300_000) " • No recent reading" else ""}")
        }
        val information = if (sharer && state.settings.wearable) live.information else deviceEvents.asReversed()
            .flatMap { it.event.wearable?.information.orEmpty().entries }.associate { it.key to it.value }
        information.forEach { (key, value) -> Text("$key: $value") }
        if (!sharer) deviceEvents.firstOrNull()?.event?.wearable?.notes?.forEach { Text("Latest summary note: $it") }
        Text("The graph shows the last pulse from each two-minute summary. History includes ranges and averages. Readings stop when the band is disconnected or not measuring.")
    }
    if (sharer) Panel("Available Bluetooth data") {
        if (live.capabilities.isEmpty()) Text("Start sharing to discover the selected wearable's services.")
        live.capabilities.forEach { Text(it) }
        if (live.issues.isNotEmpty()) {
            Text("Messages from this connection", fontWeight = FontWeight.SemiBold)
            live.issues.forEach { Text(it) }
        }
        Text("Sleep, steps, stress, ECG, and stored history are not collected through this integration. Samsung-specific services may need a separate device integration.")
        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide Bluetooth services" else "Show Bluetooth services") }
        if (diagnostics) {
            Text("Service and characteristic IDs help identify additional supported data. Unknown services are not read or written.")
            if (live.services.isEmpty()) Text("No services discovered yet")
            live.services.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Panel("Wearable history") {
        Text("${events.size} recent summaries from this phone's retained history")
        TextButton(onClick = { history = !history }) { Text(if (history) "Hide wearable history" else "Show wearable history") }
        if (history) events.take(20).forEach { stored ->
            HorizontalDivider()
            Text(formatTime(stored.event.occurredAt), fontWeight = FontWeight.Bold)
            stored.event.wearable?.let { WearableSummaryContent(it) }
        }
        if (history && events.size > 20) Text("Showing the latest 20 summaries")
    }
}

@Composable
internal fun WearableSummaryContent(summary: WearableSummary) {
    Text(summary.deviceName)
    summary.metrics.forEach {
        Text("${it.metric.label}: ${formatWearable(it.metric, it.latest)}")
        Text("Received ${formatTime(it.lastAt)} • ${it.count} samples", style = MaterialTheme.typography.bodySmall)
        if (it.count > 1 && it.metric != WearableMetric.CONTACT) Text(
            "Range ${formatWearable(it.metric, it.minimum)} – ${formatWearable(it.metric, it.maximum)} • Average ${formatWearable(it.metric, it.average)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    summary.information.forEach { (key, value) -> Text("$key: $value") }
    summary.notes.forEach { Text("During this summary: $it") }
}

internal fun formatWearable(metric: WearableMetric, value: Double): String {
    if (metric == WearableMetric.CONTACT) return if (value == 1.0) "Detected" else "Not detected"
    val number = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)
    return "$number ${metric.unit}".trim()
}
