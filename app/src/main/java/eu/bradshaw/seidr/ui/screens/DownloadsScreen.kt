package eu.bradshaw.seidr.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.RowArtShape
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.components.formatBytes
import eu.bradshaw.seidr.ui.components.pluralSongs
import eu.bradshaw.seidr.ui.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Storage, queue status, the download settings, and what's kept on the phone. Same card style as Settings. */
@Composable
fun DownloadsScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val all by graph.downloads.all.collectAsStateWithLifecycle()
    val sources by graph.downloads.sources.collectAsStateWithLifecycle()
    val usage by graph.downloads.usage.collectAsStateWithLifecycle()
    val wifiOnly by graph.settings.wifiOnly.collectAsStateWithLifecycle()
    val sync by graph.sync.status.collectAsStateWithLifecycle()
    val pendingEdits by graph.db.offline().editCount().collectAsStateWithLifecycle(0)
    var free by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { free = withContext(Dispatchers.IO) { graph.files.freeBytes() }; delay(5000) } }

    // A kid sees only approved songs here (anything else is on its way out at the next sync).
    val kid by graph.kids.rules.collectAsStateWithLifecycle()
    val rows = all.values.filter { kid?.playable(it.trackId) != false }
    val done = rows.filter { it.state == DownloadState.DONE }
    val active = rows.filter { it.state == DownloadState.DOWNLOADING }
    val queued = rows.count { it.state == DownloadState.QUEUED }
    val failed = rows.filter { it.state == DownloadState.FAILED }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Downloads")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item {
                val used = done.sumOf { it.size }
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(16.dp),
                ) {
                    Text("${pluralSongs(done.size)} · ${formatBytes(used)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (used + free > 0) (used.toFloat() / (used + free)).coerceIn(0.01f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        trackColor = cs.surfaceContainerHighest, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${formatBytes(free)} free on this phone", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }

            item { CardLabel("Queue") }
            item {
                CardGroup {
                    var first = true
                    @Composable fun gap() { if (!first) CardDivider(); first = false }
                    if (active.isEmpty() && queued == 0 && failed.isEmpty()) {
                        gap()
                        CardItem(Icons.Filled.CheckCircle, "Everything is downloaded", null)
                    }
                    active.forEach { d ->
                        gap()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                            LinearProgressIndicator(
                                progress = { if (d.size > 0) (d.bytesDone.toFloat() / d.size).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), drawStopIndicator = {},
                            )
                        }
                    }
                    if (queued > 0) {
                        gap()
                        CardItem(Icons.Outlined.Schedule, "${pluralSongs(queued)} waiting", if (wifiOnly) "Downloads on Wi-Fi only" else null)
                    }
                    if (failed.isNotEmpty()) {
                        gap()
                        CardItem(
                            Icons.Outlined.ErrorOutline, "${failed.size} failed",
                            failed.mapNotNull { it.error }.distinct().take(2).joinToString("; ").ifEmpty { null },
                            trailing = { TextButton(onClick = { graph.sync.retryFailed() }) { Text("Retry") } },
                        )
                    }
                    if (pendingEdits > 0) {
                        gap()
                        CardItem(Icons.Outlined.Edit, if (pendingEdits == 1) "1 playlist edit waiting" else "$pendingEdits playlist edits waiting",
                            "Sent when the server is reachable")
                    }
                }
            }

            item { CardLabel("Settings") }
            item {
                CardGroup {
                    CardItem(Icons.Outlined.Wifi, "Download using Wi-Fi only", "Turn off to also download on mobile data",
                        trailing = { Switch(wifiOnly, null) }) {
                        graph.settings.setWifiOnly(!wifiOnly); graph.sync.requeueDownloads()
                    }
                    CardDivider()
                    val failedSources = sync.lastReport?.failed.orEmpty()
                    CardItem(
                        Icons.Outlined.Sync,
                        when {
                            sync.running -> "Syncing…"
                            sync.lastRunAt == 0L -> "Not synced yet"
                            else -> "Last synced " + DateUtils.getRelativeTimeSpanString(sync.lastRunAt)
                        },
                        if (failedSources.isNotEmpty()) "${failedSources.size} couldn't be checked; nothing was removed" else null,
                        // Correctness never depends on this; it's just here for impatience.
                        trailing = {
                            FilledTonalButton(onClick = { graph.sync.request(sources.keys) }, enabled = !sync.running,
                                modifier = Modifier.focusRing(CircleShape)) { Text("Sync now") }
                        },
                    )
                }
            }

            item { CardLabel("Downloaded") }
            item {
                CardGroup {
                    if (sources.isEmpty()) {
                        CardItem(null, "Nothing yet", "Switch on Download for a playlist or album to keep it on the phone.")
                    }
                    sources.values.sortedBy { it.name.lowercase() }.forEachIndexed { i, s ->
                        if (i > 0) CardDivider()
                        val u = usage[s.id]
                        val sub = if (u == null) "Waiting" else "${u.done} of ${pluralSongs(u.tracks)} · ${formatBytes(u.bytes)}"
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    when (s.type) {
                                        SourceType.PLAYLIST -> actions.open(Route.Playlist(s.serverId))
                                        SourceType.STARRED -> actions.open(Route.Loved)
                                        else -> actions.open(Route.Album(s.serverId))
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val art = Modifier.size(48.dp).clip(RowArtShape)
                            if (s.type == SourceType.STARRED) LovedArt(art) else Cover(s.coverArt.takeIf { kid == null }, art, size = 160)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (s.type == SourceType.STARRED) "Playlist" else s.type.replaceFirstChar { it.uppercase() }} · $sub", style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(true, { graph.sync.setOffline(s, false) })
                        }
                    }
                }
            }
        }
    }
}
