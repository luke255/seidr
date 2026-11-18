package eu.bradshaw.seidr.ui.player

import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.vis.PresetPack

/**
 * The visualiser's presets aren't on this device yet ([PresetPack]): offers
 * the download, shows it going, and carries on to the visualiser ([onReady])
 * once it's done.
 */
@Composable
fun PresetPackDialog(onDismiss: () -> Unit, onReady: () -> Unit) {
    val pack = LocalActions.current.graph.presetPack
    val state by pack.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val metered = remember { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }
    LaunchedEffect(state) { if (state == PresetPack.State.Ready) onReady() }
    val downloading = state as? PresetPack.State.Downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vis_pack_title)) },
        text = {
            Column {
                if (downloading != null) {
                    Text(stringResource(R.string.vis_pack_downloading, (downloading.fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator({ downloading.fraction }, Modifier.fillMaxWidth().padding(top = 16.dp))
                } else {
                    Text(stringResource(R.string.vis_pack_missing, PresetPack.SIZE_MB), style = MaterialTheme.typography.bodyMedium)
                    if (state == PresetPack.State.Failed) Text(stringResource(R.string.vis_pack_failed),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    if (metered) Text(stringResource(R.string.vis_pack_metered),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (downloading == null) TextButton(onClick = pack::download) { Text(stringResource(R.string.vis_pack_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(if (downloading != null) R.string.common_close else R.string.settings_cancel)) }
        },
    )
}
