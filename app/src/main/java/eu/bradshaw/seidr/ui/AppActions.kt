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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** An add waiting on "Already added": the song is in that playlist already. */
    var alreadyAdded by mutableStateOf<PendingAdd?>(null)
    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    /** Now Playing or the queue is over the screens. */
    val sheetOpen get() = nowPlayingOpen || queueOpen
    /** An album's or playlist's art, full screen over everything; null when it's closed. */
    var fullArt by mutableStateOf<eu.bradshaw.seidr.ui.components.FullArt?>(null)

    fun open(route: Route) {
        nowPlayingOpen = false
        queueOpen = false
        fullArt = null
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

    /**
     * Adds [songIds] to the playlist, with Undo. A single song that's already in it
     * asks first ([alreadyAdded]) unless [anyway]. The check asks the server, briefly,
     * else goes by the display cache; no answer at all just adds.
     */
    fun addTo(playlistId: String, name: String, songIds: List<String>, anyway: Boolean = false) {
        scope.launch {
            if (!anyway && songIds.size == 1) {
                val now = withTimeoutOrNull(3_000) { graph.editor.songsOf(playlistId) }
                    ?: graph.library.playlistSongs.value[playlistId]
                if (now != null && songIds[0] in now) {
                    alreadyAdded = PendingAdd(playlistId, name, songIds)
                    return@launch
                }
            }
            val r = graph.editor.add(playlistId, songIds)
            report(r, graph.app.getString(R.string.menu_added_to, name)) {
                report(graph.editor.undoAdd(playlistId, songIds), graph.app.getString(R.string.playlist_removed_from, name))
            }
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}

/** An add to a playlist, held while "Already added" asks. */
data class PendingAdd(val playlistId: String, val name: String, val songIds: List<String>)

val LocalActions = compositionLocalOf<AppActions> { error("AppActions not provided") }
