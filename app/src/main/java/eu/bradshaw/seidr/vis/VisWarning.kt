package eu.bradshaw.seidr.vis

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.focusRing

/**
 * Shown once per device for each visualiser, from Now Playing before it first starts: some
 * presets strobe, and in a headset they fill your view. [immersive] picks the
 * headset's wording. Continue marks it seen ([VisPrefs.setWarned]); Back or
 * Not now leaves without starting.
 */
@Composable
fun VisWarning(immersive: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    val go = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { go.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.vis_warning_title)) },
        text = {
            Text(stringResource(if (immersive) R.string.vis_warning_headset else R.string.vis_warning_flat))
        },
        confirmButton = {
            Button(onClick = onContinue, Modifier.focusRequester(go).focusRing(CircleShape)) { Text(stringResource(R.string.vis_continue)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.vis_not_now)) } },
    )
}
