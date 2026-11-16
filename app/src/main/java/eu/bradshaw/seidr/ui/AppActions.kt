package eu.bradshaw.seidr.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.BadKind
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.sync.EditResult
import eu.bradshaw.seidr.ui.screens.LibTab
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

    /** Library at its root, on [tab] with nothing filtered out (Home's "see all" arrows). */
    fun openLibrary(tab: LibTab) {
        nowPlayingOpen = false
        queueOpen = false
        nav.library.show(tab)
        nav.showRoot(Tab.LIBRARY)
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun queue(songs: List<Song>) {
        player.addToQueue(songs)
        toast(graph.app.resources.getQuantityString(R.plurals.menu_added_to_queue, songs.size, songs.size))
    }

    fun toggleLike(songId: String) {
        if (graph.readOnly) return toast(graph.app.getString(R.string.common_read_only_kid))
        scope.launch {
            val now = songId !in graph.browse.starredSongs.value
            if (!graph.browse.setSongStarred(songId, now)) toast(graph.app.getString(R.string.common_cant_reach_server))
            else {
                toast(graph.app.getString(if (now) R.string.common_loved else R.string.common_unloved))
                graph.lovedChanged()
            }
        }
    }

    /** Reports [songId] to a "Bad: " playlist, or takes the report back. */
    fun reportBad(kind: BadKind, songId: String) {
        if (graph.readOnly) return toast(graph.app.getString(R.string.common_read_only_kid))
        scope.launch { toast(graph.bad.toggle(kind, songId)) }
    }

    fun report(result: EditResult, applied: String) {
        toast(when (result) {
            EditResult.Applied -> applied
            EditResult.Queued -> graph.app.getString(R.string.common_edit_queued)
            EditResult.Conflict -> graph.app.getString(R.string.common_edit_conflict)
            is EditResult.Failed -> graph.app.getString(R.string.common_edit_failed, result.message)
        })
    }

    /**
     * As [report], and when the edit went through, the snackbar offers Undo, which
     * runs [undo] (reporting its own result) if tapped.
     */
    fun report(result: EditResult, applied: String, undo: suspend () -> Unit) {
        if (result != EditResult.Applied) return report(result, applied)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(applied, graph.app.getString(R.string.common_undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}

val LocalActions = compositionLocalOf<AppActions> { error("AppActions not provided") }
