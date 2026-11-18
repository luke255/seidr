package eu.bradshaw.seidr.data

import android.content.Context
import android.util.Log
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Kid mode: while the login (signed in, or a guest's) is a kid's, this holds
 * the [KidRules] everything else filters by. Null rules mean "not a kid".
 *
 * The lists come from the server's three "Seidr: Kids" playlists, trusted only
 * when owned by the grown-up who signed the kid in ([ServerConfig.kidOf]), so
 * a kid can't make their own. A list that isn't there counts as empty, and
 * before the first fetch everything is empty: it fails closed. Each fetch is
 * strict and complete, and is saved, so offline the last one stands.
 */
class Kids(
    /** The login in use right now: signed in, else the guest's. */
    private val loginNow: () -> ServerConfig?,
    logins: Flow<ServerConfig?>,
    private val api: () -> SubsonicApi,
    private val dir: File,
    private val scope: CoroutineScope,
    private val context: Context,
    http: okhttp3.OkHttpClient,
) {
    /** Navidrome's own API as the kid, for which playlists have pictures. */
    private val native = NavidromeApi(http, loginNow)

    private class Loaded(val key: String, val rules: KidRules)

    @Volatile private var loaded: Loaded? = null
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _rules = MutableStateFlow<KidRules?>(null)
    /** For reacting to changes; read [current] when deciding anything. */
    val rules: StateFlow<KidRules?> = _rules.asStateFlow()

    init {
        scope.launch {
            logins.collect {
                _rules.value = current
                if (it?.isKid == true) runCatching { refresh() }.onFailure { e -> Log.w(TAG, "refresh failed: $e") }
            }
        }
    }

    /** True while the login in use is a kid's. */
    val isKid: Boolean get() = loginNow()?.isKid == true

    /**
     * The rules for the login in use, or null if it isn't a kid's. Read on
     * every decision, so a new login is filtered from its first moment, before
     * any flow has caught up.
     */
    val current: KidRules?
        get() {
            val c = loginNow()?.takeIf { it.isKid } ?: return null
            val key = keyOf(c)
            loaded?.takeIf { it.key == key }?.let { return it.rules }
            val rules = KidRules(read(key) ?: KidLists())
            loaded = Loaded(key, rules)
            return rules
        }

    /**
     * Fetches the three lists again (strict: throws rather than use a cached
     * copy, and keeps what it had). Returns the new rules, or null if the
     * login isn't a kid's.
     */
    suspend fun refresh(): KidRules? = lock.withLock {
        val c = loginNow()?.takeIf { it.isKid } ?: return null
        val owner = c.kidOf!!
        val key = keyOf(c)
        val listing = api().getPlaylists().ok().playlists?.playlist.orEmpty()
        val trusted = KidRules.NAMES.associateWith { name -> listing.filter { KidRules.trusted(it, name, owner) } }
        val versions = trusted.values.flatten().associate { it.id to "${it.changed}|${it.songCount}" }
        // The lists run to thousands of songs (megabytes each), so they're
        // fetched again only when the server says one changed.
        val saved = read(key)
        val lists = if (saved != null && saved.versions == versions && saved.playlistIds.toSet() == versions.keys) saved
        else coroutineScope {
            suspend fun fetch(name: String): List<KidSong> {
                val out = LinkedHashMap<String, KidSong>()
                val fulls = trusted.getValue(name).map { p -> async { api().getPlaylist(p.id).ok().playlist ?: throw IOException("no playlist in response") } }
                for (full in fulls.awaitAll()) {
                    if (full.entry.size != full.songCount) throw IOException("$name incomplete (${full.entry.size} of ${full.songCount})")
                    full.entry.forEach { s: Song -> out.putIfAbsent(s.id, KidRules.kidSong(s)) }
                }
                return out.values.toList()
            }
            val (approved, titles, art) = listOf(KidRules.APPROVED, KidRules.SAFE_TITLES, KidRules.SAFE_ART)
                .map { async { fetch(it) } }.awaitAll()
            KidLists(approved, titles, art, versions.keys.toList(), versions)
        }
        // Pictures are one small call, so they're checked every time. Only the grown-up's
        // playlists and the kid's own count. Unreachable keeps what we had; a server without them has none.
        val pictures = try {
            val trusted = setOf(owner.trim().lowercase(), c.username.trim().lowercase())
            native.playlistPictures().orEmpty().filterValues { it.trim().lowercase() in trusted }.keys.sorted()
        } catch (e: NotNavidromeException) {
            emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "pictures: $e")
            (saved ?: lists).pictures
        }
        val withPictures = if (pictures == lists.pictures) lists else lists.copy(pictures = pictures)
        // Signed out or in as someone else meanwhile: these lists aren't theirs.
        if (loginNow() != c) return null
        if (withPictures !== saved) write(key, withPictures)
        // Unchanged lists keep the rules already loaded, so nothing downstream redoes its filtering.
        val rules = loaded?.takeIf { it.key == key && withPictures === saved }?.rules ?: KidRules(withPictures)
        loaded = Loaded(key, rules)
        _rules.value = rules
        Log.i(TAG, "lists${if (withPictures === saved) " (unchanged)" else ""}: ${lists.approved.size} approved, ${lists.titles.size} titles, ${lists.art.size} art, ${pictures.size} pictures")
        rules
    }

    /**
     * A grown-up adding a kid: makes sure the three lists exist under their
     * own login, and are public so the kid can read them. Creates only what
     * two fresh fetches agree is missing, like `BadReports`. Returns an error
     * message, or null.
     */
    suspend fun ensureLists(): String? = lock.withLock {
        val me = loginNow()?.takeIf { !it.isKid } ?: return context.getString(R.string.kids_signed_in_as_kid)
        suspend fun mine() = api().getPlaylists().ok().playlists?.playlist.orEmpty()
            .filter { p -> KidRules.NAMES.any { KidRules.trusted(p, it, me.username) } }
        return try {
            val first = mine()
            val missing = KidRules.NAMES.filter { n -> first.none { it.name.trim() == n } }
                .intersect(mine().let { again -> KidRules.NAMES.filter { n -> again.none { it.name.trim() == n } }.toSet() })
            for (name in missing) {
                val id = api().createPlaylist(name, emptyList()).ok().playlist?.id ?: throw IOException("no id for $name")
                api().updatePlaylist(id, public = true).ok()
                Log.i(TAG, "created $name")
            }
            for (p in first.filter { !it.public }) api().updatePlaylist(p.id, public = true).ok()
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            context.getString(R.string.kids_setup_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun keyOf(c: ServerConfig) =
        SubsonicClient.md5("${c.url}|${c.username.trim().lowercase()}|${c.kidOf?.trim()?.lowercase()}")

    private fun file(key: String) = File(dir, "$key.json")

    private fun read(key: String): KidLists? =
        runCatching { json.decodeFromString<KidLists>(file(key).readText()) }.getOrNull()

    private fun write(key: String, lists: KidLists) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(json.encodeToString(KidLists.serializer(), lists))
            tmp.renameTo(file(key))
        }.onFailure { Log.w(TAG, "couldn't save lists: $it") }
    }

    companion object { private const val TAG = "Kids" }
}
