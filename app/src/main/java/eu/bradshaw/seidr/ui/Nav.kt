package eu.bradshaw.seidr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.util.UUID

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data class Playlist(val id: String) : Route
    data object Loved : Route
    data class Album(val id: String) : Route
    data class Artist(val id: String) : Route
    data object Downloads : Route
    data object Visualiser : Route
    data object Settings : Route
    data object SignInDevice : Route
    data object Licences : Route
    data object WhatsNew : Route
    data class PlaylistHistory(val id: String) : Route
    data object DeletedPlaylists : Route
    data class LicenceText(val title: String, val file: String) : Route
}

enum class Tab(val root: Route) { HOME(Route.Home), SEARCH(Route.Search), LIBRARY(Route.Library) }

/** One screen on a back stack. Owns the ViewModels of that screen; they're cleared when it's popped. */
class Entry(val route: Route) : ViewModelStoreOwner {
    val id: String = UUID.randomUUID().toString()
    override val viewModelStore = ViewModelStore()
}

/**
 * A back stack per bottom-nav tab: switching tabs keeps each
 * tab's place, tapping the current tab returns to its root. Lives in an
 * activity-scoped ViewModel so it survives configuration changes such as
 * unfolding the phone.
 */
class Navigator : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stacks: Map<Tab, SnapshotStateList<Entry>> =
        Tab.entries.associateWith { mutableStateListOf(Entry(it.root)) }

    val stack: List<Entry> get() = stacks.getValue(tab)
    val top: Entry get() = stack.last()
    val root: Entry get() = stack.first()

    fun push(route: Route) {
        val s = stacks.getValue(tab)
        if (s.last().route == route) return
        s.add(Entry(route))
    }

    fun pop(): Boolean {
        val s = stacks.getValue(tab)
        if (s.size > 1) {
            s.removeAt(s.lastIndex).viewModelStore.clear()
            return true
        }
        if (tab != Tab.HOME) { tab = Tab.HOME; return true }
        return false
    }

    fun select(t: Tab) {
        if (t == tab) {
            val s = stacks.getValue(t)
            while (s.size > 1) s.removeAt(s.lastIndex).viewModelStore.clear()
        } else tab = t
    }

    /** Opens a route in the given tab (e.g. Downloads from a notification). */
    fun open(t: Tab, route: Route) {
        tab = t
        push(route)
    }

    /** The kid view the stacks were built under (see `MainActivity`). */
    var kidView: Any? = null

    /** Every tab back to its root, on [t], with fresh ViewModels (e.g. browsing as a kid starts or ends). */
    fun reset(t: Tab = Tab.HOME) {
        stacks.values.forEach { s ->
            s.forEach { it.viewModelStore.clear() }
            val root = Entry(s.first().route)
            s.clear(); s.add(root)
        }
        tab = t
    }

    /** Set from outside Compose (notification tap) to open Now Playing. */
    var requestNowPlaying by mutableStateOf(false)

    override fun onCleared() {
        stacks.values.flatten().forEach { it.viewModelStore.clear() }
    }
}
