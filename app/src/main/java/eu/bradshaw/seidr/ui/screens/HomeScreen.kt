package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.data.AlbumSort
import eu.bradshaw.seidr.data.ArtistSort
import eu.bradshaw.seidr.data.LovedAt
import eu.bradshaw.seidr.data.PlaylistSort
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.ui.CardPadding
import eu.bradshaw.seidr.ui.highlightClickable
import eu.bradshaw.seidr.ui.rememberedFocus
import eu.bradshaw.seidr.ui.components.ArtistImage
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import eu.bradshaw.seidr.ui.components.RefreshBox
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.primaryFocus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.CoverCard
import eu.bradshaw.seidr.ui.components.SectionHeader
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HomeData(val newest: List<Album>, val frequent: List<Album>, val artists: List<Artist>)

/** How many a Home shelf shows; its arrow opens the rest in Library. */
private const val SHELF = 15

/** Home is built only from server lists (getAlbumList2 types): no recommendations. */
class HomeVm(private val graph: Graph) : ViewModel() {
    var data by mutableStateOf<HomeData?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var loading: Job? = null

    init {
        load()
        // Live: a scan or new kid rules reload at once; plays (a burst per track) once they've settled.
        viewModelScope.launch {
            graph.changes.collectLatest { c ->
                if (c == Graph.Change.ANNOTATIONS) delay(20_000)
                load(quiet = true)
            }
        }
    }

    /** [quiet]: a live update, so no refresh spinner, and a list that fails keeps what it showed. */
    fun load(quiet: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            if (!quiet) refreshing = true
            val old = data
            fun list(type: String, size: Int = SHELF) = async { runCatching { graph.browse.albumList(type, size) }.getOrNull() }
            // Artists' plays are their albums' (as Library's Most played sorts them), so
            // fetch well past the albums shelf: enough to rank everyone who's been played.
            val newest = list("newest"); val frequent = list("frequent", 500)
            val artists = async { runCatching { graph.browse.artists() }.getOrNull() }
            launch { runCatching { graph.browse.refreshPlaylists() } }
            val played = frequent.await()
            val topArtists = played?.let { p ->
                val plays = ArtistSort.plays(p)
                artists.await()?.let { a -> ArtistSort.PLAYS.sort(a) { plays }.filter { (plays[it.id] ?: 0L) > 0 }.take(SHELF) }
            }
            data = HomeData(
                newest.await() ?: old?.newest.orEmpty(),
                played?.take(SHELF) ?: old?.frequent.orEmpty(),
                topArtists ?: old?.artists.orEmpty(),
            )
            refreshing = false
        }
    }
}

@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "home") { HomeVm(graph) }
    val playlists by graph.browse.playlists.collectAsStateWithLifecycle()
    val online by graph.status.online.collectAsStateWithLifecycle()
    val lastPlayed by graph.playlistPlays.lastPlayed.collectAsStateWithLifecycle()
    val server by graph.browseLogin.collectAsStateWithLifecycle()
    val kid by graph.browseKids.rules.collectAsStateWithLifecycle()
    val starred by graph.browse.starredSongs.collectAsStateWithLifecycle()
    val lastLoved by graph.browse.lastLoved.collectAsStateWithLifecycle()
    val newest = stringResource(R.string.home_new)
    val mostPlayed = stringResource(R.string.library_tab_albums)
    val mostPlayedArtists = stringResource(R.string.library_tab_artists)
    val settings = graph.settings
    Column(Modifier.fillMaxSize()) {
        RefreshBox(vm.refreshing && vm.data != null, onRefresh = vm::load, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding()) {
                // The list keeps its first visible item in place when items arrive
                // above it. Playlists usually load after the album shelves, so
                // without a fixed first item they'd land above the top, out of view.
                item(key = "top") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
                item(key = "viewing-as") { ViewingAsBanner() }
                if (!online) item {
                    Text(
                        if (LocalIsTv.current) stringResource(R.string.home_offline_tv)
                        else stringResource(R.string.home_offline),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                // A kid with nothing approved yet sees why, rather than an empty app.
                if (kid?.lists?.approved?.isEmpty() == true) item {
                    Text(
                        stringResource(R.string.home_kid_empty),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                val d = vm.data
                // The user's own, then (on a shelf of their own) everyone else's.
                val (shared, pls) = playlists.orEmpty().partition(graph.browse::isOthers)
                if (playlists != null) {
                    // Home has no tab header, so the first shelf sits where the other tabs' titles do.
                    item { SectionHeader(stringResource(R.string.home_playlists), top = 16.dp) }
                    item {
                        // Always most recent first. null is Loved.
                        val shelf = PlaylistSort.RECENT.sort(pls, lastPlayed, LovedAt(lastPlayed[Loved.PLAYS_KEY], lastLoved)).take(SHELF)
                        LazyRow(state = shelfState(shelf.firstOrNull()?.id ?: "loved"), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            itemsIndexed(shelf, key = { _, p -> p?.id ?: "loved" }) { i, p ->
                                val focus = if (i == 0) Modifier.primaryFocus() else Modifier
                                if (p == null) CoverCard(Loved.title, Loved.subtitle(starred), null, onClick = { actions.open(Route.Loved) },
                                    art = { m -> LovedArt(m) }, modifier = focus)
                                else CoverCard(p.name, playlistLine(p, server?.username), p.coverArt, onClick = { actions.open(Route.Playlist(p.id)) },
                                    modifier = focus)
                            }
                            seeAll { settings.setPlaylistsSort(PlaylistSort.RECENT); settings.setLibraryShared(false); actions.openLibrary(LibTab.PLAYLISTS) }
                        }
                    }
                }
                if (shared.isNotEmpty()) {
                    item(key = "h:shared") { SectionHeader(stringResource(R.string.library_shared)) }
                    item(key = "s:shared") {
                        val shelf = PlaylistSort.RECENT.sort(shared, lastPlayed, loved = null).filterNotNull().take(SHELF)
                        LazyRow(state = shelfState(shelf.firstOrNull()?.id), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(shelf, key = { it.id }) { p ->
                                CoverCard(p.name, playlistLine(p, server?.username), p.coverArt, onClick = { actions.open(Route.Playlist(p.id)) })
                            }
                            seeAll { settings.setPlaylistsSort(PlaylistSort.RECENT); settings.setLibraryShared(true); actions.openLibrary(LibTab.PLAYLISTS) }
                        }
                    }
                }
                // Each arrow opens Library in the shelf's own order.
                fun albums(sort: AlbumSort) { settings.setAlbumSort(sort); actions.openLibrary(LibTab.ALBUMS) }
                albumShelf(newest, d?.newest.orEmpty(), { actions.open(Route.Album(it)) }) { albums(AlbumSort.ADDED) }
                albumShelf(mostPlayed, d?.frequent.orEmpty(), { actions.open(Route.Album(it)) }) { albums(AlbumSort.PLAYS) }
                val artists = d?.artists.orEmpty()
                if (artists.isNotEmpty()) {
                    item(key = "h:artists") { SectionHeader(mostPlayedArtists) }
                    item(key = "s:artists") {
                        LazyRow(state = shelfState(artists.firstOrNull()?.id), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(artists, key = { it.id }) { a ->
                                CoverCard(a.name, albumsText(a.albumCount), a.coverArt, onClick = { actions.open(Route.Artist(a.id)) },
                                    round = true, art = { m -> ArtistImage(a.coverArt, m) })
                            }
                            seeAll { settings.setArtistSort(ArtistSort.PLAYS); actions.openLibrary(LibTab.ARTISTS) }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumShelf(
    title: String, albums: List<Album>, open: (String) -> Unit, more: () -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "h:$title") { SectionHeader(title) }
    item(key = "s:$title") {
        LazyRow(state = shelfState(albums.firstOrNull()?.id), contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(albums, key = { it.id }) { a -> CoverCard(a.name, a.artistLine, a.coverArt, onClick = { open(a.id) }) }
            seeAll(more)
        }
    }
}

/**
 * A shelf's scroll state. A lazy row keeps its first visible item when items are put
 * in front of it, so one that arrives first (an album just added, a playlist just
 * created) would land out of sight. At the start, stay at the start. SideEffect runs
 * after this composition is applied but before the row's next layout, which is when
 * the request has to land.
 */
@Composable
private fun shelfState(first: String?): LazyListState {
    val row = rememberLazyListState()
    val seenFirst = remember { arrayOfNulls<String>(1) }
    SideEffect {
        if (seenFirst[0] != first) {
            seenFirst[0] = first
            if (row.firstVisibleItemIndex == 0 && row.firstVisibleItemScrollOffset == 0) row.requestScrollToItem(0)
        }
    }
    return row
}

/** A shelf's last item: an arrow to the whole list in Library. As tall as a card's art. */
private fun androidx.compose.foundation.lazy.LazyListScope.seeAll(onClick: () -> Unit) {
    item(key = "see-all") {
        val label = stringResource(R.string.home_see_all)
        val focusManager = LocalFocusManager.current
        Box(
            Modifier.width(96.dp).rememberedFocus()
                // Let go of focus before Home goes: a removed focused item hands it to the first tab.
                .highlightClickable { focusManager.clearFocus(force = true); onClick() }.padding(CardPadding)
                .height(128.dp).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** "N songs", plus "by owner" only when someone else owns it (it's noise for your own). */
@Composable
private fun playlistLine(p: Playlist, me: String?): String =
    listOfNotNull(p.owner?.takeIf { it != me }?.let { stringResource(R.string.home_by_owner, it) }, songsText(p.songCount))
        .joinToString(" · ")
