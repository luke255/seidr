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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import eu.bradshaw.seidr.ui.primaryFocus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
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
import eu.bradshaw.seidr.ui.components.SortButton
import eu.bradshaw.seidr.ui.components.label
import eu.bradshaw.seidr.data.AlbumSort
import eu.bradshaw.seidr.data.ArtistSort
import eu.bradshaw.seidr.data.LovedAt
import eu.bradshaw.seidr.data.PlaylistSort
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
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.rememberedFocus
import eu.bradshaw.seidr.ui.selectToEdit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState

class LibraryVm(private val graph: Graph) : ViewModel() {
    var artists by mutableStateOf<Load<List<Artist>>>(Load.Loading)
        private set
    var albums by mutableStateOf<Load<List<Album>>>(Load.Loading)
        private set
    var playlistsError by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        load(false)
        // A scan changed the music: fetch the lists again, quietly, once a burst of
        // scans (one per folder the server's watcher saw change) has settled. New kid
        // rules filter the same lists afresh.
        viewModelScope.launch {
            graph.changes.filter { it != Graph.Change.ANNOTATIONS }.collectLatest {
                if (it == Graph.Change.KIDS) return@collectLatest load(false, quiet = true)
                delay(3_000)
                load(true, quiet = true)
            }
        }
    }

    private var loading: Job? = null

    /** [quiet]: a live update, so no refresh spinner. */
    fun load(refresh: Boolean, quiet: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            refreshing = refresh && !quiet
            launch {
                playlistsError = try { graph.browse.refreshPlaylists(); null } catch (e: Exception) { e.message ?: "Error" }
            }
            // A live update that fails keeps what's showing.
            launch {
                artists = try { Load.Ready(graph.browse.artists(refresh)) } catch (e: Exception) { if (quiet) artists else Load.Error(e.message ?: "Error") }
            }
            albums = try { Load.Ready(graph.browse.allAlbums(refresh)) } catch (e: Exception) { if (quiet) albums else Load.Error(e.message ?: "Error") }
            refreshing = false
        }
    }
}

enum class LibTab(@StringRes val label: Int, @StringRes val searchHint: Int) {
    PLAYLISTS(R.string.library_tab_playlists, R.string.library_search_playlists),
    ARTISTS(R.string.library_tab_artists, R.string.library_search_artists),
    ALBUMS(R.string.library_tab_albums, R.string.library_search_albums),
}

/**
 * What Library is showing: its tab, filters and search. Held by the
 * [eu.bradshaw.seidr.ui.Navigator] rather than the screen, so Home's shelves
 * can open Library on a tab ([show]) before it's drawn.
 */
class LibraryView {
    var tab by mutableStateOf(LibTab.PLAYLISTS)
    var downloadedOnly by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")

    /** Bumped by [show], so the lists start again from the top. */
    var shown by mutableIntStateOf(0)
        private set

    /** [tab], everything in it, from the top: no search, no Downloaded filter. */
    fun show(tab: LibTab) {
        this.tab = tab
        shown++
        downloadedOnly = false
        searching = false
        query = ""
    }
}

/** "1 song" / "N songs", from resources. */
@Composable
internal fun songsText(n: Int): String = pluralStringResource(R.plurals.library_songs, n, n)

/** "1 album" / "N albums", from resources. */
@Composable
internal fun albumsText(n: Int): String = pluralStringResource(R.plurals.library_albums, n, n)

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
    val view = actions.nav.library
    val tab = view.tab
    val downloadedOnly = view.downloadedOnly
    val searching = view.searching
    val query = view.query
    // Each tab's order is its own and remembered; playlists' is Home's too.
    val playlistSort by graph.settings.playlistsSort.collectAsStateWithLifecycle()
    val artistSort by graph.settings.artistSort.collectAsStateWithLifecycle()
    val albumSort by graph.settings.albumSort.collectAsStateWithLifecycle()
    val lastPlayed by graph.playlistPlays.lastPlayed.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val grid by graph.settings.libraryGrid.collectAsStateWithLifecycle()
    val withShared by graph.settings.libraryShared.collectAsStateWithLifecycle()

    val playlists by graph.browse.playlists.collectAsStateWithLifecycle()
    val sources by graph.downloads.sources.collectAsStateWithLifecycle()
    val downloads by graph.downloads.all.collectAsStateWithLifecycle()
    val playlistSongs by graph.browse.playlistSongs.collectAsStateWithLifecycle()
    val server by graph.browseLogin.collectAsStateWithLifecycle()
    val starred by graph.browse.starredSongs.collectAsStateWithLifecycle()
    val lastLoved by graph.browse.lastLoved.collectAsStateWithLifecycle()
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

    val q = query.trim()
    fun matches(vararg s: String?) = q.isEmpty() || s.any { it?.contains(q, ignoreCase = true) == true }

    val tv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            val field = remember { FocusRequester() }
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { view.searching = false; view.query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_close_search)) }
                SearchField(
                    query, { view.query = it }, stringResource(tab.searchHint),
                    modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(field),
                )
            }
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        } else {
            TabHeader(stringResource(R.string.library_title)) {
                IconButton(onClick = { view.searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.library_search_library)) }
                if (!graph.readOnly) IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, stringResource(R.string.library_create_playlist)) }
                if (!LocalWideShell.current) SettingsButton()
            }
        }

        // One row of chips: what to show, then (phones) only what's downloaded, then
        // (playlists, if anyone else has some) other people's too.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibTab.entries.forEach { t -> FilterPill(stringResource(t.label), tab == t, { view.tab = t }) }
            if (!tv) {
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                FilterPill(stringResource(R.string.library_downloaded), downloadedOnly, { view.downloadedOnly = !downloadedOnly }, icon = {
                    Icon(Icons.Filled.ArrowCircleDown, null, Modifier.size(16.dp),
                        tint = if (downloadedOnly) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                })
            }
            if (tab == LibTab.PLAYLISTS && playlists.orEmpty().any(graph.browse::isOthers)) {
                FilterPill(stringResource(R.string.library_shared), withShared, { graph.settings.setLibraryShared(!withShared) }, icon = {
                    Icon(Icons.Filled.Group, null, Modifier.size(16.dp),
                        tint = if (withShared) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                })
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val sortModifier = Modifier.weight(1f)
            when (tab) {
                LibTab.PLAYLISTS -> SortButton(PlaylistSort.entries, playlistSort, PlaylistSort::label, graph.settings::setPlaylistsSort, sortModifier)
                LibTab.ARTISTS -> SortButton(ArtistSort.entries, artistSort, ArtistSort::label, graph.settings::setArtistSort, sortModifier)
                LibTab.ALBUMS -> SortButton(AlbumSort.entries, albumSort, AlbumSort::label, graph.settings::setAlbumSort, sortModifier)
            }
            IconButton(onClick = { graph.settings.setLibraryGrid(!grid) }) {
                if (grid) Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.library_show_list)) else Icon(Icons.Filled.GridView, stringResource(R.string.library_show_grid))
            }
        }

        if (!tv) DownloadStrip()

        RefreshBox(refreshing = vm.refreshing, onRefresh = { vm.load(true); graph.sync.request() }, modifier = Modifier.weight(1f)) {
            key(view.shown) { when (tab) {
                LibTab.PLAYLISTS -> {
                    val list = playlists
                    if (list == null) {
                        if (vm.playlistsError != null) LoadContent(Load.Error(vm.playlistsError!!), { vm.load(true) }) {}
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val shown = list.filter {
                            (withShared || !graph.browse.isOthers(it)) && (!downloadedOnly || it.id in offlinePlaylists) && matches(it.name)
                        }
                        val withLoved = (!downloadedOnly || lovedOffline) && matches(Loved.title)
                        // Loved (null) where the order places it, as on Home.
                        val sorted = playlistSort.sort(shown, lastPlayed, if (withLoved) LovedAt(lastPlayed[Loved.PLAYS_KEY], lastLoved) else null)
                        val items = sorted.map { p ->
                            if (p == null) return@map LibItem(
                                "loved", Loved.title, Loved.subtitle(starred) ?: stringResource(R.string.library_playlist),
                                badge = if (lovedOffline) Badge.DOWNLOADED else null,
                                art = { m -> LovedArt(m) }, onClick = { actions.open(Route.Loved) },
                            )
                            val owner = p.owner?.takeIf { it != server?.username }?.let { stringResource(R.string.library_by_owner, it) }
                            LibItem(
                                p.id, p.name, listOfNotNull(owner, songsText(p.songCount)).joinToString(" · "),
                                gridSubtitle = owner ?: songsText(p.songCount),
                                badge = if (p.id in offlinePlaylists) Badge.DOWNLOADED else null,
                                art = { m -> Cover(p.coverArt, m, size = 300) }, onClick = { actions.open(Route.Playlist(p.id)) },
                            )
                        }
                        LibContent(items, grid, empty = emptyText(q, downloadedOnly), order = playlistSort.key)
                    }
                }
                // Most played needs every album's plays: wait for them rather than show A–Z and jump.
                LibTab.ARTISTS -> LoadContent(
                    if (artistSort == ArtistSort.PLAYS && vm.albums is Load.Loading) Load.Loading else vm.artists, { vm.load(true) },
                ) { all ->
                    val withDownloads = if (downloadedOnly) downloads.values.mapNotNull { it.artistId }.toSet() else emptySet()
                    val items = all.filter { (!downloadedOnly || it.id in withDownloads) && matches(it.name) }
                        .let { l -> artistSort.sort(l) { ArtistSort.plays((vm.albums as? Load.Ready)?.value.orEmpty()) } }
                        .map { a ->
                            LibItem(a.id, a.name, albumsText(a.albumCount),
                                round = true, art = { m -> ArtistImage(a.coverArt, m) }, onClick = { actions.open(Route.Artist(a.id)) })
                        }
                    LibContent(items, grid, empty = emptyText(q, downloadedOnly), order = artistSort.key)
                }
                LibTab.ALBUMS -> LoadContent(vm.albums, { vm.load(true) }) { all ->
                    val items = all
                        .filter { (!downloadedOnly || SourceType.sourceId(SourceType.ALBUM, it.id) in sources) && matches(it.name, it.artistLine) }
                        .let { albumSort.sort(it) }
                        .map { a ->
                            LibItem(a.id, a.name, listOfNotNull(a.artistLine.takeIf { it.isNotBlank() }, a.year?.toString()).joinToString(" · "),
                                gridSubtitle = a.artistLine,
                                badge = if (SourceType.sourceId(SourceType.ALBUM, a.id) in sources) Badge.DOWNLOADED else null,
                                art = { m -> Cover(a.coverArt, m, size = 300) }, onClick = { actions.open(Route.Album(a.id)) })
                        }
                    LibContent(items, grid, empty = emptyText(q, downloadedOnly), order = albumSort.key)
                }
            } }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        // Smart playlists are Navidrome's; a kid's login keeps to plain ones.
        val smartOn by graph.smart.available.collectAsStateWithLifecycle()
        val canSmart = smartOn && !graph.kids.isKid
        var smart by rememberSaveable { mutableStateOf(false) }
        val createFailed = stringResource(R.string.library_create_failed)
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.library_create_playlist)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.library_name)) }, singleLine = true, modifier = Modifier.selectToEdit())
                    if (canSmart) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                                .toggleable(smart, role = Role.Checkbox, onValueChange = { smart = it }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(smart, null)
                            Text(stringResource(R.string.smart_create_choice), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    creating = false
                    // A smart one is made by its editor's Save, once it has rules.
                    if (canSmart && smart) return@TextButton actions.open(Route.SmartRules(null, name.trim()))
                    actions.launch {
                        val id = graph.editor.create(name.trim(), emptyList())
                        if (id == null) actions.toast(createFailed)
                        else {
                            runCatching { graph.browse.refreshPlaylists() }
                            actions.open(Route.Playlist(id))
                        }
                    }
                }) { Text(stringResource(R.string.library_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

@Composable
private fun emptyText(query: String, downloadedOnly: Boolean) = when {
    query.isNotEmpty() -> stringResource(R.string.library_no_match, query)
    downloadedOnly -> stringResource(R.string.library_none_downloaded)
    else -> stringResource(R.string.library_empty)
}

@Composable
private fun LibContent(items: List<LibItem>, grid: Boolean, empty: String, order: String) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Rows are keyed, so a new order would keep whichever row was on screen, now somewhere
    // else in the list. A new order starts at the top; coming back keeps the place.
    var shownOrder by rememberSaveable { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (order != shownOrder) {
            shownOrder = order
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }
    if (items.isEmpty()) {
        Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp))
        return
    }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(128.dp), Modifier.fillMaxSize(), gridState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = bottomContentPadding().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, it -> GridCell(it, if (i == 0) Modifier.primaryFocus() else Modifier) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = bottomContentPadding(8.dp)) {
            // The first is where a Home shelf's arrow lands.
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                ListRow(
                    item.title, item.subtitle, onClick = item.onClick, artSize = 64.dp,
                    art = item.art,
                    badge = item.badge?.let { b -> { BadgeIcon(b) } },
                    modifier = if (i == 0) Modifier.primaryFocus() else Modifier,
                )
            }
        }
    }
}

@Composable
private fun GridCell(item: LibItem, modifier: Modifier = Modifier) {
    Column(
        modifier.rememberedFocus().highlightClickable(onClick = item.onClick).padding(CardPadding),
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
        Badge.DOWNLOADED -> Icon(Icons.Filled.ArrowCircleDown, stringResource(R.string.library_downloaded), tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** While downloads are running: how many are left, the whole batch's progress, and a way to the Downloads screen. */
@Composable
private fun DownloadStrip() {
    val actions = LocalActions.current
    val now by actions.graph.downloads.batch.collectAsStateWithLifecycle()
    // Held a moment after the queue empties, so a gap between files never blinks it away.
    var shown by remember { mutableStateOf(now) }
    LaunchedEffect(now) { if (now == null) delay(1_500); shown = now }
    val batch = now ?: shown ?: return
    val left = batch.left
    val cs = MaterialTheme.colorScheme
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
        Text(pluralStringResource(R.plurals.library_downloading_left, left, left), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        val progress by animateFloatAsState(batch.fraction, label = "downloaded")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(64.dp), drawStopIndicator = {})
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}
