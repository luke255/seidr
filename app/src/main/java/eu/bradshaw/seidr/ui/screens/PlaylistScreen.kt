package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import eu.bradshaw.seidr.ui.selectToEdit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.CollectionLayout
import eu.bradshaw.seidr.ui.components.HeaderFacts
import eu.bradshaw.seidr.ui.components.approvedCount
import eu.bradshaw.seidr.ui.components.rememberAllDownloaded
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.MenuAction
import eu.bradshaw.seidr.ui.components.Mosaic
import eu.bradshaw.seidr.ui.components.TrackRow
import eu.bradshaw.seidr.ui.components.dragHandle
import eu.bradshaw.seidr.ui.components.LiftHandle
import eu.bradshaw.seidr.ui.components.formatLongDuration
import eu.bradshaw.seidr.ui.components.pluralSongs
import eu.bradshaw.seidr.ui.components.rememberReorderState
import eu.bradshaw.seidr.ui.components.reorderable
import kotlinx.coroutines.launch

class PlaylistVm(private val graph: Graph, val id: String) : ViewModel() {
    var state by mutableStateOf<Load<Playlist>>(Load.Loading)
        private set

    init {
        load()
        // Edits made elsewhere in the app (add to playlist from search, say) must show here.
        viewModelScope.launch {
            graph.library.playlistEdited.collect { if (it == id) load() }
        }
        // Opening a playlist is a sync trigger (cheap when nothing changed).
        if (SourceType.sourceId(SourceType.PLAYLIST, id) in graph.downloads.sources.value) {
            graph.sync.request(setOf(SourceType.sourceId(SourceType.PLAYLIST, id)))
        }
    }

    fun load() {
        viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading
            state = try { Load.Ready(graph.library.playlist(id)) } catch (e: Exception) { Load.Error(e.message ?: "Error") }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "playlist:$id") { PlaylistVm(graph, id) }
    LoadContent(vm.state, vm::load) { playlist -> PlaylistContent(playlist, vm) }
}

@Composable
private fun PlaylistContent(playlist: Playlist, vm: PlaylistVm) {
    val actions = LocalActions.current
    val graph = actions.graph
    val ui by graph.player.ui.collectAsStateWithLifecycle()
    val server by graph.settings.server.collectAsStateWithLifecycle()
    val me = server?.username
    val editable = !playlist.readonly && (playlist.owner == null || playlist.owner == me)
    val songs = playlist.entry

    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Local copy for drag-reordering; keyed by position so duplicates are fine.
    val order = remember(playlist) { mutableStateListOf<Pair<Int, Song>>().apply { addAll(songs.withIndex().map { it.index to it.value }) } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            val f = order.indexOfFirst { it.first == from }
            val t = order.indexOfFirst { it.first == to }
            if (f >= 0 && t >= 0) order.add(t, order.removeAt(f))
        },
        canMoveTo = { _, to -> to is Int },
        onDragEnd = {},
    )

    val source = OfflineSource(
        SourceType.sourceId(SourceType.PLAYLIST, playlist.id), SourceType.PLAYLIST, playlist.id, playlist.name,
        coverArt = playlist.coverArt,
    )

    CollectionLayout(
        title = playlist.name,
        washCover = playlist.coverArt,
        art = { m -> Mosaic(songs.distinctBy { it.albumId }.mapNotNull { it.coverArt }, m, fallback = playlist.coverArt) },
        subtitle = {
            HeaderFacts(
                listOfNotNull(
                    playlist.owner?.let { "by $it" },
                    pluralSongs(playlist.songCount),
                    approvedCount(songs),
                    formatLongDuration(playlist.duration),
                ).joinToString(" · "),
                downloaded = rememberAllDownloaded(remember(songs) { songs.map { it.id } }),
            )
        },
        onShuffle = { graph.playlistPlays.record(playlist.id); actions.player.shufflePlay(songs, playlist.name) },
        shuffleEnabled = songs.any { !it.locked } && !editing,
        onPlay = { graph.playlistPlays.record(playlist.id); actions.player.playContext(songs, 0, playlist.name, shuffle = false) },
        shufflePrimary = true,
        offline = source,
        trackCount = songs.size,
        listState = listState,
        topActions = {
            if (editing) {
                IconButton(onClick = {
                    editing = false
                    val desired = order.map { it.second.id }
                    val seen = songs.map { it.id }
                    if (desired != seen) actions.launch {
                        actions.report(graph.editor.reorder(playlist.id, seen, desired), "Playlist order saved")
                        vm.load()
                    }
                }) { Icon(Icons.Filled.Check, "Done") }
            } else if (editable) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Playlist options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Reorder songs") }, leadingIcon = { Icon(Icons.Filled.DragHandle, null) },
                            onClick = { menu = false; editing = true })
                        DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true })
                        DropdownMenuItem({ Text("Delete playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        },
    ) {
        if (editing) {
            itemsIndexed(order, key = { _, it -> it.first }) { _, (key, song) ->
                // No placement animation on the dragged row: it already follows the finger.
                val anim = if (reorder.draggingKey == key) Modifier else Modifier.animateItem()
                Row(Modifier.fillMaxWidth().reorderable(reorder, key).then(anim), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TrackRow(song, onClick = {}, swipeToQueue = false)
                    }
                    LiftHandle(reorder.draggingKey == key, Modifier.padding(end = 8.dp).dragHandle(reorder, key))
                }
            }
        } else {
            itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { i, song ->
                TrackRow(
                    song,
                    onClick = { graph.playlistPlays.record(playlist.id); actions.player.playContext(songs, i, playlist.name) },
                    playing = ui.current?.songId == song.id,
                    extraMenu = if (editable) listOf(MenuAction("Remove from this playlist", Icons.Filled.RemoveCircleOutline) {
                        actions.launch {
                            actions.report(graph.editor.remove(playlist.id, song.id, i), "Removed from ${playlist.name}")
                            vm.load()
                        }
                    }) else emptyList(),
                )
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.selectToEdit()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    actions.launch {
                        actions.report(graph.editor.rename(playlist.id, name.trim()), "Renamed")
                        vm.load()
                        runCatching { graph.library.refreshPlaylists() }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("This deletes it on the server. Downloads only it was keeping are removed from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.launch {
                        val r = graph.editor.delete(playlist.id)
                        actions.report(r, "Deleted ${playlist.name}")
                        if (r == eu.bradshaw.seidr.sync.EditResult.Applied) {
                            runCatching { graph.library.refreshPlaylists() }
                            actions.nav.pop()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
