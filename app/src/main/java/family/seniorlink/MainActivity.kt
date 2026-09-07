package family.seniorlink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.pairing.ConnectionSetup
import family.seniorlink.pairing.ConnectionDialog
import family.seniorlink.pairing.PairingStep
import family.seniorlink.monitor.MonitorService
import family.seniorlink.updates.UpdateDialog
import family.seniorlink.updates.UpdateViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    private val updates by viewModels<UpdateViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // SMS and location should not leak through screenshots or the recent-apps thumbnail.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF14665B),
                secondary = Color(0xFF426653),
                background = Color(0xFFF7F9F6),
                surface = Color(0xFFF7F9F6),
            )) { SeniorScreen(model, updates) }
        }
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() { model.foreground(false); super.onStop() }
}

@Composable
private fun SeniorScreen(model: MainViewModel, updates: UpdateViewModel) {
    val state by model.screen.collectAsStateWithLifecycle()
    val availableUpdate by updates.availableUpdate.collectAsStateWithLifecycle()
    val pairingState by model.pairing.state.collectAsStateWithLifecycle()
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val monitorStatus by model.app.monitorStatus.collectAsStateWithLifecycle()
    val telegramStatus by model.app.telegramStatus.collectAsStateWithLifecycle()
    val peerStatus by model.app.peerStatus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.message("Permissions updated. Tap Start sharing when ready.")
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("SeniorLink", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                when (state.settings.role) {
                    Role.SHARER -> "Your information. Your choice."
                    Role.CAREGIVER -> "Family updates, when you check in."
                    Role.UNSET -> "A little more peace of mind."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (!state.ready) {
                Text(state.fatalError ?: "Opening secure local storage…")
            }
            if (state.ready && state.settings.role == Role.UNSET) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Panel("Welcome") {
                        Text("Install this same app on each phone. Choose this phone's role; no monitoring starts automatically.")
                        Button(onClick = { model.chooseRole(Role.SHARER) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Share my information")
                        }
                        OutlinedButton(onClick = { model.chooseRole(Role.CAREGIVER) }, modifier = Modifier.fillMaxWidth()) {
                            Text("I'm a caregiver")
                        }
                    }
                    Panel("Visible and voluntary") {
                        Text("The sharing phone approves every caregiver. Location, unlock activity and selected SMS each have their own switch.")
                        Text("Caregivers catch up while their app is open. Both phones must be reachable. This is not an emergency response service.")
                        Text("Use Android app settings → Storage → Clear data to reset the role and identity. Resetting requires pairing again.")
                    }
                }
            }
            if (state.ready && state.settings.role != Role.UNSET) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Updates", "Phones", "Settings").forEachIndexed { index, title ->
                        FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (tab) {
                        0 -> {
                            if (state.settings.role == Role.SHARER) {
                                Panel(if (running) "Sharing is active" else "Sharing is paused") {
                                    Text(monitorStatus)
                                    Text("Approved caregivers: ${state.peers.size}")
                                    if (running) {
                                        Button(onClick = model::checkIn, modifier = Modifier.fillMaxWidth()) { Text("I'm okay — check in") }
                                        OutlinedButton(onClick = model::pause, modifier = Modifier.fillMaxWidth()) { Text("Pause sharing") }
                                    } else {
                                        Button(onClick = {
                                            val missing = MonitorService.missingPermissions(context, state.settings)
                                            if (missing.isEmpty()) model.start() else permissions.launch(missing.toTypedArray())
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Start sharing") }
                                        Text("Starting makes retained information available to approved phones. A visible notification stays on while sharing.")
                                    }
                                }
                            } else {
                                Panel("Catch-up on open") {
                                    Text("Cached information appears immediately. Updates arrive only while this app is open and the sharing phone is reachable.")
                                    if (state.peers.isEmpty()) Text("Go to Phones to pair with your grandfather.")
                                    state.peers.forEach { peer ->
                                        Text(peer.name, fontWeight = FontWeight.Bold)
                                        Text(peerStatus[peer.id] ?: "Waiting to connect")
                                        Text("Last contact: ${formatTime(peer.lastContact)}")
                                        if (peer.historyGap) Text("Some older history expired or was withdrawn before this phone received it.")
                                    }
                                    Text("Always check timestamps. Missing contact alone is not an emergency signal.")
                                }
                            }
                            Text("Recent updates", style = MaterialTheme.typography.titleLarge)
                            if (state.events.isEmpty()) Text("No updates yet. On the sharing phone, tap Start sharing and then I'm okay.")
                            state.events.forEach { EventCard(it, state.peers) }
                        }
                        1 -> Phones(state, model)
                        2 -> {
                            if (state.settings.role == Role.SHARER) {
                                SharingSettings(state, running, telegramStatus, model)
                            } else Panel("Caregiver mode") {
                                Text("This phone does not collect its own location, unlock activity or SMS.")
                                Text("Updates sync while this app is open. A connection invitation can stay active for up to 5 minutes.")
                            }
                            Panel("Privacy and reliability") {
                                Text("History stays on these phones for up to 7 days, capped at 10,000 events per source. The latest 100 are shown here.")
                                Text("iroh encrypts connections end to end. Public discovery and relays may see connection metadata, not message contents.")
                                Text("Android can stop the sharing service. After reboot or force-stop, open SeniorLink on the sharing phone and tap Start.")
                                OutlinedButton(onClick = {
                                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                                }) { Text("Android app settings") }
                            }
                        }
                    }
                }
            }
        }
    }
    ConnectionDialog(model)
    if (state.ready && state.message == null && pairingState.step == PairingStep.IDLE) {
        availableUpdate?.let { UpdateDialog(it, updates::dismiss) }
    }
    state.message?.let { text ->
        AlertDialog(onDismissRequest = { model.message(null) }, title = { Text("SeniorLink") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { model.message(null) }) { Text("OK") } })
    }
}

@Composable
private fun Phones(state: ScreenState, model: MainViewModel) {
    var removing by remember { mutableStateOf<Peer?>(null) }
    Panel(if (state.settings.role == Role.SHARER) "Connect a caregiver" else "Connect to your family") {
        ConnectionSetup(state, model)
    }
    Panel("Paired phones") {
        if (state.peers.isEmpty()) Text("No paired phones")
        state.peers.forEach { peer ->
            Text(peer.name, fontWeight = FontWeight.Bold)
            Text("Last receipt: ${formatTime(peer.lastContact)}")
            TextButton(onClick = { removing = peer }) { Text("Remove ${peer.name}") }
        }
        Text("Removing a caregiver stops future access. It cannot erase information already received on that phone.")
    }
    removing?.let { peer ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${peer.name}?") },
            text = { Text("You will need to approve this phone again to resume sharing. In caregiver mode, its cached history is also deleted here.") },
            confirmButton = { TextButton(onClick = { model.removePeer(peer.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SharingSettings(state: ScreenState, running: Boolean, telegramStatus: String, model: MainViewModel) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var token by remember { mutableStateOf("") }
    Panel("Choose what to share") {
        if (running) Text("Pause sharing before editing settings.")
        Toggle("Observe phone unlocks", draft.unlock, { draft = draft.copy(unlock = it) }, !running)
        Text("Best-effort Android unlock broadcasts, not a complete audit log.")
        Toggle("Share location", draft.location, { draft = draft.copy(location = it) }, !running)
        Text("Approximately every 15 minutes when Android provides a fix. Network location is preferred to reduce battery use; GPS is a fallback.")
        Toggle("Share incoming SMS from selected senders", draft.sms, { draft = draft.copy(sms = it) }, !running)
        if (draft.sms) {
            OutlinedTextField(draft.smsSenders, { draft = draft.copy(smsSenders = it.take(2000)) },
                label = { Text("Exact senders, one per line") }, enabled = !running, modifier = Modifier.fillMaxWidth())
            Text("Use full numbers, including country code, or exact sender names. Wildcards are not accepted.")
            Toggle("Also share SMS message bodies", draft.smsBodies, { draft = draft.copy(smsBodies = it) }, !running)
            Text("Bodies can contain private conversations and banking details. Likely codes are withheld, but filtering cannot detect every secret.")
        }
    }
    Panel("Optional Telegram output") {
        Toggle("Forward enabled updates to Telegram", draft.telegram, { draft = draft.copy(telegram = it) }, !running)
        Text("Telegram bot/channel messages are NOT end-to-end encrypted. Only this sharing phone posts. Network retries can occasionally duplicate a post.")
        OutlinedTextField(token, { token = it.take(160) },
            label = { Text(if (state.tokenSaved) "New bot token (leave blank to keep saved)" else "Bot token") },
            visualTransformation = PasswordVisualTransformation(), enabled = !running,
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.telegramChat, { draft = draft.copy(telegramChat = it.take(100)) },
            label = { Text("Chat/channel ID or @channel") }, enabled = !running, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("The bot needs permission to post to the channel. Token is encrypted locally and never included in pairing codes.")
        Text("$telegramStatus • ${state.pendingTelegram} queued")
        OutlinedButton(onClick = model::testTelegram, enabled = state.tokenSaved && draft == state.settings && token.isBlank()) {
            Text("Send test to saved destination")
        }
    }
    Button(onClick = { model.save(draft, token); token = "" }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
        Text("Save settings")
    }
    Text("Save first, then go to Updates and tap Start sharing. Android will ask for permissions only for selected features.")
}

@Composable
private fun EventCard(stored: StoredEvent, peers: List<Peer>) {
    val event = stored.event
    val context = LocalContext.current
    Panel(when (event.kind) {
        Kind.UNLOCK -> "Phone unlocked"
        Kind.LOCATION -> "Location update"
        Kind.SMS -> "Incoming SMS"
        Kind.CHECK_IN -> "I'm okay"
    }) {
        Text("${peers.firstOrNull { it.id == stored.source }?.name ?: "This phone"} • ${formatTime(event.occurredAt)}")
        when (event.kind) {
            Kind.LOCATION -> {
                Text("${event.latitude}, ${event.longitude} • accuracy ±${event.accuracy?.toInt()} m")
                TextButton(onClick = {
                    val uri = Uri.parse("geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }) { Text("Open in maps") }
            }
            Kind.SMS -> {
                Text("From ${event.sender}")
                Text(event.body ?: "Message body not shared")
            }
            Kind.CHECK_IN -> Text("Manual check-in from the sharing phone.")
            Kind.UNLOCK -> Text("Android reported that the phone became available after unlocking.")
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch,
            onValueChange = change,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun formatTime(time: Long): String = if (time == 0L) "Never" else
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
