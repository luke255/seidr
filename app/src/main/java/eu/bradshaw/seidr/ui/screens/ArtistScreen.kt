package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.data.Star
import eu.bradshaw.seidr.ui.components.StarButton

import eu.bradshaw.seidr.ui.components.TrackNumber
import eu.bradshaw.seidr.ui.components.LocalWideShell
import eu.bradshaw.seidr.ui.RowArtShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.ArtistImage
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.HeaderFacts
import eu.bradshaw.seidr.ui.components.pluralAlbums
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.db.DownloadState
import eu.bradshaw.seidr.ui.components.CoverCard
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.SectionHeader
import eu.bradshaw.seidr.ui.components.PlayButtons
import eu.bradshaw.seidr.ui.components.TrackRow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import eu.bradshaw.seidr.ui.components.reloadOnChanges
import eu.bradshaw.seidr.ui.components.reloaded
import kotlinx.coroutines.Job

data class ArtistPage(val artist: Artist, val top: List<Song>, val albums: List<Album>, val singles: List<Album>, val appearsOn: List<Album>)

class ArtistVm(private val graph: Graph, val id: String) : ViewModel() {
    var state by mutableStateOf<Load<ArtistPage>>(Load.Loading)
        private set

    init {
        load()
        reloadOnChanges(graph) { load(quiet = true) }
    }

    private var loading: Job? = null

    /** [quiet]: a live update, so a failure keeps what's showing. */
    fun load(quiet: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            state = state.reloaded(runCatching {
                val artist = graph.browse.artist(id)
                val top = async { graph.browse.topSongs(artist.name) }
                // OpenSubsonic release types split albums from singles and EPs where tagged.
                val isSingle = { a: Album -> a.releaseTypes.any { it.equals("single", true) || it.equals("ep", true) } }
                val compilation = { a: Album -> a.isCompilation || a.releaseTypes.any { it.equals("compilation", true) } }
                val sorted = artist.album.sortedByDescending { it.year ?: 0 }
                ArtistPage(
                    artist = artist,
                    top = top.await(),
                    albums = sorted.filter { !isSingle(it) && !compilation(it) },
                    singles = sorted.filter(isSingle),
                    appearsOn = sorted.filter { compilation(it) && !isSingle(it) },
                )
            }, quiet)
        }
    }

    /** Every song by the artist, for Shuffle Play when the server has no top tracks. */
    suspend fun allSongs(): List<Song> {
        val page = (state as? Load.Ready)?.value ?: return emptyList()
        return (page.albums + page.singles + page.appearsOn).flatMap { runCatching { graph.browse.album(it.id).song }.getOrDefault(emptyList()) }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "artist:$id") { ArtistVm(graph, id) }
    val ui by graph.player.ui.collectAsStateWithLifecycle()

    LoadContent(vm.state, vm::load) { page ->
        var showAllTop by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        val wide = LocalWideShell.current
        val loadFailed = stringResource(R.string.artist_load_failed)
        val shuffle: () -> Unit = {
            if (page.top.isNotEmpty()) actions.player.shufflePlay(page.top, page.artist.name)
            else actions.launch {
                val songs = vm.allSongs()
                if (songs.isEmpty()) { actions.nowPlayingOpen = false; actions.toast(loadFailed) } else actions.player.shufflePlay(songs, page.artist.name)
            }
            Unit
        }
        // Every album counts as downloaded once its songs are all on the phone, whoever asked for them.
        val downloads by graph.downloads.all.collectAsStateWithLifecycle()
        val albums = page.artist.album
        val songCount = albums.sumOf { it.songCount }
        val allDownloaded = remember(albums, downloads) {
            val done = downloads.values.filter { it.state == DownloadState.DONE }.groupingBy { it.albumId }.eachCount()
            albums.isNotEmpty() && albums.all { it.songCount > 0 && (done[it.id] ?: 0) >= it.songCount }
        }
        // A kid's artist may have only songs they can't play yet.
        val playable = graph.browseKids.current?.artistPlayable(page.artist.id) != false
        val facts = listOfNotNull(albumsText(albums.size), songsText(songCount).takeIf { songCount > 0 }).joinToString(" · ")
        val albumsTitle = stringResource(R.string.artist_albums)
        val singlesTitle = stringResource(R.string.artist_singles)
        val appearsOnTitle = stringResource(R.string.artist_appears_on)
        Column(Modifier.fillMaxSize()) {
            BackTopBar(page.artist.name, showTitle = showTitle) { StarButton(Star.OfArtist(page.artist.copy(album = emptyList()), null)) }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = bottomContentPadding(24.dp)) {
                item {
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtistImage(page.artist.coverArt, Modifier.size(160.dp).shadow(12.dp, CircleShape))
                            Spacer(Modifier.width(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.artist_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(page.artist.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                HeaderFacts(facts, allDownloaded)
                                PlayButtons(onShuffle = shuffle, enabled = playable)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ArtistImage(page.artist.coverArt, Modifier.size(180.dp).shadow(12.dp, CircleShape))
                            Text(page.artist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            HeaderFacts(facts, allDownloaded)
                            PlayButtons(onShuffle = shuffle, enabled = playable)
                        }
                    }
                }
                if (page.top.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.artist_popular)) }
                    val shown = if (showAllTop) page.top else page.top.take(5)
                    itemsIndexed(shown, key = { i, s -> "top:$i:${s.id}" }) { i, song ->
                        val playing = ui.current?.songId == song.id
                        TrackRow(song, onClick = { actions.player.playContext(page.top, i, page.artist.name) },
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TrackNumber(i + 1, playing)
                                    Spacer(Modifier.width(4.dp))
                                    Cover(song.coverArt, Modifier.size(48.dp).clip(RowArtShape), size = 120)
                                }
                            },
                            subtitle = "", playing = playing)
                    }
                    if (page.top.size > 5) item {
                        TextButton(onClick = { showAllTop = !showAllTop }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(if (showAllTop) R.string.artist_show_less else R.string.artist_show_all), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                shelf(albumsTitle, page.albums) { actions.open(Route.Album(it)) }
                shelf(singlesTitle, page.singles) { actions.open(Route.Album(it)) }
                shelf(appearsOnTitle, page.appearsOn) { actions.open(Route.Album(it)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(title: String, albums: List<Album>, open: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "h:$title") { SectionHeader(title) }
    item(key = "s:$title") {
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(albums, key = { it.id }) { a ->
                CoverCard(a.name, a.year?.toString(), a.coverArt, onClick = { open(a.id) })
            }
        }
    }
}
