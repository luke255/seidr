package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import eu.bradshaw.seidr.ui.pair.deviceName
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.Account
import androidx.activity.compose.BackHandler
import eu.bradshaw.seidr.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.bradshaw.seidr.data.PlainHttp

/**
 * Server URL, username, password, then a ping to prove it works. Or, from a
 * device that's already signed in, Settings → Sign in a device (over Connect).
 * [onBack] returns to the signed-out Now Playing screen.
 */
@Composable
fun LoginScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    // An http:// server on the open internet: confirm before signing in.
    var exposed by remember { mutableStateOf<ServerConfig?>(null) }

    fun connect() {
        busy = true; error = null
        val config = ServerConfig(Settings.normaliseUrl(url), user.trim(), pass)
        scope.launch {
            error = Account.probe(graph, context, config)
            if (error == null) {
                if (withContext(Dispatchers.IO) { PlainHttp.exposed(config.url) }) exposed = config
                else Account.commit(graph, config)
            }
            busy = false
        }
    }

    // Android 17 blocks LAN addresses without this; ask before the first ping.
    val lanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { connect() }

    exposed?.let { config ->
        AlertDialog(
            onDismissRequest = { exposed = null },
            title = { Text(stringResource(R.string.login_unencrypted_title)) },
            text = {
                Text(stringResource(R.string.login_unencrypted_message))
            },
            confirmButton = { TextButton(onClick = { exposed = null; Account.commit(graph, config) }) { Text(stringResource(R.string.login_connect_anyway)) } },
            dismissButton = { TextButton(onClick = { exposed = null }) { Text(stringResource(R.string.login_go_back)) } },
        )
    }

    // Tall windows stack the heading, form and hint; wide ones (TVs, Quest panels,
    // landscape phones) put the form on the right.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        val field = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        val name = remember { deviceName(context) }
        val heading: @Composable ColumnScope.() -> Unit = {
            eu.bradshaw.seidr.ui.components.SeidrBanner()
            Text(stringResource(R.string.login_heading), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val form: @Composable ColumnScope.() -> Unit = {
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.login_server_url)) }, placeholder = { Text(stringResource(R.string.login_server_url_placeholder)) },
                singleLine = true, keyboardOptions = noAutoCaps.copy(keyboardType = KeyboardType.Uri), modifier = field.selectToEdit())
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.login_username)) }, singleLine = true,
                keyboardOptions = noAutoCaps, modifier = field.selectToEdit())
            OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.login_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = field.selectToEdit())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                onClick = {
                    if (Account.needsLanPermission(context)) lanPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else connect()
                },
                modifier = field.focusRing(CircleShape),
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.login_log_in))
            }
        }
        val other: @Composable ColumnScope.() -> Unit = {
            Text(stringResource(R.string.login_other_device_title), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.login_other_device_steps, name),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 420.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.login_back)) }
        }
        val column = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)
        val arrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        if (wide) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight().then(column), arrangement, Alignment.CenterHorizontally) {
                    heading()
                    other()
                }
                Column(Modifier.weight(1f).fillMaxHeight().then(column), arrangement, Alignment.CenterHorizontally) { form() }
            }
        } else {
            Column(Modifier.fillMaxSize().then(column), arrangement, Alignment.CenterHorizontally) {
                heading()
                form()
                other()
            }
        }
    }
}

/** Samsung's keyboard capitalises the first letter unless told not to, even for Uri fields. */
private val noAutoCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
