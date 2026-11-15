package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.data.db.SourceType
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.CollectionLayout
import eu.bradshaw.seidr.ui.components.HeaderFacts
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.TrackRow
import eu.bradshaw.seidr.ui.components.approvedCount
import eu.bradshaw.seidr.ui.components.formatLongDuration
import eu.bradshaw.seidr.ui.components.pluralSongs
import eu.bradshaw.seidr.ui.components.rememberAllDownloaded
import kotlinx.coroutines.launch

/** The built-in Loved playlist: the server's starred songs. */
object Loved {
    const val NAME = "Loved"
    /** Its key in [eu.bradshaw.seidr.data.PlaylistPlays] (real playlist ids never look like this). */
    const val PLAYS_KEY = "loved"
    val source = OfflineSource(SourceType.STARRED_ID, SourceType.STARRED, "starred", NAME)

    /** Its count, or null until the starred list has loaded (rather than "0 songs"). */
    fun subtitle(starred: Set<String>) = starred.takeIf { it.isNotEmpty() }?.let { pluralSongs(it.size) }
}

class LovedVm(private val graph: Graph) : ViewModel() {
    var state by mutableStateOf<Load<List<Song>>>(Load.Loading)
        private set

    init {
        load()
        // Opening it is a sync trigger, as for playlists.
        if (SourceType.STARRED_ID in graph.downloads.sources.value) graph.sync.request(setOf(SourceType.STARRED_ID))
        // Loves made elsewhere add rows; un-loves just hide (below) until the next load.
        viewModelScope.launch {
            var known = graph.browse.starredSongs.value
            graph.browse.starredSongs.collect { now -> if (!known.containsAll(now)) load(); known = now }
        }
    }

    fun load() {
        viewModelScope.launch {
            state = try { Load.Ready(graph.browse.starredSongsList()) } catch (e: Exception) { Load.Error(e.message ?: "Error") }
        }
    }
}

/** Loved songs as a playlist, with its own Download switch. */
@Composable
fun LovedScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "loved") { LovedVm(graph) }
    val starred by graph.browse.starredSongs.collectAsStateWithLifecycle()
    val ui by graph.player.ui.collectAsStateWithLifecycle()
    LoadContent(vm.state, vm::load) { all ->
        // Hide songs un-loved since the list loaded.
        val songs = remember(all, starred) { all.filter { it.id in starred } }
        fun play(i: Int, shuffle: Boolean? = null) {
            graph.playlistPlays.record(Loved.PLAYS_KEY)
            actions.player.playContext(songs, i, Loved.NAME, shuffle)
        }
        CollectionLayout(
            title = Loved.NAME,
            art = { m -> LovedArt(m) },
            subtitle = {
                HeaderFacts(
                    listOfNotNull(pluralSongs(songs.size), approvedCount(songs), formatLongDuration(songs.sumOf { it.duration }))
                        .joinToString(" · "),
                    downloaded = rememberAllDownloaded(remember(songs) { songs.map { it.id } }),
                )
            },
            onShuffle = { play(-1, true) },
            shuffleEnabled = songs.any { !it.locked },
            onPlay = { play(0, false) },
            shufflePrimary = true,
            offline = Loved.source,
            trackCount = songs.size,
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                TrackRow(song, onClick = { play(i) }, playing = ui.current?.songId == song.id)
            }
        }
    }
}

@Composable
fun LovedArt(modifier: Modifier) {
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(4)).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiaryContainer))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxSize(0.4f))
    }
}
