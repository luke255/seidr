package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.BadKind
import eu.bradshaw.seidr.ui.LocalActions

private val BadKind.icon: ImageVector get() = when (this) {
    BadKind.SONG -> Icons.Outlined.MusicOff
    BadKind.ART -> Icons.Outlined.HideImage
    BadKind.LYRICS -> Icons.Outlined.SpeakerNotesOff
}

/**
 * Menu items for reporting a song to the "Bad: " playlists, or taking a report
 * back. Used inside a DropdownMenu (Now Playing's flag, the track menu).
 */
@Composable
fun ColumnScope.ReportItems(songId: String, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val reported by actions.graph.bad.reported.collectAsStateWithLifecycle()
    val playlists by actions.graph.bad.playlists.collectAsStateWithLifecycle()
    Text("Report a problem", style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(220.dp))
    BadKind.entries.forEach { kind ->
        val on = songId in reported[kind].orEmpty()
        val there = playlists[kind] != null
        DropdownMenuItem(
            text = {
                Text(when {
                    !there -> "No ${kind.playlistName} playlist"
                    on -> "Undo ${kind.noun} report"
                    kind == BadKind.SONG -> "Bad song (skip it)"
                    else -> "Bad ${kind.noun}"
                })
            },
            leadingIcon = { Icon(kind.icon, null) },
            enabled = there,
            onClick = { onDismiss(); actions.reportBad(kind, songId) },
        )
    }
}
