package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.components.ToggleItem
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.vis.VisPrefs
import eu.bradshaw.seidr.vis.VisualiserActivity

/**
 * Settings → Visualiser: the track info toggle, and the loved and hidden
 * presets (this device's), each previewed on tap and undone with its button.
 */
@Composable
fun VisualiserSettingsScreen() {
    val prefs = LocalActions.current.graph.visPrefs
    val context = LocalContext.current
    val showInfo by prefs.showInfo.collectAsStateWithLifecycle()
    val showStats by prefs.showStats.collectAsStateWithLifecycle()
    val loved by prefs.loved.collectAsStateWithLifecycle()
    val hidden by prefs.hidden.collectAsStateWithLifecycle()
    val preview = { p: VisPrefs.Preset -> VisualiserActivity.preview(context, p.path) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.vissettings_title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item {
                CardGroup {
                    ToggleItem(Icons.Outlined.Info, stringResource(R.string.vissettings_track_info), stringResource(R.string.vissettings_track_info_hint),
                        showInfo) { prefs.setShowInfo(it) }
                    CardDivider()
                    ToggleItem(Icons.Outlined.Speed, stringResource(R.string.vissettings_performance), stringResource(R.string.vissettings_performance_hint),
                        showStats) { prefs.setShowStats(it) }
                }
            }

            item { CardLabel(stringResource(R.string.vissettings_loved)) }
            item { Hint(stringResource(R.string.vissettings_loved_hint)) }
            if (loved.isNotEmpty()) item {
                CardGroup {
                    loved.forEachIndexed { i, p ->
                        if (i > 0) CardDivider()
                        PresetRow(p, p.folder, stringResource(R.string.vissettings_unlove), onClick = { preview(p) }) { prefs.setLoved(p.path, false) }
                    }
                }
            }

            item { CardLabel(stringResource(R.string.vissettings_hidden)) }
            item {
                Hint(stringResource(R.string.vissettings_hidden_hint))
            }
            if (hidden.isNotEmpty()) item {
                CardGroup {
                    hidden.forEachIndexed { i, p ->
                        if (i > 0) CardDivider()
                        val why = stringResource(if (p.byUser) R.string.vissettings_hidden_by_you else R.string.vissettings_too_heavy)
                        PresetRow(p, listOf(why, p.folder).filter { it.isNotEmpty() }.joinToString(" · "), stringResource(R.string.vissettings_unhide),
                            onClick = { preview(p) }) { prefs.unhide(p.path) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp),
)

/** A preset's name and [subtitle], previewed on tap, with [action] beside it (not inside: a remote must reach both). */
@Composable
private fun PresetRow(preset: VisPrefs.Preset, subtitle: String, action: String, onClick: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onAction, Modifier.padding(end = 8.dp)) { Text(action) }
    }
}
