package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.Account
import eu.bradshaw.seidr.data.KidLogin
import eu.bradshaw.seidr.data.KidRules
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.ToggleItem
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.focusRing
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
    val own by graph.settings.server.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<KidLogin?>(null) }
    var viewing by remember { mutableStateOf<KidLogin?>(null) }
    var help by remember { mutableStateOf(false) }
    // View as: admins only, asked of the server each time (offline, it doesn't show).
    val admin by produceState(false, own) { value = own?.let { Account.isAdmin(graph, it) } == true }

    CardLabel(stringResource(R.string.kids_title))
    CardGroup {
        kids.forEach { kid ->
            CardItem(Icons.Outlined.ChildCare, kid.username, null, trailing = {
                if (admin) IconButton(onClick = { viewing = kid }) { Icon(Icons.Outlined.Visibility, stringResource(R.string.kids_view_as_description, kid.username)) }
                IconButton(onClick = { removing = kid }) { Icon(Icons.Outlined.Close, stringResource(R.string.kids_remove_description, kid.username)) }
            })
            CardDivider()
        }
        CardItem(Icons.Outlined.Add, stringResource(R.string.kids_add), null) { adding = true }
        CardDivider()
        CardItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.kids_how), null) { help = true }
        // Only once the server has them (or it's been switched off, so it can go back on).
        val hideLists by graph.settings.hideKidsPlaylists.collectAsStateWithLifecycle()
        val anyLists = graph.library.allPlaylists.collectAsStateWithLifecycle().value.orEmpty().any { KidRules.isKidsList(it.name) }
        if (!hideLists || anyLists) {
            CardDivider()
            ToggleItem(Icons.Outlined.VisibilityOff, stringResource(R.string.kids_hide_lists),
                stringResource(R.string.settings_hide_lists_hint, KidRules.APPROVED, KidRules.SAFE_TITLES, KidRules.SAFE_ART), hideLists) {
                graph.settings.setHideKidsPlaylists(it)
            }
        }
    }

    if (help) KidsHelpDialog(admin, onDone = { help = false })
    if (adding) AddKidDialog(onDone = { adding = false })
    viewing?.let { kid -> ViewAsDialog(kid, onDone = { viewing = null }) }
    removing?.let { kid ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.kids_remove_title, kid.username)) },
            text = { Text(stringResource(R.string.kids_remove_message, kid.username)) },
            confirmButton = { TextButton(onClick = { graph.settings.removeKid(kid.username); removing = null }) { Text(stringResource(R.string.kids_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.kids_cancel)) } },
        )
    }
}

@Composable
private fun KidsHelpDialog(admin: Boolean, onDone: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.kids_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.kids_help_step1))
                Text(stringResource(R.string.kids_help_step2))
                Text(stringResource(R.string.kids_help_step3))
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    @Composable fun list(name: String, what: String) = Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) }
                        append(stringResource(R.string.kids_help_list_what, what))
                    })
                    list(KidRules.APPROVED, stringResource(R.string.kids_help_approved))
                    list(KidRules.SAFE_TITLES, stringResource(R.string.kids_help_safe_titles))
                    list(KidRules.SAFE_ART, stringResource(R.string.kids_help_safe_art))
                }
                Text(stringResource(R.string.kids_help_hidden), color = muted)
                Text(stringResource(R.string.kids_filter_warning, stringResource(R.string.settings_sign_in_device)), color = MaterialTheme.colorScheme.tertiary)
                if (admin) Text(stringResource(R.string.kids_help_eye), color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.kids_got_it)) } },
    )
}

@Composable
private fun ViewAsDialog(kid: KidLogin, onDone: () -> Unit) {
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.kids_view_as_title, kid.username)) },
        text = {
            Column {
                Text(stringResource(R.string.kids_view_as_message, kid.username))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    error = Account.viewAs(actions.graph, kid)
                    busy = false
                    if (error == null) onDone()
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.kids_view_as_button, kid.username))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(stringResource(R.string.kids_cancel)) } },
    )
}

/** While an admin browses as a kid (see `KidView`): who, and the way back. */
@Composable
fun ViewingAsBanner(modifier: Modifier = Modifier) {
    val graph = LocalActions.current.graph
    val kid = graph.kidView.collectAsStateWithLifecycle().value?.config ?: return
    val own = graph.settings.server.collectAsStateWithLifecycle().value ?: return
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(cs.tertiaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Visibility, null, tint = cs.onTertiaryContainer)
        Text(stringResource(R.string.kids_viewing_as, kid.username), color = cs.onTertiaryContainer, style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        FilledTonalButton(onClick = { graph.stopKidView() }, modifier = Modifier.focusRing(CircleShape)) {
            Text(stringResource(R.string.kids_back_to, own.username), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    val ownAccountError = stringResource(R.string.kids_own_account)

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.kids_add)) },
        text = {
            Column {
                Text(stringResource(R.string.kids_add_message, own?.url?.substringAfter("://").orEmpty()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                // Kid mode is Seiðr's own: the login itself sees everything anywhere else.
                Text(stringResource(R.string.kids_filter_warning, stringResource(R.string.settings_sign_in_device)),
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.kids_username)) }, singleLine = true,
                    keyboardOptions = noCaps, modifier = Modifier.fillMaxWidth().selectToEdit())
                OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.kids_password)) }, singleLine = true,
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
                if (name.equals(server.username, ignoreCase = true)) { error = ownAccountError; return@TextButton }
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
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.kids_add_button))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(stringResource(R.string.kids_cancel)) } },
    )
}
