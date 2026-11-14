package eu.bradshaw.seidr.ui.pair

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material3.RadioButton
import eu.bradshaw.seidr.data.KidLogin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.connect.Connect
import eu.bradshaw.seidr.connect.Peer
import eu.bradshaw.seidr.data.Account
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.LocalBottomChrome
import eu.bradshaw.seidr.ui.player.kindIcon
import kotlinx.coroutines.launch

/**
 * Settings → Sign in a device: the signed-out devices Connect can see on this
 * network. Picking one checks its code, then hands it this device's login.
 */
@Composable
fun SignInDeviceScreen() {
    val actions = LocalActions.current
    val connect = actions.graph.connect
    val devices by connect.devices.collectAsStateWithLifecycle()
    val server by actions.graph.settings.server.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<Peer?>(null) }
    var result by remember { mutableStateOf<Pair<Peer, Connect.SignInResult>?>(null) }
    // Who to sign the device in as: this device's login (null), or one of the kids'.
    val kids by actions.graph.settings.kidLogins.collectAsStateWithLifecycle()
    var asKid by remember { mutableStateOf<KidLogin?>(null) }
    val who = asKid?.username ?: server?.username.orEmpty()
    var lanMissing by remember { mutableStateOf(Account.needsLanPermission(context)) }
    val lan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        lanMissing = !it
        if (it) { connect.stopDiscovery(); connect.onLanGranted() }
    }
    val signedOut = devices.filter { it.guest }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Sign in a device")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp + LocalBottomChrome.current),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val w = working
            val r = result
            when {
                w != null -> Column(Modifier.padding(24.dp)) { Working("Signing in ${w.name}…") }
                r != null -> {
                    val (peer, outcome) = r
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (outcome is Connect.SignInResult.Done) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("${peer.name} is signed in as $who", textAlign = TextAlign.Center)
                            Button(onClick = { actions.nav.pop() }) { Text("Done") }
                        } else {
                            Text((outcome as? Connect.SignInResult.Failed)?.message ?: "Cancelled", textAlign = TextAlign.Center)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { actions.nav.pop() }) { Text("Close") }
                                Button(onClick = { result = null }) { Text("Try again") }
                            }
                        }
                    }
                }
                else -> {
                    Hint("Open Seiðr on the device you want to sign in, on the same Wi-Fi as this one. " +
                        "It shows up here while it's signed out. You'll check that both screens show the same code, " +
                        "then it signs in as $who." +
                        if (server?.isKid == true) " It'll be a kid's device too, with the same songs." else "")
                    // Kids' devices don't keep other logins, so only a grown-up's has a choice.
                    if (kids.isNotEmpty() && server?.isKid != true) {
                        CardGroup {
                            CardItem(Icons.Filled.Person, server?.username.orEmpty(), "You",
                                trailing = { RadioButton(asKid == null, null) }) { asKid = null }
                            kids.forEach { kid ->
                                CardItem(Icons.Outlined.ChildCare, kid.username, "Kid: approved songs only",
                                    trailing = { RadioButton(asKid == kid, null) }) { asKid = kid }
                            }
                        }
                    }
                    if (signedOut.isNotEmpty()) {
                        CardGroup {
                            signedOut.forEach { peer ->
                                CardItem(kindIcon(peer.kind), peer.name, "Not signed in") {
                                    if (working != null) return@CardItem
                                    working = peer
                                    scope.launch {
                                        val outcome = connect.signIn(peer, asKid)
                                        working = null
                                        if (outcome !is Connect.SignInResult.Cancelled) result = peer to outcome
                                    }
                                }
                            }
                        }
                    }
                    if (lanMissing) {
                        Hint("Seiðr needs the Nearby devices permission to find other devices.")
                        TextButton(onClick = { lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
                    } else if (signedOut.isEmpty()) {
                        LinearProgressIndicator(Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(horizontal = 24.dp))
                        Hint("Looking for signed-out devices…")
                    }
                }
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp))
}

@Composable
fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/** The name people gave the device (e.g. "Living Room TV"), else its model. */
fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
