package family.seniorlink.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import family.seniorlink.BuildConfig

@Composable
internal fun UpdateDialog(update: AppUpdate, dismiss: () -> Unit) {
    val context = LocalContext.current
    var openFailed by remember(update) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Update SeniorLink?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Version ${update.version} is available. You have ${BuildConfig.VERSION_NAME}.")
                Text("Download the APK, then open it to update SeniorLink.")
                if (openFailed) {
                    Text("No browser could open the download. Copy this link into a browser:")
                    SelectionContainer { Text(update.downloadUrl) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri()))
                    dismiss()
                } catch (_: ActivityNotFoundException) {
                    openFailed = true
                } catch (_: SecurityException) {
                    openFailed = true
                }
            }) { Text("Download update") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Later") } },
    )
}
