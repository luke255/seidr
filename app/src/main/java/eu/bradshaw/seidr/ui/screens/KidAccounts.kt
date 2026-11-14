package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.Account
import eu.bradshaw.seidr.data.KidLogin
import eu.bradshaw.seidr.data.KidRules
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.selectToEdit
import kotlinx.coroutines.launch

/**
 * Settings → Kids (grown-ups only): kids' logins this device keeps, purely
 * so Sign in a device can sign their devices in. Kids never see them.
 */
@Composable
fun KidAccountsSection() {
    val actions = LocalActions.current
    val graph = actions.graph
    val kids by graph.settings.kidLogins.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<KidLogin?>(null) }

    CardLabel("Kids")
    CardGroup {
        kids.forEach { kid ->
            CardItem(Icons.Outlined.ChildCare, kid.username, "Sign in their devices from Sign in a device", trailing = {
                IconButton(onClick = { removing = kid }) { Icon(Icons.Outlined.Close, "Remove ${kid.username}") }
            })
            CardDivider()
        }
        CardItem(Icons.Outlined.Add, "Add a kid's account", null) { adding = true }
        Text(
            "Add each child's own Navidrome account (not an admin), then sign their device in with Sign in a device. " +
                "It plays only the songs in your \"${KidRules.APPROVED}\" playlist. Songs only in \"${KidRules.SAFE_TITLES}\" " +
                "or \"${KidRules.SAFE_ART}\" show greyed out, with just their name or cover; everything else stays hidden. " +
                "Adding a kid creates these playlists on your account if they're missing. Seiðr keeps them out of your library, " +
                "so approve songs with Navidrome's web player or another app. Keep the kid's password to yourself: they sign in through you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 14.dp),
        )
    }

    if (adding) AddKidDialog(onDone = { adding = false })
    removing?.let { kid ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${kid.username}?") },
            text = { Text("This device forgets their login. Devices already signed in as ${kid.username} stay signed in.") },
            confirmButton = { TextButton(onClick = { graph.settings.removeKid(kid.username); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddKidDialog(onDone: () -> Unit) {
    val actions = LocalActions.current
    val graph = actions.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val own = graph.settings.server.collectAsStateWithLifecycle().value
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Add a kid's account") },
        text = {
            Column {
                Text("Their Navidrome login on ${own?.url?.substringAfter("://").orEmpty()}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
                    keyboardOptions = noCaps, modifier = Modifier.fillMaxWidth().selectToEdit())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).selectToEdit())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && user.isNotBlank() && pass.isNotBlank() && own != null, onClick = {
                val server = own ?: return@TextButton
                val name = user.trim()
                if (name.equals(server.username, ignoreCase = true)) { error = "That's your own account"; return@TextButton }
                busy = true; error = null
                scope.launch {
                    error = Account.probeKid(graph, context, ServerConfig(server.url, name, pass))
                    busy = false
                    if (error == null) {
                        graph.settings.saveKid(KidLogin(name, pass))
                        // The lists their device reads: created (public) under this login if missing.
                        graph.kids.ensureLists()?.let(actions::toast)
                        onDone()
                    }
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("Add")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") } },
    )
}
