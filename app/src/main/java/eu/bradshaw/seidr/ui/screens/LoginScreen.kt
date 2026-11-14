package eu.bradshaw.seidr.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.bradshaw.seidr.ui.selectToEdit
import eu.bradshaw.seidr.ui.focusRing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.pair.PairOutcome
import eu.bradshaw.seidr.data.pair.PairRole
import eu.bradshaw.seidr.data.pair.joinPair
import eu.bradshaw.seidr.ui.pair.ScanCodePanel
import eu.bradshaw.seidr.ui.pair.ShowCodePanel
import eu.bradshaw.seidr.ui.pair.Working
import eu.bradshaw.seidr.ui.pair.canOfferScan
import eu.bradshaw.seidr.ui.pair.deviceName
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.api.ResponseStore
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.bradshaw.seidr.data.PlainHttp

private enum class LoginMode { FORM, SHOW_CODE, SCAN }

/**
 * First launch: server URL, username, password, then a ping to prove it works.
 * Or take the login from a signed-in device by QR, either way round (see `Pairing`).
 */
@Composable
fun LoginScreen(graph: Graph) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // A TV has no keyboard worth typing a password on, so it starts on its code.
    var mode by rememberSaveable { mutableStateOf(if (isTv(context)) LoginMode.SHOW_CODE else LoginMode.FORM) }
    var joining by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offerScan = remember { canOfferScan(context) }

    // An http:// server on the open internet: confirm before signing in.
    var exposed by remember { mutableStateOf<ServerConfig?>(null) }

    fun connect() {
        busy = true; error = null
        val config = ServerConfig(Settings.normaliseUrl(url), user.trim(), pass)
        scope.launch {
            error = probeServer(graph, context, config)
            if (error == null) {
                if (withContext(Dispatchers.IO) { PlainHttp.exposed(config.url) }) exposed = config
                else commitServer(graph, config)
            }
            busy = false
        }
    }

    /** A pairing ended: sign in, or put what arrived in the form with the reason it failed. */
    fun finish(outcome: PairOutcome) {
        val config = outcome.config
        if (outcome.error == null && config != null) {
            commitServer(graph, config)
            return
        }
        if (config != null) { url = config.url; user = config.username; pass = config.password }
        error = outcome.error
        mode = LoginMode.FORM
    }

    val taker = PairRole.Taker { config -> probeServer(graph, context, config) }

    // Android 17 blocks LAN addresses without this; ask before the first ping.
    val lanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { connect() }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        exposed?.let { config ->
            AlertDialog(
                onDismissRequest = { exposed = null },
                title = { Text("Unencrypted connection") },
                text = {
                    Text("This server is on the internet, but its address starts with http://, so your login and music " +
                        "travel unencrypted. Anyone along the way could read them and reuse your login. " +
                        "Use an https:// address if your server has one.")
                },
                confirmButton = { TextButton(onClick = { exposed = null; commitServer(graph, config) }) { Text("Connect anyway") } },
                dismissButton = { TextButton(onClick = { exposed = null }) { Text("Go back") } },
            )
        }
        eu.bradshaw.seidr.ui.components.SeidrBanner()
        val field = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        val signedInHint = "Signed in elsewhere? Open Seiðr there, go to Settings → Sign in another device"
        joining?.let { Working("Signing in from $it…") }
        if (joining == null) when (mode) {
            LoginMode.SHOW_CODE -> {
                Text("Scan to sign in", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ShowCodePanel(
                    role = { taker },
                    hint = "$signedInHint and scan this code. Both devices need to be on the same Wi-Fi or tailnet.",
                    onOutcome = ::finish,
                )
                TextButton(onClick = { mode = LoginMode.FORM }) { Text("Sign in with a password instead") }
            }
            LoginMode.SCAN -> {
                Text("Scan a signed-in device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScanCodePanel("$signedInHint and choose Show a code.") { code ->
                    if (!code.giving) {
                        finish(PairOutcome(code.name, "${code.name} isn't signed in either. Scan the code in a signed-in device's Settings."))
                    } else {
                        joining = code.name
                        scope.launch {
                            val outcome = joinPair(code, taker, deviceName(context))
                            joining = null
                            finish(outcome)
                        }
                    }
                }
                TextButton(onClick = { mode = LoginMode.FORM }) { Text("Sign in with a password instead") }
            }
            LoginMode.FORM -> {
                Text("Connect to your Navidrome server", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://10.0.0.5:4533") },
                    singleLine = true, keyboardOptions = noAutoCaps.copy(keyboardType = KeyboardType.Uri), modifier = field.selectToEdit())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
                    keyboardOptions = noAutoCaps, modifier = field.selectToEdit())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = field.selectToEdit())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                    onClick = {
                        if (needsLanPermission(context)) lanPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else connect()
                    },
                    modifier = field.focusRing(CircleShape),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("Connect")
                }
                Text("Or sign in from a device that's already signed in",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (offerScan) OutlinedButton(onClick = { error = null; mode = LoginMode.SCAN }) { Text("Scan a code") }
                    OutlinedButton(onClick = { error = null; mode = LoginMode.SHOW_CODE }) { Text("Show a code") }
                }
            }
        }
    }
}

/**
 * Pings [config] with a throwaway client. Returns an error message, or null if
 * the server answered. Doesn't sign in: saving the config switches the whole app over.
 */
suspend fun probeServer(graph: Graph, context: Context, config: ServerConfig): String? {
    val probe = SubsonicClient({ config }, "", graph.baseHttp,
        ResponseStore(java.io.File(context.cacheDir, "login-probe")), graph.status)
    return try {
        val r = probe.strict.ping().ok()
        android.util.Log.i("Login", "connected to ${r.type} ${r.serverVersion}")
        null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        "Couldn't connect to ${config.url}: ${e.message ?: e.javaClass.simpleName}" +
            if (needsLanPermission(context)) "\nSeiðr needs the Nearby devices / local network permission to reach a server on your home network." else ""
    }
}

/** Signs in with a config [probeServer] accepted. */
fun commitServer(graph: Graph, config: ServerConfig) {
    graph.client.clearCache()
    graph.settings.saveServer(config)
    graph.sync.schedulePeriodic()
    graph.sync.request()
    graph.live.start()
    graph.connect.startDiscovery() // held back while signed out
    // On the app scope: this screen leaves composition as soon as the config is saved.
    graph.scope.launch { runCatching { graph.library.refreshStarred() } }
}

fun needsLanPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= 37 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

/** Samsung's keyboard capitalises the first letter unless told not to, even for Uri fields. */
private val noAutoCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
