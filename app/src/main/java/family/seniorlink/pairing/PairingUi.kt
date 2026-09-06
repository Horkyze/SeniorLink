package family.seniorlink.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import family.seniorlink.MainViewModel
import family.seniorlink.ScreenState
import family.seniorlink.core.ConnectionPairing
import family.seniorlink.core.Role

@Composable
fun ConnectionSetup(state: ScreenState, model: MainViewModel) {
    val context = LocalContext.current
    val sharing = state.settings.role == Role.SHARER
    var name by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    val ownName = name.trim().ifBlank { if (sharing) "Sharing phone" else "Caregiver" }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        cameraDenied = result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true
        result.contents?.let { model.scanPairing(it, ownName) }
    }
    Text(if (sharing) "Show your QR to the caregiver. Then compare the code on both phones and confirm."
    else "On the sharing phone, open Phones → Connect a caregiver. Scan its QR, then compare the code together.")
    Text("Keep both apps open and connected to the internet.")
    OutlinedTextField(
        name, { name = it.take(40) }, label = { Text("Your name (optional)") },
        placeholder = { Text(if (sharing) "e.g. Grandad" else "e.g. Anna") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = {
        if (sharing) model.showPairingQr(ownName) else scanner.launch(PairingScannerActivity.options())
    }, modifier = Modifier.fillMaxWidth()) { Text(if (sharing) "Connect a caregiver" else "Scan QR") }
    if (!sharing) {
        if (cameraDenied) {
            Text("Camera access is off. Allow it in Settings, or use a shared invitation below.")
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open camera permission settings") }
        }
        TextButton(onClick = { manual = !manual }) { Text("Use a shared invitation instead") }
        if (manual) {
            OutlinedTextField(code, { code = it.take(ConnectionPairing.MAX_CODE) },
                label = { Text("Connection invitation") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.scanPairing(code, ownName) }, enabled = code.isNotBlank()) { Text("Connect") }
        }
    }
}

@Composable
fun ConnectionDialog(model: MainViewModel) {
    val state by model.pairing.state.collectAsStateWithLifecycle()
    PairingDialog(state, model.pairing::approve, model.pairing::decline, model.pairing::dismiss)
}

/** Stateless so the exact screens can also be exercised without camera/network dependencies. */
@Composable
internal fun PairingDialog(state: PairingState, approve: (String) -> Unit, decline: () -> Unit, close: () -> Unit) {
    if (state.step == PairingStep.IDLE) return
    val context = LocalContext.current
    val title = when (state.step) {
        PairingStep.PREPARING -> if (state.hosting) "Getting your QR ready…" else "Connecting…"
        PairingStep.QR -> "Show this QR to the caregiver"
        PairingStep.VERIFY -> if (state.hosting) "Do the codes match?" else "Compare this code"
        PairingStep.SAVING -> "Finishing the connection…"
        PairingStep.CONNECTED -> "You're connected"
        PairingStep.DECLINED -> "Connection declined"
        PairingStep.EXPIRED -> "Let's try again"
        else -> "Couldn't connect"
    }
    AlertDialog(
        onDismissRequest = { if (state.step == PairingStep.VERIFY) decline() else if (state.step != PairingStep.SAVING) close() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state.step) {
                    PairingStep.PREPARING -> {
                        CircularProgressIndicator()
                        Text("Keep both apps open. This may take a few moments.")
                    }
                    PairingStep.QR -> {
                        Text("On the caregiver's phone, open Phones and tap Scan QR.")
                        InvitationQr(state.qr)
                        Text("Waiting for a scan… This QR expires after 5 minutes.")
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("SeniorLink connection invitation", state.qr),
                            )
                        }) { Text("Copy invitation") }
                        TextButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, state.qr)
                            }, "Share connection invitation"))
                        }) { Text("Share invitation") }
                    }
                    PairingStep.VERIFY, PairingStep.SAVING -> {
                        Text(if (state.hosting) "${state.peerName} wants to connect as your caregiver." else "Connecting to ${state.peerName}.")
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val size = minOf(42f, maxWidth.value / (3.2f * LocalDensity.current.fontScale))
                            Text(state.verification, fontSize = size.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
                                maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        if (state.step == PairingStep.SAVING) {
                            CircularProgressIndicator()
                            Text("Confirmed. Keep both apps open while we finish.")
                        } else if (state.hosting) {
                            Text("Check that both phones show the same code.")
                            Text("Allow this caregiver to see your shared updates and saved history while sharing is on.")
                        } else {
                            Text("If both codes match, confirm on the sharing phone.")
                            Text("Waiting for confirmation on the sharing phone…")
                        }
                    }
                    PairingStep.CONNECTED -> {
                        Text("${state.peerName} is now connected. You don't need to scan anything else.")
                        Text(if (state.hosting) "Choose what to share in Settings. When you're ready, tap Start sharing in Updates."
                        else "Updates appear when the sharing phone has Start sharing turned on.")
                    }
                    PairingStep.DECLINED -> Text("To connect, show a new QR and compare the codes again.")
                    PairingStep.EXPIRED -> Text("The invitation has expired. On the sharing phone, tap Connect a caregiver for a new QR.")
                    else -> Unit
                }
                if (state.detail.isNotBlank()) Text(state.detail)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.hosting && state.step == PairingStep.VERIFY) {
                    Button(onClick = { approve(state.requestId) }, modifier = Modifier.fillMaxWidth()) { Text("Codes match — connect") }
                } else if (state.step in listOf(PairingStep.CONNECTED, PairingStep.DECLINED, PairingStep.EXPIRED, PairingStep.ERROR)) {
                    Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                if (state.step == PairingStep.VERIFY) {
                    OutlinedButton(onClick = decline, modifier = Modifier.fillMaxWidth()) { Text("Codes don't match") }
                } else if (state.step in listOf(PairingStep.PREPARING, PairingStep.QR)) {
                    TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        },
    )
}

@Composable
private fun InvitationQr(code: String) {
    val bitmap = remember(code) {
        val size = 720
        val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, size, 0, 0, size, size)
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = "Connection QR code", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
}
