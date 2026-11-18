package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.data.Star
import eu.bradshaw.seidr.ui.components.StarButton

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import eu.bradshaw.seidr.ui.ArtPage
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.PickedPicture
import eu.bradshaw.seidr.ui.components.PictureCropDialog
import eu.bradshaw.seidr.ui.components.loadPicture
import eu.bradshaw.seidr.ui.components.renderPicture
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.scale
import eu.bradshaw.seidr.ui.Route
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
import eu.bradshaw.seidr.ui.components.SortButton
import eu.bradshaw.seidr.data.TrackSort
import eu.bradshaw.seidr.ui.components.label
import eu.bradshaw.seidr.ui.components.HeaderFacts
import eu.bradshaw.seidr.ui.components.approvedCount
import eu.bradshaw.seidr.ui.components.rememberAllDownloaded
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.MenuAction
import eu.bradshaw.seidr.ui.components.Mosaic
import eu.bradshaw.seidr.ui.components.FullArt
import eu.bradshaw.seidr.ui.components.TrackRow
import eu.bradshaw.seidr.ui.components.dragHandle
import eu.bradshaw.seidr.ui.components.LiftHandle
import eu.bradshaw.seidr.ui.components.formatLongDuration
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.components.rememberReorderState
import eu.bradshaw.seidr.ui.components.reorderable
import kotlinx.coroutines.launch
import eu.bradshaw.seidr.ui.components.reloadOnChanges
import eu.bradshaw.seidr.ui.components.reloaded
import kotlinx.coroutines.Job

class PlaylistVm(private val graph: Graph, val id: String) : ViewModel() {
    var state by mutableStateOf<Load<Playlist>>(Load.Loading)
        private set

    init {
        load()
        // Changes made anywhere (here, another app, another device) must show here.
        viewModelScope.launch {
            graph.browse.playlistEdited.collect { if (it == id) load(quiet = true) }
        }
        // A scan can change its songs' details (and a smart playlist's songs).
        reloadOnChanges(graph) { load(quiet = true) }
        // Opening a playlist is a sync trigger (cheap when nothing changed).
        if (SourceType.sourceId(SourceType.PLAYLIST, id) in graph.downloads.sources.value) {
            graph.sync.request(setOf(SourceType.sourceId(SourceType.PLAYLIST, id)))
        }
    }

    private var loading: Job? = null

    /** [quiet]: a live update, so a failure keeps what's showing. Changes come in bursts; the latest load wins. */
    fun load(quiet: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            if (state !is Load.Ready) state = Load.Loading
            state = state.reloaded(runCatching { graph.browse.playlist(id) }, quiet)
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
    val server by graph.browseLogin.collectAsStateWithLifecycle()
    val canStarPlaylists by graph.browse.canStarPlaylists.collectAsStateWithLifecycle()
    val me = server?.username
    val mine = playlist.owner == null || playlist.owner == me
    val editable = !graph.readOnly && !playlist.readonly && mine
    // Navidrome marks smart playlists read-only: their songs are the rules', which the owner edits.
    val ownSmart = !graph.readOnly && playlist.readonly && mine && !graph.kids.isKid
    val smartIds by graph.smart.ids.collectAsStateWithLifecycle()
    val smart = playlist.readonly && (mine || playlist.id in smartIds)
    // Sharing is the owner's call; anyone else can keep a copy of their own.
    val canShare = !graph.readOnly && mine
    val canCopy = !graph.readOnly && !mine
    val songs = playlist.entry
    // A view-only order (the server's stays as it is), remembered per playlist.
    val sorts by graph.settings.playlistSorts.collectAsStateWithLifecycle()
    val sortDefault by graph.settings.playlistSortDefault.collectAsStateWithLifecycle()
    val sort = graph.settings.playlistSort(playlist.id, sorts, sortDefault)
    val shown = remember(songs, sort) { sort.sort(songs) }
    val shownSongs = remember(shown) { shown.map { it.value } }

    var editing by remember { mutableStateOf(false) }
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

    val orderSaved = stringResource(R.string.playlist_order_saved)
    val removedFrom = stringResource(R.string.playlist_removed_from, playlist.name)
    val removeLabel = stringResource(R.string.playlist_remove_from_this)
    val renamed = stringResource(R.string.playlist_renamed)
    val deleted = stringResource(R.string.playlist_deleted, playlist.name)
    val sharedNow = stringResource(R.string.playlist_shared_now)
    val unsharedNow = stringResource(R.string.playlist_unshared_now)
    val copied = stringResource(R.string.playlist_copied, playlist.name)
    val putBack = stringResource(R.string.playlist_put_back, playlist.name)
    val restored = stringResource(R.string.playlist_restored, playlist.name)
    val restoreFailed = stringResource(R.string.playlist_restore_failed)
    // The songs as the server has them (a kid's login sees fewer), for undoing a remove or delete.
    val serverSongs = { graph.library.playlistSongs.value[playlist.id] ?: songs.map { it.id } }
    val copyFailed = stringResource(R.string.playlist_copy_failed)
    val pictureChanged = stringResource(R.string.playlist_picture_changed)
    val pictureRemoved = stringResource(R.string.playlist_picture_removed)

    // A picture of its own shows as it is; otherwise the classic mosaic of its albums.
    val pictured by graph.playlistPictures.ids.collectAsStateWithLifecycle()
    val picture = playlist.coverArt?.takeIf { playlist.id in pictured }
    // Pictures are Navidrome's (0.61+), and need a photo picker (not on TV). The owner's
    // call, smart playlists included: their songs are the rules', but the picture isn't.
    val picturesOn by graph.playlistPictures.supported.collectAsStateWithLifecycle()
    val canPicture = !graph.readOnly && mine && picturesOn && !LocalIsTv.current
    suspend fun editPicture(applied: String, edit: suspend () -> Unit) {
        try {
            edit()
            actions.toast(applied)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            actions.toast(graph.app.getString(R.string.common_edit_failed, e.message ?: e.javaClass.simpleName))
        }
        vm.load()
    }
    // Picked, then framed (PictureCropDialog), then uploaded.
    var framing by remember { mutableStateOf<PickedPicture?>(null) }
    val notPicture = stringResource(R.string.playlist_picture_unreadable)
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.launch {
            framing = runCatching { loadPicture(graph.app, uri) }.getOrElse { actions.toast(notPicture); null }
        }
    }
    framing?.let { picked ->
        PictureCropDialog(
            picked,
            onSave = { frame ->
                framing = null
                actions.launch { editPicture(pictureChanged) { graph.playlistPictures.set(playlist.id, renderPicture(picked, frame)) } }
            },
            onDismiss = { framing = null },
        )
    }

    val source = OfflineSource(
        SourceType.sourceId(SourceType.PLAYLIST, playlist.id), SourceType.PLAYLIST, playlist.id, playlist.name,
        coverArt = playlist.coverArt,
    )

    // A picture of its own colours the page, as a cover does an album's; the mosaic keeps the theme.
    ArtPage(picture) { CollectionLayout(
        title = playlist.name,
        art = { m ->
            if (picture != null) Cover(picture, m.aspectRatio(1f), size = 600)
            else Mosaic(songs.distinctBy { it.albumId }.mapNotNull { it.coverArt }, m, fallback = playlist.coverArt)
        },
        fullArt = if (picture != null) FullArt(picture)
            else FullArt(playlist.coverArt, songs.distinctBy { it.albumId }.mapNotNull { it.coverArt }),
        subtitle = {
            HeaderFacts(
                listOfNotNull(
                    playlist.owner?.takeUnless { mine }?.let { stringResource(R.string.playlist_by_owner, it) },
                    if (smart) stringResource(R.string.smart_badge) else null,
                    if (mine && playlist.public) stringResource(R.string.playlist_shared) else null,
                    songsText(playlist.songCount),
                    approvedCount(songs),
                    formatLongDuration(playlist.duration),
                ).joinToString(" · "),
                downloaded = rememberAllDownloaded(remember(songs) { songs.map { it.id } }),
            )
        },
        onShuffle = { graph.playlistPlays.record(playlist.id); actions.player.shufflePlay(shownSongs, playlist.name, playlist.id, picture) },
        shuffleEnabled = songs.any { !it.locked } && !editing,
        onPlay = { graph.playlistPlays.record(playlist.id); actions.player.playContext(shownSongs, 0, playlist.name, shuffle = false, playlistId = playlist.id, playlistArt = picture) },
        shufflePrimary = true,
        offline = source,
        trackCount = songs.size,
        listState = listState,
        topActions = {
            if (!editing && canStarPlaylists) StarButton(Star.OfPlaylist(playlist.id, null))
            if (editing) {
                IconButton(onClick = {
                    editing = false
                    val desired = order.map { it.second.id }
                    val seen = songs.map { it.id }
                    if (desired != seen) actions.launch {
                        actions.report(graph.editor.reorder(playlist.id, seen, desired), orderSaved)
                        vm.load()
                    }
                }) { Icon(Icons.Filled.Check, stringResource(R.string.playlist_done)) }
            }
        },
        showMenu = !editing,
        // After Download (the layout's): the switches, then the edits, then Delete.
        menu = if (!(editable || ownSmart || canShare || canCopy)) null else { close ->
            if (canShare) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_share)) }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                    trailingIcon = { Switch(playlist.public, null, Modifier.scale(0.8f)) },
                    onClick = {
                        close()
                        val share = !playlist.public
                        actions.launch {
                            actions.report(graph.editor.setShared(playlist.id, share), if (share) sharedNow else unsharedNow)
                            vm.load()
                            runCatching { graph.browse.refreshPlaylists() }
                        }
                    })
            }
            if (ownSmart) {
                DropdownMenuItem({ Text(stringResource(R.string.smart_edit_rules)) }, leadingIcon = { Icon(Icons.Filled.Tune, null) },
                    onClick = { close(); actions.open(Route.SmartRules(playlist.id)) })
            }
            if (editable || ownSmart) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { close(); renaming = true })
            }
            if (editable) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_reorder)) }, leadingIcon = { Icon(Icons.Filled.DragHandle, null) },
                    // Reordering is of the playlist's own order, so the view goes back to it.
                    onClick = { close(); graph.settings.setPlaylistSort(playlist.id, TrackSort.CUSTOM); editing = true })
            }
            if (canPicture) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_picture_change)) }, leadingIcon = { Icon(Icons.Filled.Image, null) },
                    onClick = { close(); pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                if (picture != null) {
                    DropdownMenuItem({ Text(stringResource(R.string.playlist_picture_remove)) }, leadingIcon = { Icon(Icons.Filled.HideImage, null) },
                        onClick = { close(); actions.launch { editPicture(pictureRemoved) { graph.playlistPictures.remove(playlist.id) } } })
                }
            }
            if (canCopy) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_save_copy)) }, leadingIcon = { Icon(Icons.Filled.LibraryAdd, null) },
                    onClick = {
                        close()
                        actions.launch {
                            val id = graph.editor.copy(playlist.id)
                            if (id == null) actions.toast(copyFailed)
                            else {
                                runCatching { graph.browse.refreshPlaylists() }
                                actions.toast(copied)
                                actions.open(Route.Playlist(id))
                            }
                        }
                    })
            }
            if (editable || ownSmart) {
                DropdownMenuItem({ Text(stringResource(R.string.playlist_history)) }, leadingIcon = { Icon(Icons.Filled.History, null) },
                    onClick = { close(); actions.open(Route.PlaylistHistory(playlist.id)) })
                DropdownMenuItem({ Text(stringResource(R.string.playlist_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { close(); confirmDelete = true })
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
            if (songs.size > 1) item(key = "sort") {
                SortButton(TrackSort.entries, sort, TrackSort::label, { graph.settings.setPlaylistSort(playlist.id, it) },
                    Modifier.padding(horizontal = 4.dp))
            }
            // Keyed by the playlist position, which a remove goes by too (whatever the view's order).
            itemsIndexed(shown, key = { _, (at, s) -> "$at:${s.id}" }) { i, (at, song) ->
                TrackRow(
                    song,
                    onClick = { graph.playlistPlays.record(playlist.id); actions.player.playContext(shownSongs, i, playlist.name, playlistId = playlist.id, playlistArt = picture) },
                    playing = ui.current?.songId == song.id,
                    extraMenu = if (editable) listOf(MenuAction(removeLabel, Icons.Filled.RemoveCircleOutline) {
                        actions.launch {
                            val before = serverSongs()
                            actions.report(graph.editor.remove(playlist.id, song.id, at), removedFrom) {
                                actions.report(graph.editor.undoRemove(playlist.id, song.id, before), putBack)
                                vm.load()
                            }
                            vm.load()
                        }
                    }) else emptyList(),
                )
            }
        }
    } }

    if (renaming) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.playlist_rename_title)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.selectToEdit()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    actions.launch {
                        actions.report(graph.editor.rename(playlist.id, name.trim()), renamed)
                        vm.load()
                        runCatching { graph.browse.refreshPlaylists() }
                    }
                }) { Text(stringResource(R.string.playlist_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete_title, playlist.name)) },
            text = { Text(stringResource(R.string.playlist_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.launch {
                        val name = playlist.name
                        val ids = serverSongs()
                        val shared = playlist.public
                        // A smart playlist comes back from its rules (and they go into its history).
                        val rules = if (playlist.readonly) runCatching { graph.smart.load(playlist.id).rules }.getOrNull() else null
                        val r = graph.editor.delete(playlist.id)
                        actions.report(r, deleted) {
                            val id = if (rules != null) {
                                graph.smart.create(name, rules)?.also { id ->
                                    if (shared) graph.editor.setShared(id, true)
                                }
                            } else graph.editor.undoDelete(name, ids, shared)
                            if (id == null) actions.toast(restoreFailed)
                            else {
                                graph.history.restored(playlist.id, id)
                                runCatching { graph.browse.refreshPlaylists() }
                                actions.toast(restored)
                                actions.open(Route.Playlist(id))
                            }
                        }
                        if (r == eu.bradshaw.seidr.sync.EditResult.Applied) {
                            graph.history.deleted(playlist.id)
                            runCatching { graph.browse.refreshPlaylists() }
                            actions.nav.pop()
                        }
                    }
                }) { Text(stringResource(R.string.playlist_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
}

