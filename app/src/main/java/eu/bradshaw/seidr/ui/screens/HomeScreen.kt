package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.data.nameOrder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.PlaylistPlays
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.CoverCard
import eu.bradshaw.seidr.ui.components.SectionHeader
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class HomeData(val newest: List<Album>, val frequent: List<Album>, val starred: List<Album>)

/** Home is built only from server lists (getAlbumList2 types): no recommendations. */
class HomeVm(private val graph: Graph) : ViewModel() {
    var data by mutableStateOf<HomeData?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            refreshing = true
            fun list(type: String) = async { runCatching { graph.browse.albumList(type, 20) }.getOrDefault(emptyList()) }
            val newest = list("newest"); val frequent = list("frequent"); val starred = list("starred")
            launch { runCatching { graph.browse.refreshPlaylists() } }
            data = HomeData(newest.await(), frequent.await(), starred.await())
            refreshing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "home") { HomeVm(graph) }
    val playlists by graph.browse.playlists.collectAsStateWithLifecycle()
    val online by graph.status.online.collectAsStateWithLifecycle()
    val lastPlayed by graph.playlistPlays.lastPlayed.collectAsStateWithLifecycle()
    val alpha by graph.settings.homePlaylistsAlpha.collectAsStateWithLifecycle()
    val server by graph.browseLogin.collectAsStateWithLifecycle()
    val kid by graph.browseKids.rules.collectAsStateWithLifecycle()
    val starred by graph.browse.starredSongs.collectAsStateWithLifecycle()
    val lastLoved by graph.browse.lastLoved.collectAsStateWithLifecycle()
    val recentlyAdded = stringResource(R.string.home_recently_added)
    val mostPlayed = stringResource(R.string.home_most_played)
    val savedAlbums = stringResource(R.string.home_saved_albums)
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
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Home has no tab header, so the first shelf sits where the other tabs' titles do.
                            SectionHeader(stringResource(R.string.home_playlists), Modifier.weight(1f), top = 16.dp)
                            TextButton(onClick = { graph.settings.setHomePlaylistsAlpha(!alpha) }, Modifier.padding(top = 4.dp, end = 4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Text(" " + stringResource(if (alpha) R.string.home_sort_az else R.string.home_sort_recent), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    item {
                        // null is Loved.
                        val shelf = withLoved(sortPlaylists(pls, alpha, lastPlayed), alpha, lastPlayed, lastLoved)
                        val row = rememberLazyListState()
                        // A lazy row keeps its first visible item when items are put in front of it,
                        // so a playlist that arrives first (one just created, after the cached list
                        // showed) would open scrolled out of sight. At the start, stay at the start.
                        // SideEffect runs after this composition is applied but before the row's
                        // next layout, which is when the request has to land.
                        val first = shelf.firstOrNull()?.id ?: "loved"
                        val seenFirst = remember { arrayOfNulls<String>(1) }
                        SideEffect {
                            if (seenFirst[0] != first) {
                                seenFirst[0] = first
                                if (row.firstVisibleItemIndex == 0 && row.firstVisibleItemScrollOffset == 0) row.requestScrollToItem(0)
                            }
                        }
                        LazyRow(state = row, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            itemsIndexed(shelf, key = { _, p -> p?.id ?: "loved" }) { i, p ->
                                val focus = if (i == 0) Modifier.primaryFocus() else Modifier
                                if (p == null) CoverCard(Loved.title, Loved.subtitle(starred), null, onClick = { actions.open(Route.Loved) },
                                    art = { m -> LovedArt(m) }, modifier = focus)
                                else CoverCard(p.name, playlistLine(p, server?.username), p.coverArt, onClick = { actions.open(Route.Playlist(p.id)) },
                                    modifier = focus)
                            }
                        }
                    }
                }
                if (shared.isNotEmpty()) {
                    item(key = "h:shared") { SectionHeader(stringResource(R.string.home_shared_playlists)) }
                    item(key = "s:shared") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(sortPlaylists(shared, alpha, lastPlayed), key = { it.id }) { p ->
                                CoverCard(p.name, playlistLine(p, server?.username), p.coverArt, onClick = { actions.open(Route.Playlist(p.id)) })
                            }
                        }
                    }
                }
                albumShelf(recentlyAdded, d?.newest.orEmpty()) { actions.open(Route.Album(it)) }
                albumShelf(mostPlayed, d?.frequent.orEmpty()) { actions.open(Route.Album(it)) }
                albumShelf(savedAlbums, d?.starred.orEmpty()) { actions.open(Route.Album(it)) }
            }
        }
    }
}

/**
 * A–Z, or most recent first: when it was last played on this phone
 * ([PlaylistPlays] is local: Subsonic keeps no playlist play history) or
 * created on the server, whichever is later, so a new playlist shows at the
 * front. Ties (none of either) go A–Z.
 */
private fun sortPlaylists(pls: List<Playlist>, alpha: Boolean, lastPlayed: Map<String, Long>): List<Playlist> {
    val byName = nameOrder<Playlist> { it.name }
    return if (alpha) pls.sortedWith(byName)
    else pls.sortedWith(compareByDescending<Playlist> { recency(it, lastPlayed) }.then(byName))
}

private fun recency(p: Playlist, lastPlayed: Map<String, Long>): Long = maxOf(lastPlayed[p.id] ?: 0L, epochMs(p.created))

private fun epochMs(iso: String?): Long = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/**
 * [sorted] with Loved (null) put in: first A–Z, else by its recency, which is
 * when it was last played here or a song was last loved, whichever is later.
 */
private fun withLoved(sorted: List<Playlist>, alpha: Boolean, lastPlayed: Map<String, Long>, lastLoved: String?): List<Playlist?> {
    val loved = maxOf(lastPlayed[Loved.PLAYS_KEY] ?: 0L, epochMs(lastLoved))
    val at = if (alpha) 0 else sorted.indexOfFirst { recency(it, lastPlayed) <= loved }.let { if (it < 0) sorted.size else it }
    return sorted.toMutableList<Playlist?>().apply { add(at, null) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumShelf(title: String, albums: List<Album>, open: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "h:$title") { SectionHeader(title) }
    item(key = "s:$title") {
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(albums, key = { it.id }) { a -> CoverCard(a.name, a.artistLine, a.coverArt, onClick = { open(a.id) }) }
        }
    }
}

/** "N songs", plus "by owner" only when someone else owns it (it's noise for your own). */
@Composable
private fun playlistLine(p: Playlist, me: String?): String =
    listOfNotNull(p.owner?.takeIf { it != me }?.let { stringResource(R.string.home_by_owner, it) }, songsText(p.songCount))
        .joinToString(" · ")
