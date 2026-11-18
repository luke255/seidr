package eu.bradshaw.seidr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import eu.bradshaw.seidr.ui.screens.LibTab
import eu.bradshaw.seidr.ui.screens.LibraryView
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
    data object Log : Route
    data class PlaylistHistory(val id: String) : Route
    data object DeletedPlaylists : Route
    data class LicenceText(val title: String, val file: String) : Route
}

/** Where focus goes on a tab switch the tab bar didn't make: the main action, or back where it was. */
enum class TabFocus { OPENED, RETURNED }

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

    /** What Back would show: the page under the top one, or from another tab's root, Home's top page. */
    val below: Entry? get() = when {
        stack.size > 1 -> stack[stack.size - 2]
        tab != Tab.HOME -> stacks.getValue(Tab.HOME).last()
        else -> null
    }

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
        if (tab != Tab.HOME) { tab = Tab.HOME; focusIntoTab = TabFocus.RETURNED; return true }
        return false
    }

    fun select(t: Tab) {
        if (t == tab) {
            val s = stacks.getValue(t)
            while (s.size > 1) s.removeAt(s.lastIndex).viewModelStore.clear()
        } else tab = t
    }

    /** Library's tab, filters and search (see [LibraryView]). */
    val library = LibraryView()

    /**
     * Set when something other than the tab bar switched tabs, so remote focus
     * goes into the new tab's screen rather than staying on the tab. Read once.
     */
    var focusIntoTab: TabFocus? = null

    /** Tab [t] at its root, whatever it had open, from a screen's own link. */
    fun showRoot(t: Tab) {
        focusIntoTab = TabFocus.OPENED
        tab = t
        val s = stacks.getValue(t)
        while (s.size > 1) s.removeAt(s.lastIndex).viewModelStore.clear()
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
        library.show(LibTab.PLAYLISTS)
        tab = t
    }

    /** Set from outside Compose (notification tap) to open Now Playing. */
    var requestNowPlaying by mutableStateOf(false)

    override fun onCleared() {
        stacks.values.flatten().forEach { it.viewModelStore.clear() }
    }
}
