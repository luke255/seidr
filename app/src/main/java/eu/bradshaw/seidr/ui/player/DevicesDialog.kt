package eu.bradshaw.seidr.ui.player

import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.connect.Peer
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.data.Account.needsLanPermission

/**
 * Seiðr Connect's device picker: this device, then every other one on the
 * same Wi-Fi signed in as this user or signed out. Picking one moves
 * playback there (a signed-out one plays with this login).
 */
@Composable
fun DevicesDialog(onDismiss: () -> Unit) {
    val connect = LocalActions.current.graph.connect
    val devices by connect.devices.collectAsStateWithLifecycle()
    val active by connect.active.collectAsStateWithLifecycle()
    val connecting by connect.connecting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lanMissing by remember { mutableStateOf(needsLanPermission(context)) }
    val lan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        lanMissing = !it
        if (it) { connect.stopDiscovery(); connect.onLanGranted() }
    }
    // Close once the device the user picked is the one playing. A failure stays
    // open, with the reason on that device's row, so they can try again.
    var asked by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(asked, active) { if (asked != null && active?.id == asked) onDismiss() }
    var open by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { open = false } }

    // The device being controlled stays listed even if its advert lapses.
    val others = devices + listOfNotNull(active).filter { a -> devices.none { it.id == a.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Devices, null) },
        title = { Text(stringResource(R.string.devices_title)) },
        text = {
            // The dialog caps the text slot's height, so a long list (or a
            // Fold's cover screen) scrolls rather than clipping devices.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceRow(kindIcon(connect.kind), connect.name, stringResource(R.string.devices_this_device), selected = active == null, busy = false) {
                    connect.playOn(null)
                    onDismiss()
                }
                others.forEach { d ->
                    val error = failed?.takeIf { it.first == d.id && connecting != d.id }?.second
                    DeviceRow(kindIcon(d.kind), d.name, // A TV is everyone's: whether it's signed in doesn't matter to anyone picking it.
                        error ?: if (d.guest && d.kind != Peer.KIND_TV) stringResource(R.string.devices_not_signed_in) else null,
                        selected = active?.id == d.id, busy = connecting == d.id, isError = error != null) {
                        if (connecting != null) return@DeviceRow
                        if (active?.id == d.id) { onDismiss(); return@DeviceRow }
                        asked = d.id
                        failed = null
                        connect.playOn(d) { message ->
                            // Shown here while the dialog is up; a toast otherwise.
                            if (open) { failed = d.id to message; asked = null }
                            open
                        }
                    }
                }
                Spacer(Modifier.padding(top = 12.dp))
                if (lanMissing) {
                    Text(stringResource(R.string.devices_permission_rationale),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (Build.VERSION.SDK_INT >= 37) lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text(stringResource(R.string.devices_allow)) }
                } else if (others.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text(stringResource(R.string.devices_looking),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, label: String?, selected: Boolean, busy: Boolean, isError: Boolean = false, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(icon, null, tint = tint)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = tint, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                busy -> stringResource(R.string.devices_connecting)
                selected && label != stringResource(R.string.devices_this_device) -> stringResource(R.string.devices_connected)
                else -> label
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = when {
                    busy -> MaterialTheme.colorScheme.onSurfaceVariant
                    isError -> MaterialTheme.colorScheme.error
                    selected -> tint
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
    }
}

fun kindIcon(kind: String): ImageVector = when (kind) {
    Peer.KIND_TV -> Icons.Filled.Tv
    Peer.KIND_HEADSET -> Icons.Filled.ViewInAr
    Peer.KIND_SPEAKER -> Icons.Filled.Speaker
    Peer.KIND_SPEAKER_GROUP -> Icons.Filled.SpeakerGroup
    else -> Icons.Filled.Smartphone
}
