package eu.bradshaw.seidr.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.pair.PairCode
import eu.bradshaw.seidr.data.pair.PairOutcome
import eu.bradshaw.seidr.data.pair.PairRole
import eu.bradshaw.seidr.data.pair.joinPair
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.BackTopBar
import kotlinx.coroutines.launch

/** Settings → Sign in another device: hands this device's login to a new one, by either side's code. */
@Composable
fun LinkDeviceScreen() {
    val actions = LocalActions.current
    val context = LocalContext.current
    val server by actions.graph.settings.server.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val offerScan = remember { canOfferScan(context) }
    var scanning by rememberSaveable { mutableStateOf(offerScan) }
    var confirm by remember { mutableStateOf<PairCode?>(null) }
    var joining by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<PairOutcome?>(null) }
    var attempt by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Sign in another device")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp + eu.bradshaw.seidr.ui.components.LocalBottomChrome.current),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val config = server ?: return@Column
            val done = outcome
            when {
                done != null -> Result(done, onAgain = { outcome = null; attempt++ }, onClose = { actions.nav.pop() })
                joining != null -> Working("Signing in $joining…")
                else -> {
                    if (offerScan) {
                        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                            SegmentedButton(scanning, { scanning = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Scan its code") }
                            SegmentedButton(!scanning, { scanning = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Show a code") }
                        }
                    }
                    // Keyed on the attempt so "Try again" starts a fresh code or scan.
                    androidx.compose.runtime.key(attempt, scanning) {
                        if (scanning) {
                            ScanCodePanel("On the other device, open Seiðr and choose Show a code.") { code ->
                                if (confirm == null && joining == null) {
                                    if (code.giving) outcome = PairOutcome(code.name, "${code.name} is already signed in.")
                                    else confirm = code
                                }
                            }
                        } else {
                            eu.bradshaw.seidr.ui.components.SeidrBanner()
                            ShowCodePanel(
                                role = { PairRole.Giver(config) },
                                hint = "On the other device, open Seiðr and choose Scan a code. Both devices need to be on the same Wi-Fi or tailnet.",
                                onOutcome = { outcome = it },
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { code ->
        val config = server ?: return@let
        AlertDialog(
            onDismissRequest = { confirm = null; attempt++ },
            title = { Text("Sign in ${code.name}?") },
            text = { Text("It will use your login for ${config.url} as ${config.username}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    joining = code.name
                    scope.launch {
                        outcome = joinPair(code, PairRole.Giver(config), deviceName(context))
                        joining = null
                    }
                }) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { confirm = null; attempt++ }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Result(outcome: PairOutcome, onAgain: () -> Unit, onClose: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val error = outcome.error
        if (error == null) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Signed in on ${outcome.peer}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Button(onClick = onClose) { Text("Done") }
            OutlinedButton(onClick = onAgain) { Text("Sign in another device") }
        } else {
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
            Button(onClick = onAgain) { Text("Try again") }
        }
    }
}
