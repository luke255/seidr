package eu.bradshaw.seidr.ui.player

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import eu.bradshaw.seidr.ui.screens.needsLanPermission

/**
 * Seiðr Connect's device picker: this device, then every other one signed
 * in as this user on the same Wi-Fi. Picking one moves playback there.
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
    // Close once a switch the user asked for is done (or failed).
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(asked, connecting) { if (asked && connecting == null) onDismiss() }

    // The device being controlled stays listed even if its advert lapses.
    val others = devices + listOfNotNull(active).filter { a -> devices.none { it.id == a.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Devices, null) },
        title = { Text("Play on") },
        text = {
            Column {
                DeviceRow(kindIcon(connect.kind), connect.name, "This device", selected = active == null, busy = false) {
                    connect.playOn(null)
                    onDismiss()
                }
                others.forEach { d ->
                    DeviceRow(kindIcon(d.kind), d.name, null, selected = active?.id == d.id, busy = connecting == d.id) {
                        if (connecting != null) return@DeviceRow
                        if (active?.id == d.id) { onDismiss(); return@DeviceRow }
                        asked = true
                        connect.playOn(d)
                    }
                }
                Spacer(Modifier.padding(top = 12.dp))
                if (lanMissing) {
                    Text("Seiðr needs the Nearby devices permission to find your other devices.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
                } else if (others.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text("Looking for devices. Open Seiðr on another device signed in as you, on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, label: String?, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
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
                busy -> "Connecting…"
                selected && label == null -> "Connected"
                else -> label
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun kindIcon(kind: String): ImageVector = when (kind) {
    Peer.KIND_TV -> Icons.Filled.Tv
    Peer.KIND_HEADSET -> Icons.Filled.ViewInAr
    else -> Icons.Filled.Smartphone
}
