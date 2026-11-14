package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.focusRing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Loading / error / content for a screen's single async load. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

@Composable
fun <T> LoadContent(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Error -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Couldn't load this", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = onRetry, modifier = Modifier.focusRing(CircleShape)) { Text("Retry") }
        }
        is Load.Ready -> content(state.value)
    }
}

/**
 * Pull to refresh, but not on TV: a remote can't pull, and focus scrolling a
 * list at its top was dragging the spinner out bit by bit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (LocalIsTv.current) Box(modifier, content = content)
    else PullToRefreshBox(refreshing, onRefresh, modifier, content = content)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = 24.dp) {
    Text(
        text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 12.dp),
    )
}

fun formatDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatLongDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

fun pluralSongs(n: Int) = if (n == 1) "1 song" else "$n songs"

fun pluralAlbums(n: Int) = if (n == 1) "1 album" else "$n albums"

/** "N songs", plus "by owner" only when someone else owns it (it's noise for your own). */
fun playlistSubtitle(p: eu.bradshaw.seidr.data.api.Playlist, me: String?) =
    listOfNotNull(p.owner?.takeIf { it != me }?.let { "by $it" }, pluralSongs(p.songCount)).joinToString(" · ")
