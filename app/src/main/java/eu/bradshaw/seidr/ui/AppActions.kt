package eu.bradshaw.seidr.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.BadKind
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.sync.EditResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Things any screen can ask for: navigation, playback, snackbars and the
 * shared dialogs. Provided once from [MainScreen] via [LocalActions].
 */
class AppActions(
    val graph: Graph,
    val nav: Navigator,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    val player get() = graph.player

    /** Songs waiting for the "Add to playlist" picker, or null when it's closed. */
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)

    fun open(route: Route) {
        nowPlayingOpen = false
        queueOpen = false
        nav.push(route)
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun queue(songs: List<Song>) {
        player.addToQueue(songs)
        toast(if (songs.size == 1) "Added to queue" else "Added ${songs.size} songs to queue")
    }

    fun toggleLike(songId: String) {
        scope.launch {
            val now = songId !in graph.library.starredSongs.value
            if (!graph.library.setSongStarred(songId, now)) toast("Couldn't reach the server")
            else {
                toast(if (now) "Loved" else "Unloved")
            }
        }
    }

    /** Reports [songId] to a "Bad: " playlist, or takes the report back. */
    fun reportBad(kind: BadKind, songId: String) {
        scope.launch { toast(graph.bad.toggle(kind, songId)) }
    }

    fun report(result: EditResult, applied: String) {
        toast(when (result) {
            EditResult.Applied -> applied
            EditResult.Queued -> "Offline: will save to the server when it's back"
            EditResult.Conflict -> "The playlist changed elsewhere, so nothing was saved. Reloaded."
            is EditResult.Failed -> "Couldn't save: ${result.message}"
        })
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}

val LocalActions = compositionLocalOf<AppActions> { error("AppActions not provided") }
