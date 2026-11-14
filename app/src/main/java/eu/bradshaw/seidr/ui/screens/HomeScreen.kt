package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.ui.components.bottomContentPadding
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
import eu.bradshaw.seidr.ui.components.playlistSubtitle
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
            fun list(type: String) = async { runCatching { graph.library.albumList(type, 20) }.getOrDefault(emptyList()) }
            val newest = list("newest"); val frequent = list("frequent"); val starred = list("starred")
            launch { runCatching { graph.library.refreshPlaylists() } }
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
    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    val online by graph.status.online.collectAsStateWithLifecycle()
    val lastPlayed by graph.playlistPlays.lastPlayed.collectAsStateWithLifecycle()
    val alpha by graph.settings.homePlaylistsAlpha.collectAsStateWithLifecycle()
    val server by graph.settings.server.collectAsStateWithLifecycle()
    val kid by graph.kids.rules.collectAsStateWithLifecycle()
    val starred by graph.library.starredSongs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        RefreshBox(vm.refreshing && vm.data != null, onRefresh = vm::load, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding()) {
                // The list keeps its first visible item in place when items arrive
                // above it. Playlists usually load after the album shelves, so
                // without a fixed first item they'd land above the top, out of view.
                item(key = "top") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
                if (!online) item {
                    Text(
                        if (LocalIsTv.current) "You're offline. Seiðr will reconnect when the server is reachable."
                        else "You're offline. Downloaded music is still here: look in Library with the Downloaded filter.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                // A kid with nothing approved yet sees why, rather than an empty app.
                if (kid?.lists?.approved?.isEmpty() == true) item {
                    Text(
                        "Nothing's been approved for you yet. Ask whoever signed you in to add some songs.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                val d = vm.data
                val pls = playlists.orEmpty()
                if (pls.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Home has no tab header, so the first shelf sits where the other tabs' titles do.
                            SectionHeader("Playlists", Modifier.weight(1f), top = 16.dp)
                            TextButton(onClick = { graph.settings.setHomePlaylistsAlpha(!alpha) }, Modifier.padding(top = 4.dp, end = 4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Text(if (alpha) " A–Z" else " Recent", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            // null is Loved.
                            itemsIndexed(withLoved(sortPlaylists(pls, alpha, lastPlayed), alpha, lastPlayed).take(20), key = { _, p -> p?.id ?: "loved" }) { i, p ->
                                val focus = if (i == 0) Modifier.primaryFocus() else Modifier
                                if (p == null) CoverCard(Loved.NAME, Loved.subtitle(starred), null, onClick = { actions.open(Route.Loved) },
                                    art = { m -> LovedArt(m) }, modifier = focus)
                                else CoverCard(p.name, playlistSubtitle(p, server?.username), p.coverArt, onClick = { actions.open(Route.Playlist(p.id)) },
                                    modifier = focus)
                            }
                        }
                    }
                }
                albumShelf("Recently added", d?.newest.orEmpty()) { actions.open(Route.Album(it)) }
                albumShelf("Most played", d?.frequent.orEmpty()) { actions.open(Route.Album(it)) }
                albumShelf("Saved albums", d?.starred.orEmpty()) { actions.open(Route.Album(it)) }
            }
        }
    }
}

/**
 * A–Z, or most recently played on this phone first ([PlaylistPlays] is local:
 * Subsonic keeps no playlist play history). Never-played playlists follow, A–Z.
 */
private fun sortPlaylists(pls: List<Playlist>, alpha: Boolean, lastPlayed: Map<String, Long>): List<Playlist> {
    val byName = compareBy<Playlist, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return if (alpha) pls.sortedWith(byName)
    else pls.sortedWith(compareByDescending<Playlist> { lastPlayed[it.id] ?: 0L }.then(byName))
}

/** [sorted] with Loved (null) put in: first A–Z, else by when it was last played (first of the never-played). */
private fun withLoved(sorted: List<Playlist>, alpha: Boolean, lastPlayed: Map<String, Long>): List<Playlist?> {
    val loved = lastPlayed[Loved.PLAYS_KEY] ?: 0L
    val at = if (alpha) 0 else sorted.indexOfFirst { (lastPlayed[it.id] ?: 0L) <= loved }.let { if (it < 0) sorted.size else it }
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
