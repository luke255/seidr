package eu.bradshaw.seidr.ui.screens

import android.text.format.DateUtils
import eu.bradshaw.seidr.data.nameOrder
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
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
    val batch by graph.downloads.batch.collectAsStateWithLifecycle()
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
        BackTopBar(stringResource(R.string.downloads_title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item {
                val used = done.sumOf { it.size }
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(16.dp),
                ) {
                    Text(stringResource(R.string.downloads_summary, songsText(done.size), formatBytes(used)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (used + free > 0) (used.toFloat() / (used + free)).coerceIn(0.01f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        trackColor = cs.surfaceContainerHighest, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.downloads_free, formatBytes(free)), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }

            item { CardLabel(stringResource(R.string.downloads_queue)) }
            item {
                CardGroup {
                    var first = true
                    @Composable fun gap() { if (!first) CardDivider(); first = false }
                    if (batch == null && failed.isEmpty()) {
                        gap()
                        CardItem(Icons.Filled.CheckCircle, stringResource(R.string.downloads_all_done), null)
                    }
                    // The file being fetched, with the whole batch's progress: files come and go
                    // in about a second each, too quick for a bar of their own.
                    // Between files there's a moment with none fetching: keep showing the last.
                    val lastFile = remember { arrayOfNulls<eu.bradshaw.seidr.data.db.Download>(1) }
                    active.firstOrNull()?.let { lastFile[0] = it }
                    val d = lastFile[0]
                    if (d != null && batch != null) {
                        gap()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                            val progress by animateFloatAsState(batch!!.fraction, label = "downloaded")
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), drawStopIndicator = {},
                            )
                        }
                    }
                    if (queued > 0) {
                        gap()
                        CardItem(Icons.Outlined.Schedule, pluralStringResource(R.plurals.downloads_waiting, queued, queued),
                            if (wifiOnly) stringResource(R.string.downloads_wifi_only_note) else null)
                    }
                    if (failed.isNotEmpty()) {
                        gap()
                        CardItem(
                            Icons.Outlined.ErrorOutline, pluralStringResource(R.plurals.downloads_failed, failed.size, failed.size),
                            failed.mapNotNull { it.error }.distinct().take(2).joinToString("; ").ifEmpty { null },
                            trailing = { TextButton(onClick = { graph.sync.retryFailed() }) { Text(stringResource(R.string.downloads_retry)) } },
                        )
                    }
                    if (pendingEdits > 0) {
                        gap()
                        CardItem(Icons.Outlined.Edit, pluralStringResource(R.plurals.downloads_edits_waiting, pendingEdits, pendingEdits),
                            stringResource(R.string.downloads_edits_note))
                    }
                }
            }

            item { CardLabel(stringResource(R.string.downloads_settings)) }
            item {
                CardGroup {
                    CardItem(Icons.Outlined.Wifi, stringResource(R.string.downloads_wifi_only), stringResource(R.string.downloads_wifi_only_hint),
                        trailing = { Switch(wifiOnly, null) }) {
                        graph.settings.setWifiOnly(!wifiOnly); graph.sync.requeueDownloads()
                    }
                    CardDivider()
                    val failedSources = sync.lastReport?.failed.orEmpty()
                    CardItem(
                        Icons.Outlined.Sync,
                        when {
                            sync.running -> stringResource(R.string.downloads_syncing)
                            sync.lastRunAt == 0L -> stringResource(R.string.downloads_not_synced)
                            else -> stringResource(R.string.downloads_last_synced, DateUtils.getRelativeTimeSpanString(sync.lastRunAt).toString())
                        },
                        if (failedSources.isNotEmpty()) pluralStringResource(R.plurals.downloads_unchecked, failedSources.size, failedSources.size) else null,
                        // Correctness never depends on this; it's just here for impatience.
                        trailing = {
                            FilledTonalButton(onClick = { graph.sync.request(sources.keys) }, enabled = !sync.running,
                                modifier = Modifier.focusRing(CircleShape)) { Text(stringResource(R.string.downloads_sync_now)) }
                        },
                    )
                }
            }

            item { CardLabel(stringResource(R.string.downloads_downloaded)) }
            item {
                CardGroup {
                    if (sources.isEmpty()) {
                        CardItem(null, stringResource(R.string.downloads_nothing), stringResource(R.string.downloads_nothing_hint))
                    }
                    sources.values.sortedWith(nameOrder { it.name }).forEachIndexed { i, s ->
                        if (i > 0) CardDivider()
                        val u = usage[s.id]
                        val sub = if (u == null) stringResource(R.string.downloads_waiting_short)
                            else pluralStringResource(R.plurals.downloads_progress, u.tracks, u.done, u.tracks, formatBytes(u.bytes))
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
                                val kind = when (s.type) {
                                    SourceType.STARRED, SourceType.PLAYLIST -> stringResource(R.string.downloads_kind_playlist)
                                    SourceType.ALBUM -> stringResource(R.string.downloads_kind_album)
                                    else -> s.type.replaceFirstChar { it.uppercase() }
                                }
                                Text(stringResource(R.string.downloads_kind_line, kind, sub), style = MaterialTheme.typography.bodyMedium,
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
