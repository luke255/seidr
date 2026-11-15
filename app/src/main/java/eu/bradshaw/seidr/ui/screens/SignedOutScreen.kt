package eu.bradshaw.seidr.ui.screens

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import eu.bradshaw.seidr.ui.keys
import eu.bradshaw.seidr.ui.focusWhenReady
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.connect.Connect
import eu.bradshaw.seidr.ui.AppActions
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.LocalPrimaryFocus
import eu.bradshaw.seidr.ui.Navigator
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.overlayFocus
import eu.bradshaw.seidr.ui.pair.deviceName
import eu.bradshaw.seidr.ui.player.NowPlayingScreen
import eu.bradshaw.seidr.ui.player.QueueScreen
import eu.bradshaw.seidr.ui.primaryFocus

/**
 * Signed out, the app is a Now Playing screen. Idle, it shows the device's
 * name and a way to log in; once another device plays here over Connect, it's
 * that music's Now Playing (lyrics, visualiser and a read-only queue, and
 * nowhere else). A code shows over either while someone checks it.
 */
@Composable
fun SignedOutScreen(graph: Graph, nav: Navigator) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = remember(nav) { AppActions(graph, nav, snackbar, scope) }
    val guest by graph.guest.collectAsStateWithLifecycle()
    var login by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(actions) { graph.connect.messages.collect { actions.toast(it) } }

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                guest != null -> GuestPlayer(actions)
                login -> LoginScreen(graph, onBack = { login = false })
                else -> Idle(onLogin = { login = true })
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp))
        }
        ShownCode(graph.connect)
        SignInRequestDialog(graph.connect)
    }
}

@Composable
private fun GuestPlayer(actions: AppActions) {
    val npPrimary = remember { FocusRequester() }
    val queuePrimary = remember { FocusRequester() }
    CompositionLocalProvider(LocalPrimaryFocus provides npPrimary) {
        NowPlayingScreen(onClose = null, onQueue = { actions.queueOpen = true },
            modifier = Modifier.overlayFocus(!actions.queueOpen, npPrimary))
    }
    androidx.activity.compose.BackHandler(enabled = actions.queueOpen) { actions.queueOpen = false }
    AnimatedVisibility(actions.queueOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        CompositionLocalProvider(LocalPrimaryFocus provides queuePrimary) {
            QueueScreen(onClose = { actions.queueOpen = false }, readOnly = true,
                modifier = Modifier.overlayFocus(actions.queueOpen, queuePrimary))
        }
    }
}

/**
 * Nothing playing and no one signed in: Now Playing's layout with the app's
 * logo for art, the device's name for a title, how to play here, and Log in.
 */
@Composable
private fun Idle(onLogin: () -> Unit) {
    val context = LocalContext.current
    val name = remember { deviceName(context) }
    val cs = MaterialTheme.colorScheme
    val logo: @Composable (Modifier) -> Unit = { m ->
        Box(m.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(colorResource(R.color.icon_bg)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = stringResource(R.string.login_logo_description), modifier = Modifier.fillMaxSize())
        }
    }
    // Ready to play is the headline; Log in is there but quiet (outlined, set
    // apart), so a kid looking for "the button" doesn't land on it.
    val words: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReadyPill(Modifier.padding(bottom = 12.dp))
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(stringResource(R.string.login_idle_hint), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center)
            OutlinedButton(onClick = onLogin, modifier = Modifier.padding(top = 40.dp).focusRing(CircleShape).primaryFocus()) {
                Text(stringResource(R.string.login_log_in))
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.55f), cs.background)))
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth
        val h = maxHeight
        if (w > h * 1.25f) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                logo(Modifier.size(minOf(h - 48.dp, w * 0.5f)))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { words() }
            }
        } else {
            Column(Modifier.fillMaxHeight().widthIn(max = 520.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                logo(Modifier.fillMaxWidth().padding(8.dp))
                Box(Modifier.padding(top = 24.dp)) { words() }
            }
        }
    }
}

/** "Ready to play", with a slowly breathing dot: this device is waiting to be played on. */
@Composable
private fun ReadyPill(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "ready").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dot",
    )
    Row(
        modifier.clip(CircleShape).background(cs.primary.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.background(cs.primary, CircleShape))
        Text(stringResource(R.string.login_idle_ready), style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
}

/** Host side: the code another device is checking against, until it's done. */
@Composable
private fun ShownCode(connect: Connect) {
    val shown by connect.shown.collectAsStateWithLifecycle()
    val s = shown ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.login_pairing_code)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(spaced(s.code), fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.login_check_same_code, s.from), textAlign = TextAlign.Center)
            }
        },
        confirmButton = {},
    )
}

/**
 * Host side: another device wants to sign this one in. Nothing happens until
 * someone here allows it. With a remote, Don't allow has focus first.
 */
@Composable
private fun SignInRequestDialog(connect: Connect) {
    val request by connect.signInRequest.collectAsStateWithLifecycle()
    val r = request ?: return
    val deny = remember(r) { FocusRequester() }
    val input = LocalInputModeManager.current
    LaunchedEffect(r) { if (input.keys) deny.focusWhenReady() }
    AlertDialog(
        onDismissRequest = r::deny,
        title = { Text(stringResource(R.string.login_sign_in_this_device)) },
        text = {
            Text(
                stringResource(
                    if (r.kid) R.string.login_sign_in_request_kid else R.string.login_sign_in_request,
                    r.from, r.username, r.server,
                ),
            )
        },
        confirmButton = { TextButton(onClick = r::allow) { Text(stringResource(R.string.login_allow)) } },
        dismissButton = { TextButton(onClick = r::deny, modifier = Modifier.focusRequester(deny)) { Text(stringResource(R.string.login_dont_allow)) } },
    )
}

/** "123456" as "123 456". */
fun spaced(code: String) = if (code.length == 6) code.take(3) + " " + code.drop(3) else code

/** Controller side: compare the code with the other screen before trusting it. */
@Composable
fun CheckCode(connect: Connect, username: String?) {
    val check by connect.check.collectAsStateWithLifecycle()
    val c = check ?: return
    AlertDialog(
        onDismissRequest = c::cancel,
        title = { Text(stringResource(if (c.signingIn) R.string.login_sign_in_peer else R.string.login_play_on_peer, c.peer.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_check_this_code, c.peer.name), textAlign = TextAlign.Center)
                Text(spaced(c.code), fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    if (c.signingIn) c.signingInAs?.let { stringResource(R.string.login_will_sign_in_kid, it) }
                        ?: stringResource(R.string.login_will_sign_in, username.orEmpty())
                    else stringResource(R.string.login_will_play),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = c::confirm) { Text(stringResource(R.string.login_it_matches)) } },
        dismissButton = { TextButton(onClick = c::cancel) { Text(stringResource(R.string.login_cancel)) } },
    )
}
