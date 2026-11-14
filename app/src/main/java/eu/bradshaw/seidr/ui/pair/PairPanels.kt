package eu.bradshaw.seidr.ui.pair

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.data.pair.PairCode
import eu.bradshaw.seidr.data.pair.PairHost
import eu.bradshaw.seidr.data.pair.PairOutcome
import eu.bradshaw.seidr.data.pair.PairRole
import eu.bradshaw.seidr.ui.screens.needsLanPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A shown code is replaced after this long, so a photo of it soon stops working. */
private const val CODE_LIFETIME_MS = 5 * 60_000L

/** Width for the code and the viewfinder: big enough to scan from across a room on a TV. */
val PairBoxWidth = 320.dp

/**
 * Shows a pairing code and waits for one device to complete the exchange.
 * [role] is read when each new code starts. [onOutcome] gets the result once;
 * the caller decides what replaces this panel.
 */
@Composable
fun ShowCodePanel(role: () -> PairRole, hint: String, onOutcome: (PairOutcome) -> Unit) {
    val context = LocalContext.current
    val currentRole by rememberUpdatedState(role)
    val finish by rememberUpdatedState(onOutcome)
    var generation by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<PairCode?>(null) }
    var peer by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    RequestLanPermission()

    LaunchedEffect(generation) {
        peer = null
        val r = currentRole()
        val host = withContext(Dispatchers.IO) { PairHost.open(deviceName(context), r.giving) }
        noNetwork = host == null
        code = host?.code
        host?.use { finish(it.await(r) { name -> peer = name }) }
    }
    // Refresh an unused code; never cut off a device that's mid-exchange.
    LaunchedEffect(generation, peer) {
        if (peer != null) return@LaunchedEffect
        delay(CODE_LIFETIME_MS)
        generation++
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val c = code
        when {
            noNetwork -> {
                Text("Connect to Wi-Fi (or Tailscale) to show a code.", textAlign = TextAlign.Center)
                OutlinedButton(onClick = { generation++ }) { Text("Try again") }
            }
            c == null -> CircularProgressIndicator()
            else -> {
                QrCode(c.encode(), Modifier.widthIn(max = PairBoxWidth).fillMaxWidth())
                val p = peer
                if (p == null) Hint(hint) else Working("Signing in with $p…")
            }
        }
    }
}

/**
 * Asks for the camera and scans until it reads a Seiðr code. Other QR codes
 * are ignored with a note.
 */
@Composable
fun ScanCodePanel(hint: String, onCode: (PairCode) -> Unit) {
    val context = LocalContext.current
    // On a Quest the passthrough cameras need their own permission too.
    var granted by remember { mutableStateOf(hasCamera(context) && !needsHeadsetCamera(context)) }
    var foreign by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasCamera(context) && !needsHeadsetCamera(context)
    }
    RequestLanPermission()
    LaunchedEffect(Unit) { if (!granted) ask.launch(cameraPermissions()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (granted) {
            QrScanner(
                onText = { text -> PairCode.parse(text)?.let(onCode) ?: run { foreign = true } },
                prompt = if (isQuest) "Look at the code on the other device" else "Point the camera at the code on the other device",
                modifier = Modifier.widthIn(max = PairBoxWidth).fillMaxWidth(),
            )
            Hint(if (foreign) "That isn't a Seiðr code. $hint" else hint)
        } else if (isQuest) {
            Hint("Seiðr needs the headset cameras to scan the code. When your Quest asks, tick Headset cameras, then Allow. " +
                "If it doesn't ask, allow Camera and Headset cameras in the app's settings.")
            Button(onClick = { ask.launch(cameraPermissions()) }) { Text("Allow cameras") }
        } else {
            Hint("Seiðr needs the camera to scan the code. If it doesn't ask, allow Camera in the app's system settings.")
            Button(onClick = { ask.launch(cameraPermissions()) }) { Text("Allow camera") }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 420.dp))
}

@Composable
fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/** Android 17 blocks LAN peers without this. Asked once per panel; Tailscale works either way. */
@Composable
private fun RequestLanPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (needsLanPermission(context)) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
}

/** The name people gave the device (e.g. "Living Room TV"), else its model. */
fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/** TVs without a camera hide the scan option; everything else offers it and says so if there's no camera. */
fun canOfferScan(context: Context): Boolean {
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) || !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Quest (Horizon OS) gates its passthrough cameras behind an extra runtime permission. */
private const val HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA"
internal val isQuest = Build.MANUFACTURER.equals("Oculus", ignoreCase = true) || Build.MANUFACTURER.equals("Meta", ignoreCase = true)

private fun cameraPermissions(): Array<String> =
    if (isQuest) arrayOf(Manifest.permission.CAMERA, HEADSET_CAMERA) else arrayOf(Manifest.permission.CAMERA)

private fun hasCamera(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun needsHeadsetCamera(context: Context) =
    isQuest && context.checkSelfPermission(HEADSET_CAMERA) != PackageManager.PERMISSION_GRANTED
