package eu.bradshaw.seidr.data

import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.NowPlayingEntry
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.SubsonicException
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Listening now: what other people on the server are playing, and telling
 * them what this device plays (rules in [ListeningRules]). On unless the
 * user opts out (Settings → Library). A kid's login always shares (it has no
 * switch: their grown-ups see what they play); a guest's never does.
 * Opting out also stops the server's own now-playing reports.
 */
class Listening(private val graph: Graph) {
    /** This device shares: signed in, and a kid or not opted out. */
    val sharing: StateFlow<Boolean> =
        combine(graph.settings.shareListening, graph.settings.server) { on, s -> s != null && (s.isKid || on) }
            .stateIn(graph.scope, SharingStarted.Eagerly, false)

    /**
     * Whether to tell the server what's playing now (Navidrome's list, and any
     * scrobbling service it forwards to). A guest's plays keep doing so: it's
     * their login, not this device's owner's.
     */
    fun reportsNowPlaying(): Boolean = graph.settings.server.value == null || sharing.value

    private val serverNow = MutableStateFlow<List<NowPlayingEntry>>(emptyList())
    private val tick = MutableStateFlow(0L)
    private val poke = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fetch again soon (a status list changed, say). */
    fun refreshSoon() { poke.tryEmit(Unit) }

    /**
     * Everyone else listening, playing first, with each song's details. Polled
     * while something on screen shows it; empty offline or signed out.
     */
    val others: StateFlow<List<Listener>> = channelFlow {
        launch {
            while (isActive) {
                refresh()
                tick.value = System.currentTimeMillis()
                withTimeoutOrNull(POLL_MS) { poke.first() }
            }
        }
        combine(graph.library.allPlaylists, serverNow, tick) { lists, now, _ -> lists to now }
            .collectLatest { (lists, now) -> send(build(lists.orEmpty(), now)) }
    }.stateIn(graph.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun refresh() {
        if (graph.settings.server.value == null || !graph.status.online.value) { serverNow.value = emptyList(); return }
        runCatching { graph.library.fetchAllPlaylists() }
        serverNow.value = try {
            graph.client.strict.getNowPlaying().ok().nowPlaying?.entry.orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun build(lists: List<eu.bradshaw.seidr.data.api.Playlist>, server: List<NowPlayingEntry>): List<Listener> {
        val login = graph.settings.server.value ?: return emptyList()
        if (!graph.status.online.value) return emptyList()
        // A kid (or an admin browsing as one) sees only what they may play.
        val kid = graph.browseKids.current
        return ListeningRules.listeners(login.username, lists, server, System.currentTimeMillis()).mapNotNull { l ->
            if (kid != null && !kid.playable(l.songId)) return@mapNotNull null
            l.copy(song = l.song ?: song(l.songId) ?: return@mapNotNull null)
        }
    }

    private val songs = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) = size > 200
    }

    private suspend fun song(id: String): Song? {
        synchronized(songs) { songs[id] }?.let { return it }
        return graph.library.song(id)?.also { s -> synchronized(songs) { songs[id] = s } }
    }

    /** A status list's id (anyone's), as the listing has it: their edits aren't playlist changes to act on. */
    fun isStatusList(id: String): Boolean =
        id == statusId || graph.library.allPlaylists.value.orEmpty().any { it.id == id && ListeningRules.isStatusList(it.name) }

    // --- This device's status ---------------------------------------------------

    private val writeLock = Mutex()
    /** This user's status list, once found or made. */
    @Volatile private var statusId: String? = null
    /** What this device last wrote; null before the first write. */
    private var sent: DeviceStatus? = null
    /** The latest wanted, kept to retry once the server's back. */
    @Volatile private var wanted: DeviceStatus? = null

    init {
        graph.scope.launch {
            graph.status.online.drop(1).collect { online -> if (online) wanted?.let { publish(it) } }
        }
        // Opted out (or signed out): take this device off the list.
        graph.scope.launch {
            graph.settings.shareListening.drop(1).collect { on -> if (!on) withdraw() }
        }
        graph.scope.launch {
            graph.settings.server.drop(1).collect { writeLock.withLock { statusId = null; sent = null; wanted = null } }
        }
    }

    /** Tells everyone this device's state. Same song, same state and a position where it's expected: nothing's sent. */
    suspend fun publish(status: DeviceStatus) {
        if (!sharing.value) return
        wanted = status
        if (!graph.status.online.value) return
        writeLock.withLock {
            if (unchanged(sent, status)) return
            val login = graph.settings.server.value ?: return
            // Nothing played yet (a queue restored at start, say) is no reason to make a list.
            val create = status.state != DeviceStatus.STOP || sent != null
            if (write(login.username, create) { list, now -> ListeningRules.withDevice(list, graph.connect.deviceId, status, now) }) sent = status
        }
    }

    private fun unchanged(a: DeviceStatus?, b: DeviceStatus): Boolean {
        if (a == null) return false
        fun key(s: DeviceStatus) = s.copy(position = 0, at = 0, until = 0)
        if (key(a) != key(b)) return false
        val expected = if (a.state == DeviceStatus.PLAY) a.position + (b.at - a.at) else a.position
        return b.state == DeviceStatus.STOP || abs(b.position - expected) < 5_000
    }

    /** Removes this device from the list; the list goes once no device is on it. */
    private suspend fun withdraw() = writeLock.withLock {
        wanted = null
        val login = graph.settings.server.value ?: return@withLock
        if (write(login.username, create = false, removeIfEmpty = true) { list, now -> ListeningRules.withDevice(list, graph.connect.deviceId, null, now) }) sent = null
    }

    /** Read, change, write back; [create] the list if there's none. True once the server has it (or there's no list to change). */
    private suspend fun write(me: String, create: Boolean, removeIfEmpty: Boolean = false, change: (StatusList?, Long) -> StatusList): Boolean {
        val api = graph.client.strict
        return try {
            for (attempt in 0..1) {
                val id = statusId ?: (if (create) findOrCreate(api, me) else find(api, me)) ?: return true
                val pl = try {
                    api.getPlaylist(id).ok().playlist
                } catch (e: SubsonicException) {
                    if (e.code == NOT_FOUND) null else throw e
                }
                if (pl == null) { statusId = null; continue }
                statusId = id
                val list = change(ListeningRules.parse(pl.comment), System.currentTimeMillis())
                if (removeIfEmpty && list.devices.isEmpty()) {
                    api.deletePlaylist(id).ok()
                    statusId = null
                } else {
                    api.updatePlaylist(id, comment = ListeningRules.encode(list), public = if (pl.public) null else true).ok()
                }
                return true
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("Listening", "couldn't update the status list: $e")
            false
        }
    }

    /** The user's status list. Two devices making one at once leaves two: the oldest is kept. */
    private suspend fun find(api: SubsonicApi, me: String): String? {
        val own = api.getPlaylists().ok().playlists?.playlist.orEmpty()
            .filter { ListeningRules.isStatusList(it.name) && it.owner.equals(me, ignoreCase = true) }
            .sortedBy { it.created.orEmpty() }
        for (extra in own.drop(1)) runCatching { api.deletePlaylist(extra.id).ok() }
        return own.firstOrNull()?.id
    }

    private suspend fun findOrCreate(api: SubsonicApi, me: String): String? =
        find(api, me) ?: api.createPlaylist(ListeningRules.NAME, emptyList()).ok().playlist?.id

    fun clear() {
        serverNow.value = emptyList()
        synchronized(songs) { songs.clear() }
    }

    private companion object {
        const val POLL_MS = 30_000L
        /** Subsonic's "the requested data was not found". */
        const val NOT_FOUND = 70
    }
}
