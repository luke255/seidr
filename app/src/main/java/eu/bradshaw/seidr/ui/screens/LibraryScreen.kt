package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.ArtistImage
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.ListRow
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.RefreshBox
import eu.bradshaw.seidr.ui.components.TabHeader
import eu.bradshaw.seidr.ui.components.LocalWideShell
import eu.bradshaw.seidr.ui.components.SettingsButton
import eu.bradshaw.seidr.ui.components.SearchField
import eu.bradshaw.seidr.ui.components.FilterPill
import eu.bradshaw.seidr.ui.highlightClickable
import eu.bradshaw.seidr.ui.CardPadding
import eu.bradshaw.seidr.ui.CardArtShape
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.components.pluralAlbums
import eu.bradshaw.seidr.ui.components.pluralSongs
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.rememberedFocus
import eu.bradshaw.seidr.ui.selectToEdit
import kotlinx.coroutines.launch

class LibraryVm(private val graph: Graph) : ViewModel() {
    var artists by mutableStateOf<Load<List<Artist>>>(Load.Loading)
        private set
    var albums by mutableStateOf<Load<List<Album>>>(Load.Loading)
        private set
    var playlistsError by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init { load(false) }

    fun load(refresh: Boolean) {
        viewModelScope.launch {
            refreshing = refresh
            launch {
                playlistsError = try { graph.library.refreshPlaylists(); null } catch (e: Exception) { e.message ?: "Error" }
            }
            launch {
                artists = try { Load.Ready(graph.library.artists(refresh)) } catch (e: Exception) { Load.Error(e.message ?: "Error") }
            }
            albums = try { Load.Ready(graph.library.allAlbums(refresh)) } catch (e: Exception) { Load.Error(e.message ?: "Error") }
            refreshing = false
        }
    }
}

private enum class LibTab(val label: String) { PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums") }

/** One thing in the library, as either a list row or a grid cell. */
private class LibItem(
    val key: String,
    val title: String,
    val subtitle: String,
    /** Shorter, for the grid's narrow cells. */
    val gridSubtitle: String = subtitle,
    val round: Boolean = false,
    val badge: Badge? = null,
    val art: @Composable (Modifier) -> Unit,
    val onClick: () -> Unit,
)

private enum class Badge { DOWNLOADED }

@Composable
fun LibraryScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "library") { LibraryVm(graph) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid by graph.settings.libraryGrid.collectAsStateWithLifecycle()

    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    val sources by graph.downloads.sources.collectAsStateWithLifecycle()
    val downloads by graph.downloads.all.collectAsStateWithLifecycle()
    val playlistSongs by graph.library.playlistSongs.collectAsStateWithLifecycle()
    val server by graph.settings.server.collectAsStateWithLifecycle()
    val starred by graph.library.starredSongs.collectAsStateWithLifecycle()
    val lastLoved by graph.library.lastLoved.collectAsStateWithLifecycle()
    // A playlist counts as downloaded if it's a download source, or every track is on the phone anyway.
    val offlinePlaylists = remember(sources, downloads, playlistSongs) {
        val done = downloads.filterValues { it.state == DownloadState.DONE }.keys
        val covered = playlistSongs.filter { (_, ids) -> ids.isNotEmpty() && done.containsAll(ids) }.keys
        val prefix = SourceType.sourceId(SourceType.PLAYLIST, "")
        covered + sources.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }
    val lovedOffline = remember(sources, downloads, starred) {
        SourceType.STARRED_ID in sources ||
            (starred.isNotEmpty() && starred.all { downloads[it]?.state == DownloadState.DONE })
    }

    val sortOptions = when (LibTab.entries[tab]) {
        LibTab.PLAYLISTS -> listOf("Custom order", "Name", "Recently updated")
        LibTab.ARTISTS -> listOf("Name", "Most albums")
        LibTab.ALBUMS -> listOf("Name", "Artist", "Recently added", "Year")
    }
    if (sort >= sortOptions.size) sort = 0
    val q = query.trim()
    fun matches(vararg s: String?) = q.isEmpty() || s.any { it?.contains(q, ignoreCase = true) == true }

    val tv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            val field = remember { FocusRequester() }
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                SearchField(
                    query, { query = it }, "Search ${LibTab.entries[tab].label.lowercase()}",
                    modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(field),
                )
            }
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        } else {
            TabHeader("Library") {
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search your library") }
                IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Create playlist") }
                if (!LocalWideShell.current) SettingsButton()
            }
        }

        // One row of chips: what to show, then (phones) only what's downloaded.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibTab.entries.forEachIndexed { i, t -> FilterPill(t.label, tab == i, { tab = i }) }
            if (!tv) {
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                FilterPill("Downloaded", downloadedOnly, { downloadedOnly = !downloadedOnly }, icon = {
                    Icon(Icons.Filled.ArrowCircleDown, null, Modifier.size(16.dp),
                        tint = if (downloadedOnly) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                })
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(" " + sortOptions[sort], color = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    sortOptions.forEachIndexed { i, s ->
                        DropdownMenuItem({ Text(s) }, onClick = { sort = i; sortMenu = false },
                            trailingIcon = if (i == sort) { { Icon(Icons.Filled.Check, null) } } else null)
                    }
                }
            }
            IconButton(onClick = { graph.settings.setLibraryGrid(!grid) }) {
                if (grid) Icon(Icons.AutoMirrored.Filled.ViewList, "Show as list") else Icon(Icons.Filled.GridView, "Show as grid")
            }
        }

        if (!tv) DownloadStrip()

        RefreshBox(refreshing = vm.refreshing, onRefresh = { vm.load(true); graph.sync.request() }, modifier = Modifier.weight(1f)) {
            when (LibTab.entries[tab]) {
                LibTab.PLAYLISTS -> {
                    val list = playlists
                    if (list == null) {
                        if (vm.playlistsError != null) LoadContent(Load.Error(vm.playlistsError!!), { vm.load(true) }) {}
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val shown = list.filter { (!downloadedOnly || it.id in offlinePlaylists) && matches(it.name) }
                            .let { l ->
                                when (sort) {
                                    1 -> l.sortedBy { it.name.lowercase() }
                                    2 -> l.sortedByDescending { it.changed ?: "" }
                                    else -> l
                                }
                            }
                        val items = shown.mapTo(mutableListOf()) { p ->
                            val owner = p.owner?.takeIf { it != server?.username }?.let { "by $it" }
                            LibItem(
                                p.id, p.name, listOfNotNull(owner, pluralSongs(p.songCount)).joinToString(" · "),
                                gridSubtitle = owner ?: pluralSongs(p.songCount),
                                badge = if (p.id in offlinePlaylists) Badge.DOWNLOADED else null,
                                art = { m -> Cover(p.coverArt, m, size = 300) }, onClick = { actions.open(Route.Playlist(p.id)) },
                            )
                        }
                        // Loved heads the list, except by "Recently updated", where its newest love places it.
                        if ((!downloadedOnly || lovedOffline) && matches(Loved.NAME)) {
                            val at = if (sort != 2) 0 else shown.indexOfFirst { (it.changed ?: "") < (lastLoved ?: "") }
                                .let { if (it < 0) shown.size else it }
                            val count = Loved.subtitle(starred)
                            items.add(at, LibItem(
                                "loved", Loved.NAME, count ?: "Playlist",
                                badge = if (lovedOffline) Badge.DOWNLOADED else null,
                                art = { m -> LovedArt(m) }, onClick = { actions.open(Route.Loved) },
                            ))
                        }
                        LibContent(items, grid, empty = emptyText(q, downloadedOnly))
                    }
                }
                LibTab.ARTISTS -> LoadContent(vm.artists, { vm.load(true) }) { all ->
                    val withDownloads = if (downloadedOnly) downloads.values.mapNotNull { it.artistId }.toSet() else emptySet()
                    val items = all.filter { (!downloadedOnly || it.id in withDownloads) && matches(it.name) }
                        .let { l -> if (sort == 1) l.sortedByDescending { it.albumCount } else l.sortedBy { it.name.lowercase() } }
                        .map { a ->
                            LibItem(a.id, a.name, pluralAlbums(a.albumCount),
                                round = true, art = { m -> ArtistImage(a.coverArt, m) }, onClick = { actions.open(Route.Artist(a.id)) })
                        }
                    LibContent(items, grid, empty = emptyText(q, downloadedOnly))
                }
                LibTab.ALBUMS -> LoadContent(vm.albums, { vm.load(true) }) { all ->
                    val items = all
                        .filter { (!downloadedOnly || SourceType.sourceId(SourceType.ALBUM, it.id) in sources) && matches(it.name, it.artistLine) }
                        .let { l ->
                            when (sort) {
                                1 -> l.sortedWith(compareBy({ it.artistLine.lowercase() }, { it.year ?: 0 }))
                                2 -> l.sortedByDescending { it.created ?: "" }
                                3 -> l.sortedByDescending { it.year ?: 0 }
                                else -> l
                            }
                        }
                        .map { a ->
                            LibItem(a.id, a.name, listOfNotNull(a.artistLine.takeIf { it.isNotBlank() }, a.year?.toString()).joinToString(" · "),
                                gridSubtitle = a.artistLine,
                                badge = if (SourceType.sourceId(SourceType.ALBUM, a.id) in sources) Badge.DOWNLOADED else null,
                                art = { m -> Cover(a.coverArt, m, size = 300) }, onClick = { actions.open(Route.Album(a.id)) })
                        }
                    LibContent(items, grid, empty = emptyText(q, downloadedOnly))
                }
            }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Create playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.selectToEdit()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    creating = false
                    actions.launch {
                        val id = graph.editor.create(name.trim(), emptyList())
                        if (id == null) actions.toast("Couldn't create the playlist (needs the server)")
                        else {
                            runCatching { graph.library.refreshPlaylists() }
                            actions.open(Route.Playlist(id))
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

private fun emptyText(query: String, downloadedOnly: Boolean) = when {
    query.isNotEmpty() -> "Nothing matches \"$query\""
    downloadedOnly -> "Nothing downloaded here yet"
    else -> "Nothing here yet"
}

@Composable
private fun LibContent(items: List<LibItem>, grid: Boolean, empty: String) {
    if (items.isEmpty()) {
        Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp))
        return
    }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(128.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = bottomContentPadding().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.key }) { GridCell(it) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(8.dp)) {
            items(items, key = { it.key }) { item ->
                ListRow(
                    item.title, item.subtitle, onClick = item.onClick, artSize = 64.dp,
                    art = item.art,
                    badge = item.badge?.let { b -> { BadgeIcon(b) } },
                )
            }
        }
    }
}

@Composable
private fun GridCell(item: LibItem) {
    Column(
        Modifier.rememberedFocus().highlightClickable(onClick = item.onClick).padding(CardPadding),
        horizontalAlignment = if (item.round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        item.art(Modifier.fillMaxWidth().aspectRatio(1f).clip(if (item.round) CircleShape else CardArtShape))
        Spacer(Modifier.height(6.dp))
        val align = if (item.round) TextAlign.Center else TextAlign.Start
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, textAlign = align, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (item.round) Arrangement.Center else Arrangement.Start,
        ) {
            Text(item.gridSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.weight(1f, fill = false))
            item.badge?.let { Spacer(Modifier.width(4.dp)); BadgeIcon(it) }
        }
    }
}

@Composable
private fun BadgeIcon(badge: Badge) {
    val tint = MaterialTheme.colorScheme.primary
    when (badge) {
        Badge.DOWNLOADED -> Icon(Icons.Filled.ArrowCircleDown, "Downloaded", tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** While downloads are running: how many are left, the current one's progress, and a way to the Downloads screen. */
@Composable
private fun DownloadStrip() {
    val actions = LocalActions.current
    val all by actions.graph.downloads.all.collectAsStateWithLifecycle()
    val active = all.values.filter { it.state == DownloadState.DOWNLOADING }
    val left = active.size + all.values.count { it.state == DownloadState.QUEUED }
    if (left == 0) return
    val cs = MaterialTheme.colorScheme
    val current = active.firstOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerHigh)
            .clickable { actions.open(Route.Downloads) }
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Download, null, Modifier.size(18.dp), tint = cs.primary)
        Spacer(Modifier.width(10.dp))
        Text("Downloading: ${if (left == 1) "1 song" else "$left songs"} to go", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        if (current != null && current.size > 0) LinearProgressIndicator(
            progress = { (current.bytesDone.toFloat() / current.size).coerceIn(0f, 1f) },
            modifier = Modifier.width(64.dp), drawStopIndicator = {},
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}
