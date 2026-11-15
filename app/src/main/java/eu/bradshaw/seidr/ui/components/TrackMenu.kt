package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import eu.bradshaw.seidr.ui.selectToEdit
import eu.bradshaw.seidr.ui.ignoreHeldSelect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route

/** [keepOpen]: the action changes what the menu shows rather than finishing with it. */
data class MenuAction(val label: String, val icon: ImageVector, val keepOpen: Boolean = false, val onClick: () -> Unit)

/** The track overflow menu: add to playlist, queue, go to album/artist, like. */
@Composable
fun TrackMenu(song: Song, expanded: Boolean, onDismiss: () -> Unit, extra: List<MenuAction> = emptyList()) {
    val actions = LocalActions.current
    val starred by actions.graph.browse.starredSongs.collectAsStateWithLifecycle()
    val liked = song.id in starred
    val badOn by actions.graph.bad.enabled.collectAsStateWithLifecycle()
    var reporting by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.ignoreHeldSelect()) {
        if (reporting) {
            ReportItems(song.id, onDismiss)
            return@DropdownMenu
        }
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(220.dp))
        fun item(label: String, icon: ImageVector, block: () -> Unit) = MenuAction(label, icon, onClick = block)
        val readOnly = actions.graph.readOnly // browsing as a kid
        val items = buildList {
            if (!readOnly) add(item(if (liked) "Unlove" else "Love", if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                actions.toggleLike(song.id)
            })
            if (!readOnly) add(item("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { actions.addToPlaylist = listOf(song) })
            add(item("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { actions.queue(listOf(song)) })
            song.albumId?.let { id -> add(item("Go to album", Icons.Filled.Album) { actions.open(Route.Album(id)) }) }
            song.artistId?.let { id -> add(item("Go to artist", Icons.Filled.Person) { actions.open(Route.Artist(id)) }) }
            addAll(extra)
            if (badOn && !readOnly) add(MenuAction("Report a problem", Icons.Outlined.Flag, keepOpen = true) { reporting = true })
        }
        items.forEach { a ->
            DropdownMenuItem(
                text = { Text(a.label) },
                leadingIcon = { Icon(a.icon, null) },
                onClick = { if (!a.keepOpen) onDismiss(); a.onClick() },
            )
        }
    }
}

/** Picker for "Add to playlist": the user's editable playlists plus "New playlist". */
@Composable
fun AddToPlaylistDialog() {
    val actions = LocalActions.current
    val songs = actions.addToPlaylist ?: return
    val graph = actions.graph
    val playlists by graph.browse.playlists.collectAsStateWithLifecycle()
    val server by graph.settings.server.collectAsStateWithLifecycle()
    val me = server?.username
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { graph.browse.refreshPlaylists() } }
    val dismiss = { actions.addToPlaylist = null }

    if (creating) {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.selectToEdit()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    dismiss()
                    actions.launch {
                        val id = graph.editor.create(name.trim(), songs.map { it.id })
                        if (id == null) actions.toast("Couldn't create the playlist (needs the server)")
                        else {
                            actions.toast("Created \"${name.trim()}\"")
                            runCatching { graph.browse.refreshPlaylists() }
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    PickerRow("New playlist", Icons.Filled.Add) { creating = true }
                }
                val editable = playlists.orEmpty().filter { !it.readonly && (me == null || it.owner == null || it.owner == me) }
                items(editable, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            dismiss()
                            actions.launch {
                                val r = graph.editor.add(p.id, songs.map { it.id })
                                actions.report(r, "Added to ${p.name}")
                            }
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(p.coverArt, Modifier.size(40.dp), size = 120)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(40.dp).padding(8.dp))
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
