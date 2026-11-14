package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.CollectionLayout
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.HeaderFacts
import eu.bradshaw.seidr.ui.components.approvedCount
import eu.bradshaw.seidr.ui.components.rememberAllDownloaded
import androidx.compose.runtime.remember
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.SectionHeader
import eu.bradshaw.seidr.ui.components.TrackNumber
import eu.bradshaw.seidr.ui.components.TrackRow
import eu.bradshaw.seidr.ui.components.formatLongDuration
import eu.bradshaw.seidr.ui.components.pluralSongs
import kotlinx.coroutines.launch

class AlbumVm(private val graph: Graph, val id: String) : ViewModel() {
    var state by mutableStateOf<Load<Album>>(Load.Loading)
        private set

    init {
        load()
        val sid = SourceType.sourceId(SourceType.ALBUM, id)
        if (sid in graph.downloads.sources.value) graph.sync.request(setOf(sid))
    }

    fun load() {
        viewModelScope.launch {
            state = try { Load.Ready(graph.library.album(id)) } catch (e: Exception) { Load.Error(e.message ?: "Error") }
        }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "album:$id") { AlbumVm(graph, id) }
    val ui by graph.player.ui.collectAsStateWithLifecycle()
    val starredAlbums by graph.library.starredAlbums.collectAsStateWithLifecycle()

    LoadContent(vm.state, vm::load) { album ->
        val songs = album.song
        val discs = songs.mapNotNull { it.discNumber }.distinct().size > 1
        val saved = album.id in starredAlbums
        CollectionLayout(
            washCover = album.coverArt,
            title = album.name,
            art = { m -> Cover(album.coverArt, m.aspectRatio(1f), size = 600) },
            subtitle = {
                Text(
                    album.artistLine,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = album.artistId != null) { album.artistId?.let { actions.open(Route.Artist(it)) } },
                )
                HeaderFacts(
                    listOfNotNull(album.year?.toString(), pluralSongs(album.songCount), approvedCount(songs), formatLongDuration(album.duration)).joinToString(" · "),
                    downloaded = rememberAllDownloaded(remember(songs) { songs.map { it.id } }),
                )
            },
            onShuffle = { graph.albumPlays.record(album); actions.player.shufflePlay(songs, album.name) },
            // A kid's album may show only songs they can't play yet.
            shuffleEnabled = songs.any { !it.locked },
            onPlay = { graph.albumPlays.record(album); actions.player.playContext(songs, 0, album.name, shuffle = false) },
            offline = OfflineSource(SourceType.sourceId(SourceType.ALBUM, album.id), SourceType.ALBUM, album.id, album.name, coverArt = album.coverArt),
            trackCount = songs.size,
            topActions = {
                IconButton(onClick = {
                    actions.launch {
                        if (!graph.library.setAlbumStarred(album.id, !saved)) actions.toast("Couldn't reach the server")
                    }
                }) {
                    Icon(if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (saved) "Remove from library" else "Save to library",
                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            },
        ) {
            var lastDisc: Int? = null
            songs.forEachIndexed { i, song ->
                if (discs && song.discNumber != lastDisc) {
                    lastDisc = song.discNumber
                    item(key = "disc:${song.discNumber}") { SectionHeader("Disc ${song.discNumber}") }
                }
                item(key = "$i:${song.id}") {
                    TrackRow(
                        song,
                        onClick = { graph.albumPlays.record(album); actions.player.playContext(songs, i, album.name) },
                        leading = { TrackNumber(song.track, ui.current?.songId == song.id) },
                        subtitle = song.artistLine,
                        playing = ui.current?.songId == song.id,
                    )
                }
            }
        }
    }
}
