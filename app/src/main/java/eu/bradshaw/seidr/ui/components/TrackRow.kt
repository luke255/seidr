package eu.bradshaw.seidr.ui.components

import androidx.compose.ui.draw.clip
import eu.bradshaw.seidr.ui.highlightClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.bradshaw.seidr.ui.rememberedFocus
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.ui.LocalActions

/** Download state of one track, for its indicator. */
enum class TrackOffline { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

@Composable
fun rememberTrackOffline(songId: String): TrackOffline {
    val graph = LocalActions.current.graph
    val all by graph.downloads.all.collectAsStateWithLifecycle()
    return when (all[songId]?.state) {
        DownloadState.DONE -> TrackOffline.DONE
        DownloadState.DOWNLOADING -> TrackOffline.DOWNLOADING
        DownloadState.QUEUED -> TrackOffline.QUEUED
        DownloadState.FAILED -> TrackOffline.FAILED
        else -> TrackOffline.NONE
    }
}

/** True when every one of [songIds] is on the phone (whoever asked for it). */
@Composable
fun rememberAllDownloaded(songIds: Collection<String>): Boolean {
    val all by LocalActions.current.graph.downloads.all.collectAsStateWithLifecycle()
    return songIds.isNotEmpty() && songIds.all { all[it]?.state == DownloadState.DONE }
}

@Composable
fun OfflineIndicator(state: TrackOffline) {
    val tint = MaterialTheme.colorScheme.primary
    when (state) {
        TrackOffline.DONE -> Icon(Icons.Filled.ArrowCircleDown, "Downloaded", tint = tint, modifier = Modifier.size(16.dp))
        TrackOffline.DOWNLOADING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        TrackOffline.QUEUED -> Icon(Icons.Filled.Downloading, "Queued", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        TrackOffline.FAILED -> Icon(Icons.Filled.ErrorOutline, "Download failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        TrackOffline.NONE -> {}
    }
}

/**
 * A song row: title, artist line + offline indicator, overflow menu. Swipe
 * right to add it to the queue. Greyed out (but still visible) when offline
 * and not downloaded. A kid's unapproved song ([Song.locked]) is greyed out
 * with a lock, and can't be played, queued or opened.
 */
@Composable
fun TrackRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = { Cover(song.coverArt, Modifier.size(48.dp), size = 120) },
    subtitle: String = listOfNotNull(song.artistLine.takeIf { it.isNotBlank() }, song.album).joinToString(" · "),
    playing: Boolean = false,
    extraMenu: List<MenuAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
    swipeToQueue: Boolean = true,
) {
    val actions = LocalActions.current
    val online by actions.graph.status.online.collectAsStateWithLifecycle()
    val offline = rememberTrackOffline(song.id)
    val locked = song.locked
    val available = !locked && (online || offline == TrackOffline.DONE)
    var menu by remember { mutableStateOf(false) }

    // The menu button sits beside the clickable part rather than inside it: a
    // remote's arrow keys can't move into a focusable nested in another.
    val row: @Composable () -> Unit = {
        Row(
            modifier.fillMaxWidth().alpha(if (available) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .defaultMinSize(minHeight = 64.dp)
                .rememberedFocus()
                .highlightClickable(enabled = available, onClick = onClick)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (leading != null) {
                Box(Modifier.clip(eu.bradshaw.seidr.ui.RowArtShape)) { leading() }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (offline != TrackOffline.NONE) {
                        Spacer(Modifier.width(4.dp))
                        OfflineIndicator(offline)
                    }
                }
            }
            trailing?.invoke()
          }
            if (locked) {
                Box(Modifier.padding(end = 8.dp).size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, "Not approved yet", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            } else Box(Modifier.padding(end = 8.dp)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                TrackMenu(song, expanded = menu, onDismiss = { menu = false }, extra = extraMenu)
            }
        }
    }

    if (!swipeToQueue || locked) { row(); return }
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            actions.queue(listOf(song))
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            // Only while swiping: the row itself is transparent, so an always-on icon shows through.
            if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Box(Modifier.fillMaxSize().padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { row() }
}

@Composable
fun TrackNumber(n: Int?, playing: Boolean) {
    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
        Text(
            n?.toString() ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
