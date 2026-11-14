package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.SearchResult
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.ui.HighlightCorner
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.ArtistImage
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.FilterPill
import eu.bradshaw.seidr.ui.components.ListRow
import eu.bradshaw.seidr.ui.components.SearchField
import eu.bradshaw.seidr.ui.components.SectionHeader
import eu.bradshaw.seidr.ui.components.TabHeader
import eu.bradshaw.seidr.ui.components.TrackRow
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.components.pluralAlbums
import eu.bradshaw.seidr.ui.components.pluralSongs
import eu.bradshaw.seidr.ui.highlightClickable
import eu.bradshaw.seidr.ui.rememberedFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchVm(private val graph: Graph) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var result by mutableStateOf<SearchResult?>(null)
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun onQuery(q: String) {
        query = q
        job?.cancel()
        if (q.isBlank()) { result = null; error = null; return }
        job = viewModelScope.launch {
            delay(250) // instant, but not one request per keystroke
            try {
                result = graph.library.search(q.trim())
                val pls = graph.library.playlists.value ?: runCatching { graph.library.refreshPlaylists() }.getOrDefault(emptyList())
                playlists = pls.filter { it.name.contains(q.trim(), ignoreCase = true) }
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
        }
    }

    fun remember() = graph.settings.addRecentSearch(query)
}

private enum class Filter(val label: String) { ALL("All"), SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists") }

/** The best match for the "All" view's big card. */
private sealed interface Top {
    data class OfArtist(val artist: Artist) : Top
    data class OfAlbum(val album: Album) : Top
    data class OfSong(val song: Song, val index: Int) : Top
    data class OfPlaylist(val playlist: Playlist) : Top
}

/** An exact name match first (artist, album, playlist, song), then a prefix match, then the first artist or song. */
private fun pickTop(r: SearchResult, playlists: List<Playlist>, query: String): Top? {
    val q = query.trim().lowercase()
    fun <T> List<T>.find(name: (T) -> String, test: (String) -> Boolean) = firstOrNull { test(name(it).lowercase()) }
    for (test in listOf<(String) -> Boolean>({ it == q }, { it.startsWith(q) })) {
        r.artist.find({ it.name }, test)?.let { return Top.OfArtist(it) }
        r.album.find({ it.name }, test)?.let { return Top.OfAlbum(it) }
        playlists.find({ it.name }, test)?.let { return Top.OfPlaylist(it) }
        // A kid's unapproved songs can't be the top result: it plays on a tap.
        r.song.withIndex().firstOrNull { !it.value.locked && test(it.value.title.lowercase()) }?.let { return Top.OfSong(it.value, it.index) }
    }
    return r.artist.firstOrNull()?.let { Top.OfArtist(it) }
        ?: r.song.withIndex().firstOrNull { !it.value.locked }?.let { Top.OfSong(it.value, it.index) }
}

@Composable
fun SearchScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "search") { SearchVm(graph) }
    val recent by graph.settings.recentSearches.collectAsStateWithLifecycle()
    val ui by graph.player.ui.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabHeader("Search")
        SearchField(
            vm.query, vm::onQuery, "Artists, songs, albums or playlists",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onSearch = { vm.remember() },
        )

        if (vm.query.isBlank()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding()) {
                if (recent.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("Recent searches", Modifier.weight(1f))
                            TextButton(onClick = { graph.settings.clearRecentSearches() }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                                Text("Clear", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    items(recent) { q ->
                        ListRow(q, null, onClick = { vm.onQuery(q) }, art = { m ->
                            Icon(Icons.Filled.History, null, m.padding(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        })
                    }
                }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEachIndexed { i, f -> FilterPill(f.label, filter == i, { filter = i }) }
        }

        val r = vm.result
        val f = Filter.entries[filter]
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding()) {
            vm.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (r == null) return@LazyColumn
            val none = when (f) {
                Filter.ALL -> r.artist.isEmpty() && r.album.isEmpty() && r.song.isEmpty() && vm.playlists.isEmpty()
                Filter.SONGS -> r.song.isEmpty()
                Filter.ARTISTS -> r.artist.isEmpty()
                Filter.ALBUMS -> r.album.isEmpty()
                Filter.PLAYLISTS -> vm.playlists.isEmpty()
            }
            if (none) {
                item { Text("No results for \"${vm.query}\"", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            // "All" shows the best match and the first few of each; a chip shows the rest.
            val cap = if (f == Filter.ALL) 4 else Int.MAX_VALUE
            if (f == Filter.ALL) pickTop(r, vm.playlists, vm.query)?.let { top -> item(key = "top") { TopResult(top, r, vm) } }
            val playSong = { i: Int -> vm.remember(); actions.player.playContext(r.song, i, "Search: ${vm.query}") }
            if (f == Filter.ALL || f == Filter.SONGS) section(f, "Songs", r.song.take(cap), { "s:${it.id}" }) { i, s ->
                TrackRow(s, onClick = { playSong(i) }, playing = ui.current?.songId == s.id)
            }
            if (f == Filter.ALL || f == Filter.ARTISTS) section(f, "Artists", r.artist.take(cap), { "ar:${it.id}" }) { _, a ->
                ListRow(a.name, pluralAlbums(a.albumCount).takeIf { a.albumCount > 0 }, onClick = { vm.remember(); actions.open(Route.Artist(a.id)) }, art = { m -> ArtistImage(a.coverArt, m) })
            }
            if (f == Filter.ALL || f == Filter.ALBUMS) section(f, "Albums", r.album.take(cap), { "al:${it.id}" }) { _, a ->
                ListRow(a.name, listOfNotNull(a.artistLine.takeIf { it.isNotBlank() }, a.year?.toString()).joinToString(" · "),
                    onClick = { vm.remember(); actions.open(Route.Album(a.id)) }, art = { m -> Cover(a.coverArt, m, size = 160) })
            }
            if (f == Filter.ALL || f == Filter.PLAYLISTS) section(f, "Playlists", vm.playlists.take(cap), { "p:${it.id}" }) { _, p ->
                ListRow(p.name, pluralSongs(p.songCount), onClick = { vm.remember(); actions.open(Route.Playlist(p.id)) },
                    art = { m -> Cover(p.coverArt, m, size = 160) })
            }
        }
    }
}

/** A titled run of results; the title only in the "All" view (a chip already names the rest). */
private fun <T> LazyListScope.section(
    filter: Filter, title: String, list: List<T>, key: (T) -> String, row: @Composable (Int, T) -> Unit,
) {
    if (list.isEmpty()) return
    if (filter == Filter.ALL) item(key = "h:$title") { SectionHeader(title) }
    itemsIndexed(list, key = { _, it -> key(it) }) { i, it -> row(i, it) }
}

@Composable
private fun TopResult(top: Top, r: SearchResult, vm: SearchVm) {
    val actions = LocalActions.current
    val cs = MaterialTheme.colorScheme
    val (title, label) = when (top) {
        is Top.OfArtist -> top.artist.name to "Artist"
        is Top.OfAlbum -> top.album.name to listOfNotNull("Album", top.album.artistLine.takeIf { it.isNotBlank() }).joinToString(" · ")
        is Top.OfSong -> top.song.title to listOfNotNull("Song", top.song.artistLine.takeIf { it.isNotBlank() }).joinToString(" · ")
        is Top.OfPlaylist -> top.playlist.name to "Playlist · " + pluralSongs(top.playlist.songCount)
    }
    val open = {
        vm.remember()
        when (top) {
            is Top.OfArtist -> actions.open(Route.Artist(top.artist.id))
            is Top.OfAlbum -> actions.open(Route.Album(top.album.id))
            is Top.OfSong -> actions.player.playContext(r.song, top.index, "Search: ${vm.query}")
            is Top.OfPlaylist -> actions.open(Route.Playlist(top.playlist.id))
        }
    }
    Column {
        SectionHeader("Top result")
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .clip(RoundedCornerShape(HighlightCorner)).background(cs.surfaceContainer)
                .rememberedFocus().highlightClickable(onClick = open)
                .padding(16.dp),
        ) {
            val art = Modifier.size(92.dp)
            when (top) {
                is Top.OfArtist -> ArtistImage(top.artist.coverArt, art)
                is Top.OfAlbum -> Cover(top.album.coverArt, art.clip(RoundedCornerShape(8.dp)), size = 300)
                is Top.OfSong -> Cover(top.song.coverArt, art.clip(RoundedCornerShape(8.dp)), size = 300)
                is Top.OfPlaylist -> Cover(top.playlist.coverArt, art.clip(RoundedCornerShape(8.dp)), size = 300)
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
