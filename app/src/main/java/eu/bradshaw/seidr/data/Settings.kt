package eu.bradshaw.seidr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

data class ServerConfig(val url: String, val username: String, val password: String)

/**
 * App preferences in plain SharedPreferences (app-private storage). The
 * password has to be stored recoverably because Subsonic token auth hashes it
 * with a fresh salt on every request.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _server = MutableStateFlow(readServer())
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifi_only", true))
    /** Downloads only run on unmetered networks while this is set (the default). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _theme = MutableStateFlow(prefs.getString("theme", "system")!!)
    /** Colour theme key (see `ThemeChoice`): "system", "neutral" or a colour name. */
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _homePlaylistsAlpha = MutableStateFlow(prefs.getBoolean("home_playlists_alpha", false))
    /** Home's "Playlists" shelf: A–Z when set, otherwise most recently played first. */
    val homePlaylistsAlpha: StateFlow<Boolean> = _homePlaylistsAlpha.asStateFlow()

    private val _libraryGrid = MutableStateFlow(prefs.getBoolean("library_grid", false))
    /** Library shows a grid of covers rather than a list. */
    val libraryGrid: StateFlow<Boolean> = _libraryGrid.asStateFlow()

    private val _autoUpdate = MutableStateFlow(prefs.getBoolean("auto_update", true))
    /** Check GitHub for a newer release when the app comes to the front (`Updater`). */
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    private val _badReports = MutableStateFlow(prefs.getBoolean("bad_reports", false))
    /** Opt-in: use the server's "Bad: " playlists (see `BadReports`). */
    val badReports: StateFlow<Boolean> = _badReports.asStateFlow()

    private val _recentSearches = MutableStateFlow(readRecent())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Fixed per install so cover-art URLs are stable and Coil's disk cache can key on them. */
    val coverSalt: String = prefs.getString("cover_salt", null) ?: UUID.randomUUID().toString().take(12).also {
        prefs.edit().putString("cover_salt", it).apply()
    }

    var batteryPromptShown: Boolean
        get() = prefs.getBoolean("battery_prompt_shown", false)
        set(v) { prefs.edit().putBoolean("battery_prompt_shown", v).apply() }

    private fun readServer(): ServerConfig? {
        val url = prefs.getString("server_url", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val pass = prefs.getString("password", null) ?: return null
        return ServerConfig(url, user, pass)
    }

    fun saveServer(config: ServerConfig) {
        prefs.edit()
            .putString("server_url", config.url)
            .putString("username", config.username)
            .putString("password", config.password)
            .apply()
        _server.value = config
    }

    fun clearServer() {
        prefs.edit().remove("server_url").remove("username").remove("password").apply()
        _server.value = null
    }

    fun setWifiOnly(value: Boolean) {
        prefs.edit().putBoolean("wifi_only", value).apply()
        _wifiOnly.value = value
    }

    fun setTheme(key: String) {
        prefs.edit().putString("theme", key).apply()
        _theme.value = key
    }

    fun setHomePlaylistsAlpha(value: Boolean) {
        prefs.edit().putBoolean("home_playlists_alpha", value).apply()
        _homePlaylistsAlpha.value = value
    }

    fun setLibraryGrid(value: Boolean) {
        prefs.edit().putBoolean("library_grid", value).apply()
        _libraryGrid.value = value
    }

    fun setAutoUpdate(value: Boolean) {
        prefs.edit().putBoolean("auto_update", value).apply()
        _autoUpdate.value = value
    }

    fun setBadReports(value: Boolean) {
        prefs.edit().putBoolean("bad_reports", value).apply()
        _badReports.value = value
    }

    private fun readRecent(): List<String> =
        prefs.getString("recent_searches", null)?.let {
            runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: emptyList()

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = (listOf(q) + _recentSearches.value.filterNot { it.equals(q, ignoreCase = true) }).take(15)
        prefs.edit().putString("recent_searches", Json.encodeToString(list)).apply()
        _recentSearches.value = list
    }

    fun clearRecentSearches() {
        prefs.edit().remove("recent_searches").apply()
        _recentSearches.value = emptyList()
    }

    companion object {
        /** Accepts "192.168.1.20:4533" or a full URL; strips trailing slashes and a trailing /rest. */
        fun normaliseUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (u.endsWith("/rest")) u = u.removeSuffix("/rest")
            return u
        }
    }
}
