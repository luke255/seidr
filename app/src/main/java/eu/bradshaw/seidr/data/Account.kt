package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.api.ResponseStore
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.File

/** Signing in and out: from the login form, or from another device over Connect. */
object Account {
    /**
     * Pings [config] with a throwaway client. Returns an error message, or null if
     * the server answered. Doesn't sign in: saving the config switches the whole app over.
     */
    suspend fun probe(graph: Graph, context: Context, config: ServerConfig): String? {
        val probe = SubsonicClient({ config }, "", graph.baseHttp, ResponseStore(File(context.cacheDir, "login-probe")), graph.status)
        return try {
            val r = probe.strict.ping().ok()
            Log.i("Login", "connected to ${r.type} ${r.serverVersion}")
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            context.getString(R.string.account_connect_failed, config.url, e.message ?: e.javaClass.simpleName) +
                if (needsLanPermission(context)) "\n" + context.getString(R.string.account_lan_permission) else ""
        }
    }

    /**
     * Checks a kid's login before a grown-up's device keeps it: the server
     * accepts it, and it isn't an admin. Returns an error message, or null.
     */
    suspend fun probeKid(graph: Graph, context: Context, config: ServerConfig): String? {
        probe(graph, context, config)?.let { return it }
        val probe = SubsonicClient({ config }, "", graph.baseHttp, ResponseStore(File(context.cacheDir, "login-probe")), graph.status)
        val user = try {
            probe.strict.getUser(config.username).ok().user
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return context.getString(R.string.account_check_failed_why, config.username, e.message ?: e.javaClass.simpleName)
        }
        return when {
            user == null -> context.getString(R.string.account_check_failed, config.username)
            user.adminRole -> context.getString(R.string.account_kid_is_admin, config.username)
            else -> null
        }
    }

    /** Signs in with a config [probe] accepted. On the main thread. */
    fun commit(graph: Graph, config: ServerConfig) {
        graph.client.clearCache()
        graph.settings.saveServer(config)
        graph.sync.schedulePeriodic()
        graph.sync.request()
        graph.live.start()
        graph.connect.startDiscovery() // held back while signed out
        // On the app scope: the login screen leaves composition as soon as the config is saved.
        graph.scope.launch { runCatching { graph.library.refreshStarred() } }
        // Bad: playlists of this login's own mean it reports problems (from another
        // device, say), so Reporting comes on. Only at sign-in: turning it off sticks.
        if (!config.isKid && !graph.settings.badReports.value) graph.scope.launch {
            val me = config.username.trim()
            val own = runCatching { graph.library.fetchAllPlaylists() }.getOrNull().orEmpty()
                .filter { it.owner == null || it.owner.trim().equals(me, ignoreCase = true) }
            if (BadReports.find(own).isNotEmpty()) graph.settings.setBadReports(true)
        }
    }

    /** Whether [config]'s login is a server admin (strict: null if the server can't say). */
    suspend fun isAdmin(graph: Graph, config: ServerConfig): Boolean? =
        try {
            graph.client.strict.getUser(config.username).ok().user?.adminRole
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    /**
     * Opens a read-only [KidView] of [kid] for an admin: checks the admin role
     * and the kid's login, and fetches their lists first so screens open on
     * the kid's view, not an empty one. Returns an error message, or null.
     */
    suspend fun viewAs(graph: Graph, kid: KidLogin): String? {
        val own = graph.settings.server.value?.takeIf { !it.isKid } ?: return graph.app.getString(R.string.account_not_grownup)
        when (isAdmin(graph, own)) {
            null -> return graph.app.getString(R.string.account_own_check_failed)
            false -> return graph.app.getString(R.string.account_admin_only)
            true -> {}
        }
        val view = KidView(ServerConfig(own.url, kid.username, kid.password, kidOf = own.username), graph)
        try {
            view.kids.refresh()
        } catch (e: Exception) {
            view.close()
            if (e is CancellationException) throw e
            return graph.app.getString(R.string.account_view_failed, kid.username, e.message ?: e.javaClass.simpleName)
        }
        if (graph.settings.server.value != own) { view.close(); return graph.app.getString(R.string.account_someone_else) }
        graph.kidView.getAndUpdate { view }?.close()
        return null
    }

    /** Playback stops and sync pauses; downloaded files are kept. On the main thread. */
    fun signOut(graph: Graph) {
        graph.player.stopAndClear()
        graph.sync.cancelAll()
        graph.live.reset()
        graph.library.clear()
        graph.bad.clear()
        graph.lyricsOffsets.clear()
        graph.kids.cleanOff()
        graph.client.clearCache()
        graph.settings.clearServer()
    }

    fun needsLanPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED
}
