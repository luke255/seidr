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
        title = { Text("Flashing lights") },
        text = {
            Text(
                if (immersive) {
                    "Some presets flash, strobe and swirl quickly, and in the headset they fill your view. " +
                        "That can trigger seizures in people with photosensitive epilepsy, and can bring on " +
                        "motion sickness or disorientation. Stay seated, and if you feel unwell, take the headset off."
                } else {
                    "Some presets flash, strobe and swirl quickly. That can trigger seizures in people with " +
                        "photosensitive epilepsy. If you feel unwell, look away and close the visualiser."
                },
            )
        },
        confirmButton = {
            Button(onClick = onContinue, Modifier.focusRequester(go).focusRing(CircleShape)) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now") } },
    )
}
