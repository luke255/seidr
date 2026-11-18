package eu.bradshaw.seidr.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.Star
import eu.bradshaw.seidr.ui.LocalActions

/**
 * Stars or unstars an album, artist or playlist, for Home's Starred row. A star,
 * never a heart: the heart is a Loved song. Not shown while browsing as a kid.
 */
@Composable
fun StarButton(star: Star) {
    val actions = LocalActions.current
    val graph = actions.graph
    if (graph.readOnly) return
    val stars by graph.browse.stars.collectAsStateWithLifecycle()
    val on = stars.any { it.id == star.id }
    val unreachable = stringResource(R.string.common_cant_reach_server)
    IconButton(onClick = {
        actions.launch { if (!graph.browse.setStarred(star, !on)) actions.toast(unreachable) }
    }) {
        Icon(
            if (on) Icons.Filled.Star else Icons.Filled.StarBorder,
            stringResource(if (on) R.string.star_remove else R.string.star_add),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
