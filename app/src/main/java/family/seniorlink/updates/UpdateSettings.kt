package family.seniorlink.updates

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import family.seniorlink.BuildConfig

@Composable
internal fun UpdateSettings(checking: Boolean, result: UpdateCheckResult?, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Current version: ${BuildConfig.VERSION_NAME}")
            Button(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text("Check for updates")
            }
            val message = when {
                checking -> "Checking for updates…"
                result == UpdateCheckResult.UpToDate -> "You're up to date."
                result == UpdateCheckResult.Failed -> "Couldn't check for updates. Check your connection or try again later."
                result is UpdateCheckResult.Available -> "Version ${result.update.version} is available."
                else -> null
            }
            message?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
