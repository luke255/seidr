package eu.bradshaw.seidr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A login. [kidOf] marks a kid's account, signed in through Connect by the
 * grown-up named there: the app then shows and plays only what that person's
 * "Seidr: Kids" playlists allow (see `Kids`).
 */
data class ServerConfig(val url: String, val username: String, val password: String, val kidOf: String? = null) {
    val isKid: Boolean get() = kidOf != null
}

/** A kid's login, kept by a grown-up's device purely to sign other devices in with. */
@kotlinx.serialization.Serializable
data class KidLogin(val username: String, val password: String)

/**
 * App preferences in SharedPreferences (app-private storage). The password
 * has to be stored recoverably because Subsonic token auth hashes it with a
 * fresh salt on every request, so it and the kids' logins are encrypted under
 * a Keystore key ([Secrets]).
 */
class Settings(context: Context) {
    /** Backed up and moved to a new phone (`data_extraction_rules.xml`), so nothing secret or device-specific. */
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** This device's own flags: never backed up. */
    private val device = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val secrets = Secrets(context, legacy = prefs)

    /** The Keystore wouldn't encrypt a login, so it's stored plain (see [Secrets]). */
    val secretsUnprotected: StateFlow<Boolean> = Secrets.unprotected

    private val _server = MutableStateFlow(readServer())
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifi_only", true))
    /** Downloads only run on unmetered networks while this is set (the default). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mobileKbps = MutableStateFlow(prefs.getInt("mobile_kbps", 0))
    /** Streaming on metered networks: an MP3 bitrate, or 0 for the original file (the default). */
    val mobileKbps: StateFlow<Int> = _mobileKbps.asStateFlow()

    private val _theme = MutableStateFlow(run {
        // Before themes were per login, one "theme" served everyone: it's the signed-in login's.
        prefs.getString("theme", null)?.let { old ->
            _server.value?.let { prefs.edit().putString(themeKey(it.username), old).apply() }
            prefs.edit().remove("theme").apply()
        }
        readTheme(_server.value?.username)
    })
    /**
     * Colour theme key (see `ThemeChoice`): "system", "neutral" or a colour
     * name. Each login keeps its own; signed out, it's always Red.
     */
    val theme: StateFlow<String> = _theme.asStateFlow()

    private fun themeKey(username: String) = "theme:" + username.trim().lowercase()

    private fun readTheme(username: String?): String =
        username?.let { prefs.getString(themeKey(it), null) } ?: if (username == null) SIGNED_OUT_THEME else "system"

    private val _playlistsSort = MutableStateFlow(
        // Before Home and Library shared one, Home's was a choice of A–Z or recent.
        PlaylistSort.of(prefs.getString("playlists_sort", null),
            if (prefs.getBoolean("home_playlists_alpha", false)) PlaylistSort.NAME else PlaylistSort.RECENT)
    )
    /** How playlists are ordered, on Home's shelves and in Library alike. */
    val playlistsSort: StateFlow<PlaylistSort> = _playlistsSort.asStateFlow()

    private val _albumSort = MutableStateFlow(AlbumSort.of(prefs.getString("album_sort", null)))
    /** Library's Albums order. */
    val albumSort: StateFlow<AlbumSort> = _albumSort.asStateFlow()

    private val _artistSort = MutableStateFlow(ArtistSort.of(prefs.getString("artist_sort", null)))
    /** Library's Artists order. */
    val artistSort: StateFlow<ArtistSort> = _artistSort.asStateFlow()

    private val _libraryGrid = MutableStateFlow(prefs.getBoolean("library_grid", false))
    /** Library shows a grid of covers rather than a list. */
    val libraryGrid: StateFlow<Boolean> = _libraryGrid.asStateFlow()

    private val _libraryShared = MutableStateFlow(prefs.getBoolean("library_shared", false))
    /** Library's playlists include other people's (shared with everyone, or an admin's view of private ones). */
    val libraryShared: StateFlow<Boolean> = _libraryShared.asStateFlow()

    private val _playlistSorts = MutableStateFlow(
        prefs.getString("playlist_sorts", null)
            ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
    )
    /** Each playlist's chosen track order (id → [TrackSort.key]); a playlist missing here shows its own order. */
    val playlistSorts: StateFlow<Map<String, String>> = _playlistSorts.asStateFlow()

    private val _autoUpdate = MutableStateFlow(prefs.getBoolean("auto_update", true))
    /** Check GitHub for a newer release when the app comes to the front (`Updater`). */
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    private val _badReports = MutableStateFlow(prefs.getBoolean("bad_reports", false))
    /** Opt-in: use the server's "Bad: " playlists (see `BadReports`). */
    val badReports: StateFlow<Boolean> = _badReports.asStateFlow()

    private val _othersPrivatePlaylists = MutableStateFlow(prefs.getBoolean("others_private_playlists", false))
    /**
     * Opt-in: list other users' private playlists. The server gives them to
     * admins, but they're someone else's, so `Library` leaves them out by default.
     */
    val othersPrivatePlaylists: StateFlow<Boolean> = _othersPrivatePlaylists.asStateFlow()

    private val _kidLogins = MutableStateFlow(readKids())
    /** Kids' logins this (grown-up) device can sign other devices in as. */
    val kidLogins: StateFlow<List<KidLogin>> = _kidLogins.asStateFlow()

    private val _recentSearches = MutableStateFlow(readRecent())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Fixed per install so cover-art URLs are stable and Coil's disk cache can key on them. */
    val coverSalt: String = prefs.getString("cover_salt", null) ?: UUID.randomUUID().toString().take(12).also {
        prefs.edit().putString("cover_salt", it).apply()
    }

    /** Per device: a restored or transferred install still has to ask its new phone. */
    var batteryPromptShown: Boolean
        get() = device.getBoolean("battery_prompt_shown", false) || prefs.getBoolean("battery_prompt_shown", false)
        set(v) {
            device.edit().putBoolean("battery_prompt_shown", v).apply()
            prefs.edit().remove("battery_prompt_shown").apply()
        }

    /**
     * The server and username of the last login, kept while signed out without
     * a password: after a restore from backup (secrets aren't backed up) or a
     * lost Keystore key. Login starts from them.
     */
    val lastLogin: Pair<String, String>?
        get() {
            val url = prefs.getString("server_url", null) ?: return null
            val user = prefs.getString("username", null) ?: return null
            return url to user
        }

    private fun readServer(): ServerConfig? {
        val url = prefs.getString("server_url", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val pass = secrets.read("password") ?: return null
        return ServerConfig(url, user, pass, prefs.getString("kid_of", null))
    }

    fun saveServer(config: ServerConfig) {
        val edit = prefs.edit()
            .putString("server_url", config.url)
            .putString("username", config.username)
        if (config.kidOf != null) edit.putString("kid_of", config.kidOf) else edit.remove("kid_of")
        edit.apply()
        secrets.write("password", config.password)
        _server.value = config
        _theme.value = readTheme(config.username)
    }

    fun clearServer() {
        prefs.edit().remove("server_url").remove("username").remove("kid_of").apply()
        secrets.write("password", null)
        secrets.write("kid_logins", null)
        _server.value = null
        _kidLogins.value = emptyList()
        _theme.value = SIGNED_OUT_THEME
    }

    fun setWifiOnly(value: Boolean) {
        prefs.edit().putBoolean("wifi_only", value).apply()
        _wifiOnly.value = value
    }

    fun setMobileKbps(kbps: Int) {
        prefs.edit().putInt("mobile_kbps", kbps).apply()
        _mobileKbps.value = kbps
    }

    /** Whether [url]'s server can convert for streaming (see `StreamQuality`); null if not known yet. */
    fun transcodeSupport(url: String): Boolean? =
        if (device.contains("transcode:$url")) device.getBoolean("transcode:$url", false) else null

    fun setTranscodeSupport(url: String, ok: Boolean) { device.edit().putBoolean("transcode:$url", ok).apply() }

    fun setTheme(key: String) {
        val user = _server.value?.username ?: return
        prefs.edit().putString(themeKey(user), key).apply()
        _theme.value = key
    }

    fun setPlaylistsSort(sort: PlaylistSort) {
        prefs.edit().putString("playlists_sort", sort.key).remove("home_playlists_alpha").apply()
        _playlistsSort.value = sort
    }

    fun setAlbumSort(sort: AlbumSort) {
        prefs.edit().putString("album_sort", sort.key).apply()
        _albumSort.value = sort
    }

    fun setArtistSort(sort: ArtistSort) {
        prefs.edit().putString("artist_sort", sort.key).apply()
        _artistSort.value = sort
    }

    fun setLibraryGrid(value: Boolean) {
        prefs.edit().putBoolean("library_grid", value).apply()
        _libraryGrid.value = value
    }

    fun setLibraryShared(value: Boolean) {
        prefs.edit().putBoolean("library_shared", value).apply()
        _libraryShared.value = value
    }

    fun setPlaylistSort(id: String, sort: TrackSort) {
        val next = if (sort == TrackSort.CUSTOM) _playlistSorts.value - id else _playlistSorts.value + (id to sort.key)
        prefs.edit().putString("playlist_sorts", Json.encodeToString(next)).apply()
        _playlistSorts.value = next
    }

    fun setAutoUpdate(value: Boolean) {
        prefs.edit().putBoolean("auto_update", value).apply()
        _autoUpdate.value = value
    }

    fun setBadReports(value: Boolean) {
        prefs.edit().putBoolean("bad_reports", value).apply()
        _badReports.value = value
    }

    fun setOthersPrivatePlaylists(value: Boolean) {
        prefs.edit().putBoolean("others_private_playlists", value).apply()
        _othersPrivatePlaylists.value = value
    }

    private fun readKids(): List<KidLogin> =
        secrets.read("kid_logins")?.let {
            runCatching { Json.decodeFromString<List<KidLogin>>(it) }.getOrNull()
        } ?: emptyList()

    /** Adds or replaces (by username) a kid's login. */
    fun saveKid(kid: KidLogin) {
        val list = _kidLogins.value.filterNot { it.username.equals(kid.username, ignoreCase = true) } + kid
        secrets.write("kid_logins", Json.encodeToString(list))
        _kidLogins.value = list
    }

    fun removeKid(username: String) {
        val list = _kidLogins.value.filterNot { it.username.equals(username, ignoreCase = true) }
        secrets.write("kid_logins", Json.encodeToString(list))
        _kidLogins.value = list
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
        /** `ThemeChoice.RED`: signed out (sign-in, a TV waiting for a guest) is always the brand colour. */
        const val SIGNED_OUT_THEME = "red"
        /** Accepts "192.168.1.20:4533" or a full URL; strips trailing slashes and a trailing /rest. */
        fun normaliseUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (u.endsWith("/rest")) u = u.removeSuffix("/rest")
            return u
        }
    }
}
