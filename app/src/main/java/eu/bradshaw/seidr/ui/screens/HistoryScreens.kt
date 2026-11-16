package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.PlaylistHistory
import eu.bradshaw.seidr.data.db.PlaylistVersion
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.primaryFocus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A playlist's ⋮ → Earlier versions: what it was each time this device saw it
 * change, newest first, each with Save a copy. The playlist itself is never
 * touched.
 */
@Composable
fun PlaylistHistoryScreen(playlistId: String) {
    val graph = LocalActions.current.graph
    val all by remember(playlistId) { graph.history.versions(playlistId) }.collectAsStateWithLifecycle(null)
    val versions = all ?: return
    // The newest is how it is now; the rest are what came before.
    val current = versions.firstOrNull()
    // Ones the same as now (it went back to one) have nothing to give back.
    val earlier = versions.drop(1).filter { it.songIds != current?.songIds || it.name != current.name }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.playlist_history))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item { Hint(stringResource(if (earlier.isEmpty()) R.string.playlist_history_none else R.string.playlist_history_hint)) }
            itemsIndexed(earlier, key = { _, v -> v.id }) { i, v ->
                val (gone, added) = PlaylistHistory.diff(v.songs, current?.songs.orEmpty())
                VersionRow(
                    v, title = whenText(v.savedAt),
                    detail = listOfNotNull(
                        v.name.takeIf { it != current?.name },
                        songsText(v.songs.size),
                        if (gone > 0) pluralStringResource(R.plurals.history_removed_since, gone, gone) else null,
                        if (added > 0) pluralStringResource(R.plurals.history_added_since, added, added) else null,
                    ).joinToString(" · "),
                    first = i == 0,
                )
            }
        }
    }
}

/** Settings → Library → Recently deleted playlists, each with Save a copy. */
@Composable
fun DeletedPlaylistsScreen() {
    val graph = LocalActions.current.graph
    val deleted by remember { graph.history.deleted() }.collectAsStateWithLifecycle(null)
    val list = deleted ?: return
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.deleted_playlists))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item { Hint(stringResource(if (list.isEmpty()) R.string.deleted_playlists_none else R.string.deleted_playlists_hint)) }
            itemsIndexed(list, key = { _, v -> v.id }) { i, v ->
                VersionRow(
                    v, title = v.name,
                    detail = listOf(songsText(v.songs.size), stringResource(R.string.deleted_playlists_on, whenText(v.deletedAt ?: v.savedAt)))
                        .joinToString(" · "),
                    first = i == 0,
                    // Back again: no longer deleted, and its history goes with the copy.
                    onSaved = { id -> graph.history.restored(v.playlistId, id) },
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

/** A version: [title] and [detail], and Save a copy, which makes it a new playlist and opens it. */
@Composable
private fun VersionRow(version: PlaylistVersion, title: String, detail: String, first: Boolean, onSaved: suspend (newId: String) -> Unit = {}) {
    val actions = LocalActions.current
    val graph = actions.graph
    val name = stringResource(R.string.history_copy_name, version.name)
    val copied = stringResource(R.string.playlist_copied, version.name)
    val failed = stringResource(R.string.playlist_copy_failed)
    var saving by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            enabled = !saving && version.songs.isNotEmpty(),
            modifier = if (first) Modifier.primaryFocus() else Modifier,
            onClick = {
                saving = true
                actions.launch {
                    val id = graph.editor.create(name, version.songs)
                    saving = false
                    if (id == null) actions.toast(failed)
                    else {
                        onSaved(id)
                        runCatching { graph.browse.refreshPlaylists() }
                        actions.toast(copied)
                        actions.open(Route.Playlist(id))
                    }
                }
            },
        ) { Text(stringResource(R.string.playlist_save_copy)) }
    }
}

private val whenFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun whenText(ms: Long): String = whenFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
