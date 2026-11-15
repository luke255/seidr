package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.ResponseStore
import eu.bradshaw.seidr.data.api.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * An admin browsing as one of their kids: a read-only lens. Its own client,
 * [Kids] rules and [Library] run on the kid's login, and only the browsing
 * screens read from it (`Graph.browse`). Everything else (sync, downloads,
 * playback, scrobbles, Connect) stays on the admin's login, and the screens
 * offer no edits while it's open, so nothing can conflict. In memory only:
 * it ends on Back, sign-out or a restart.
 */
class KidView(val config: ServerConfig, graph: Graph) {
    private val scope = CoroutineScope(graph.scope.coroutineContext + SupervisorJob(graph.scope.coroutineContext[Job]))
    private val dir = File(graph.app.cacheDir, "view-as").also { it.deleteRecursively() }

    private val client = SubsonicClient({ config }, graph.settings.coverSalt, graph.baseHttp,
        ResponseStore(File(dir, "api")), graph.status)

    /** Kept between views (in the cache), so opening one again only checks the lists changed. */
    val kids = Kids({ config }, emptyFlow(), { client.strict }, File(graph.app.cacheDir, "view-as-kids"), scope)

    val library = Library(
        client,
        hideBad = MutableStateFlow(true),
        user = MutableStateFlow(config.username.trim().lowercase()),
        showOthersPrivate = MutableStateFlow(false),
        kids = kids,
        scope = scope,
    )

    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}
