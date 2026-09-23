package family.seniorlink.mailbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import family.seniorlink.MainViewModel
import family.seniorlink.ScreenState
import family.seniorlink.core.Role

@Composable
fun MailboxSettings(state: ScreenState, model: MainViewModel) {
    val summary by model.app.mailbox.summary.collectAsStateWithLifecycle()
    val statuses by model.app.mailbox.statuses.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Encrypted queued delivery", style=MaterialTheme.typography.titleMedium)
            Text("An optional mailbox holds encrypted updates so your phones can connect at different times. Your paired phones keep their existing identities.")
            SelectionContainer { Text("Mailbox phone ID: ${state.publicId}", style=MaterialTheme.typography.bodySmall) }
            if (!summary.configured) Text("Ask the person running your family's mailbox for its setup code. Configure the same mailbox on both phones.")
            else Text("Mailbox configured. Keep both apps open for initial setup after enabling a phone below.")
            OutlinedTextField(value=code,singleLine=true,onValueChange={ if(it.length<=4096)code=it },label={Text("Mailbox setup code")},modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={model.configureMailbox(code)},enabled=code.isNotBlank()) { Text(if(summary.configured) "Change mailbox" else "Configure mailbox") }
            if (summary.configured) {
                Text(if(state.settings.role==Role.SHARER)
                    "Enabling a caregiver allows uploaded updates to arrive while this phone is offline. Sharing must be on to upload. If you pause or remove access offline, mailbox deletion waits for a connection; delivery can continue for up to 24 hours after the last authorization. Copies already received cannot be recalled."
                    else "Enable each approved sharing phone you want to receive queued updates from. This never collects your phone's information. The sharing phone must also enable you.")
                state.peers.forEach { peer ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name)
                            Text(statuses[peer.id] ?: "Queued delivery is optional",style=MaterialTheme.typography.bodySmall)
                        }
                        Switch(modifier=Modifier.semantics { contentDescription="Queued delivery for ${peer.name}" },checked=state.mailboxPeers.any { it.peer==peer.id && it.enabled }, onCheckedChange={model.mailboxConsent(peer.id,it)})
                    }
                }
                if(state.settings.role==Role.SHARER) Text("Pending upload: ${summary.pending} · Stored in mailbox: ${summary.stored} · Received: ${summary.received}")
                if(summary.deletionPending) Text("Mailbox deletion pending. It will retry when this app can connect.",color=MaterialTheme.colorScheme.error)
                if(summary.expired>0) Text("${summary.expired} queued updates expired before confirmed receipt.")
            }
        }
    }
}
