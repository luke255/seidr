package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.NowPlayingEntry
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one device is playing, as it tells everyone else: an entry in its
 * user's status list (see [ListeningRules]). Times are the device's clock.
 */
@Serializable
data class DeviceStatus(
    @SerialName("n") val name: String,
    /** A Connect `Peer.KIND_…`. */
    @SerialName("k") val kind: String,
    /** Where the sound goes, when it isn't the device's own speaker or screen. */
    @SerialName("o") val output: Output? = null,
    @SerialName("s") val song: String? = null,
    /** The playlist it's playing from, if it is one. Only its id: a private one's name isn't shared. */
    @SerialName("p") val playlist: String? = null,
    @SerialName("st") val state: String = STOP,
    /** Milliseconds into the song at [at]. */
    @SerialName("pos") val position: Long = 0,
    @SerialName("at") val at: Long = 0,
    /** When it stops counting: the song's end while playing (a device that vanishes drops out), or a while after a pause. */
    @SerialName("u") val until: Long = 0,
) {
    companion object {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val STOP = "stop"
    }
}

/**
 * [kind] is one of [ListeningRules]' `OUT_…`, or for a Cast device its Connect
 * `Peer.KIND_…` (speaker, group, tv), with its [name].
 */
@Serializable
data class Output(@SerialName("k") val kind: String, @SerialName("n") val name: String? = null)

/** A status list's comment: each of the user's devices, by Connect device id. */
@Serializable
data class StatusList(val v: Int = 1, val devices: Map<String, DeviceStatus> = emptyMap())

/** Someone else listening, from Seiðr's status lists or the server's now-playing list. */
data class Listener(
    /** Their username, as the server has it. */
    val user: String,
    val songId: String,
    /** The song's details, once known. */
    val song: Song?,
    /** The device's name; for another app, the app's (its player name). */
    val device: String,
    /** The device's Connect kind; null for another app. */
    val deviceKind: String?,
    val output: Output?,
    val playlistId: String?,
    val paused: Boolean,
    /** Milliseconds into the song at [at]; null when the server's list is all there is. */
    val position: Long?,
    val at: Long,
) {
    val isApp: Boolean get() = deviceKind == null

    /** How far into the song they are now. */
    fun positionAt(now: Long): Long? = position?.let { if (paused) it else it + (now - at).coerceAtLeast(0) }
}

/**
 * The rules for sharing what's playing (pure, tested). Navidrome's
 * `getNowPlaying` gives each user's song but no device, playlist or pause,
 * so each Seiðr user who shares keeps a public playlist, [NAME], whose comment
 * is a [StatusList]. Everyone's comes in with the playlist listing. Its songs
 * are never used; `Library` hides it.
 */
object ListeningRules {
    const val NAME = "Seidr: Listening"

    /** Seiðr's client name (`c`), which the server's now-playing list reports as the player. */
    const val CLIENT = "seidr"

    const val OUT_HEADPHONES = "headphones"
    const val OUT_BLUETOOTH = "bluetooth"
    const val OUT_CAR = "car"

    /** A paused device stays listed this long. */
    const val PAUSED_MS = 10 * 60_000L
    /** Room for a slow next track, past the end of the current one. */
    const val GRACE_MS = 60_000L
    /** A device's entry is dropped from its list once it's been over this long. */
    const val KEEP_MS = 24 * 60 * 60_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun isStatusList(name: String) = name.trim() == NAME

    fun parse(comment: String?): StatusList? =
        comment?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(StatusList.serializer(), it) }.getOrNull() }

    fun encode(list: StatusList): String = json.encodeToString(StatusList.serializer(), list)

    /** How long [state] counts for, from [now]: the rest of the song while playing. */
    fun until(state: String, now: Long, positionMs: Long, durationMs: Long): Long = when (state) {
        DeviceStatus.PLAY -> now + (if (durationMs > 0) (durationMs - positionMs).coerceAtLeast(0) else PAUSED_MS) + GRACE_MS
        DeviceStatus.PAUSE -> now + PAUSED_MS
        else -> now
    }

    /** [list] with this device's entry set to [status] (or removed, for null), and long-gone ones dropped. */
    fun withDevice(list: StatusList?, deviceId: String, status: DeviceStatus?, now: Long): StatusList {
        val kept = list?.devices.orEmpty().filter { (id, s) -> id != deviceId && s.until > now - KEEP_MS }
        return StatusList(devices = if (status == null) kept else kept + (deviceId to status))
    }

    /**
     * Everyone else listening at [now], playing first. [lists] is the playlist
     * listing (status lists are found in it by name, owned by whoever they
     * describe); [server] is the server's now-playing list, which adds other
     * apps. A Seiðr device's report there is left out when its user's status
     * list is newer: that covers it, pause and stop included.
     */
    fun listeners(me: String?, lists: List<Playlist>, server: List<NowPlayingEntry>, now: Long): List<Listener> {
        val self = me?.trim()?.lowercase()
        fun mine(user: String) = user.trim().lowercase() == self
        val statuses = lists.filter { isStatusList(it.name) && it.owner != null && !mine(it.owner) }
            .groupBy { it.owner!!.trim().lowercase() }
        val out = mutableListOf<Listener>()
        val latest = mutableMapOf<String, Long>()
        for ((user, own) in statuses) {
            val owner = own.first().owner!!.trim()
            for (pl in own) {
                for ((_, s) in parse(pl.comment)?.devices.orEmpty()) {
                    latest[user] = maxOf(latest[user] ?: 0L, s.at)
                    val song = s.song ?: continue
                    if (s.state == DeviceStatus.STOP || s.until <= now) continue
                    out += Listener(owner, song, null, s.name, s.kind, s.output, s.playlist, s.state == DeviceStatus.PAUSE, s.position, s.at)
                }
            }
        }
        for (e in server) {
            if (e.username.isBlank() || mine(e.username)) continue
            val reported = now - e.minutesAgo * 60_000L
            // The server keeps an entry long after the song's over; minutes are all it says.
            val length = if (e.duration > 0) e.duration * 1000L else PAUSED_MS
            if (reported + length + GRACE_MS < now - 60_000L) continue
            val seidr = e.playerName.equals(CLIENT, ignoreCase = true)
            if (seidr && (latest[e.username.trim().lowercase()] ?: -1L) >= reported - 60_000L) continue
            out += Listener(e.username.trim(), e.id, e.song(), e.playerName?.takeIf { it.isNotBlank() } ?: CLIENT,
                null, null, null, paused = false, position = null, at = reported)
        }
        return out.sortedWith(compareBy<Listener> { it.paused }.thenByDescending { it.at })
    }

    /** What a Home card stands for: the playlist they're playing from, else the song's album. */
    sealed interface Context {
        data class OfPlaylist(val id: String) : Context
        data class OfAlbum(val id: String) : Context
        data class OfSong(val id: String) : Context
    }

    /**
     * Listeners grouped by what they're playing, in [listeners]' order. A
     * playlist counts only if it's in [playlists] (the ones this login may
     * see and open); otherwise the card is the album.
     */
    fun contexts(listeners: List<Listener>, playlists: Set<String>): List<Pair<Context, List<Listener>>> =
        listeners.groupBy { l ->
            l.playlistId?.takeIf { it in playlists }?.let { Context.OfPlaylist(it) }
                ?: l.song?.albumId?.let { Context.OfAlbum(it) }
                ?: Context.OfSong(l.songId)
        }.toList()
}
