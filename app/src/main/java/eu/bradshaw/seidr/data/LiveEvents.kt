package eu.bradshaw.seidr.data

import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Listens to Navidrome's own event stream (`/api/events`, what its web UI
 * uses) while the app is on screen, so changes made elsewhere show up live:
 * playlist edits, likes, and new music after a library scan.
 *
 * Events are only hints. Each one triggers the normal sync or a screen reload;
 * nothing here decides a deletion. This is Navidrome's native API, not
 * Subsonic, so any failure just backs off quietly and the periodic sync carries on.
 */
class LiveEvents(private val graph: Graph, private val scope: CoroutineScope) {
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    // Keep-alives arrive every ~15 s, so a minute of silence means the stream is dead.
    private val http = graph.baseHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { poll() }
            run()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Catches up on coming back on screen (the stream doesn't replay what was
     * missed), then asks again every minute: Navidrome sends no event when a
     * playlist is created or deleted, and the stream may be down. One playlist
     * listing; [Library.fetchAllPlaylists] announces whatever changed.
     *
     * A kid's lists belong to their grown-up, and an edit made in Navidrome's web UI
     * is only announced to its own login, so a kid's stream may never hear of it:
     * those are checked here too, with the lists themselves fetched only when one
     * changed. New rules reach the screens through [Graph.Change.KIDS]; a sync then
     * downloads to match.
     */
    private suspend fun poll() {
        while (scope.isActive) {
            if (graph.status.online.value) runCatching { graph.library.refreshPlaylists() }
            if (graph.kids.isKid) {
                val before = graph.kids.rules.value
                val after = runCatching { graph.kids.refresh() }.getOrNull()
                if (after != null && after !== before) { Log.i("Live", "kids' lists changed (polled)"); graph.sync.request() }
            } else if (graph.kids.clean.value) runCatching { graph.kids.refreshClean() }
            delay(60_000)
        }
    }

    /** Sign-out: forget the session token too. */
    fun reset() {
        stop()
        graph.navidrome.forget()
    }

    private suspend fun run() {
        var backoff = 5_000L
        while (scope.isActive) {
            val server = graph.settings.server.value ?: return
            // No point hammering a server we can't reach; wait until we're back online.
            graph.status.online.first { it }
            val started = System.currentTimeMillis()
            val result = runCatching { listen(server) }
            if (System.currentTimeMillis() - started > 60_000) backoff = 5_000L
            val e = result.exceptionOrNull()
            if (e is NotNavidromeException) { Log.i("Live", "no event stream on this server"); return }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i("Live", "stream ended: ${e ?: "closed"}")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }

    private suspend fun listen(server: ServerConfig) {
        val tok = graph.navidrome.token(server)
        val url = server.url.trimEnd('/') + "/api/events?jwt=$tok"
        val call = http.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").build())
        val job = kotlin.coroutines.coroutineContext[Job]
        val handle = job?.invokeOnCompletion { call.cancel() }
        try {
            runInterruptible {
                call.execute().use { resp ->
                    if (resp.code == 401) { graph.navidrome.forget(); throw IllegalStateException("token expired") }
                    if (resp.code == 404) throw NotNavidromeException()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    checkMissedScan()
                    read(resp)
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun read(resp: Response) {
        val source = resp.body.source()
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: return
            when {
                line.isEmpty() -> {
                    event?.let { handle(it, data.toString()) }
                    event = null; data.clear()
                }
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
            }
        }
    }

    private var scanning = false

    /** The last scan seen, as (lastScan, count); null before the first connection. */
    private var lastScan: Pair<String?, Long?>? = null

    /**
     * The stream only runs while the app's on screen and doesn't replay what was
     * missed, so on each connection compare the server's last scan with the one
     * seen before: a scan in between means the music changed.
     */
    private fun checkMissedScan() {
        scope.launch {
            val s = runCatching { graph.client.strict.getScanStatus().ok().scanStatus }.getOrNull() ?: return@launch
            val now = s.lastScan to s.count
            val before = lastScan
            lastScan = now
            if (before != null && before != now) { Log.i("Live", "missed a scan"); graph.changed(Graph.Change.MUSIC) }
        }
    }

    private fun handle(event: String, data: String) {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (event) {
            "refreshResource" -> refresh(obj)
            "scanStatus" -> {
                val now = obj["scanning"]?.jsonPrimitive?.booleanOrNull ?: return
                // A finished scan can change albums and smart playlists, which send no events of their own.
                if (scanning && !now) { Log.i("Live", "scan finished"); graph.sync.request(); graph.changed(Graph.Change.MUSIC) }
                scanning = now
            }
        }
    }

    private fun refresh(obj: JsonObject) {
        fun ids(key: String) = (obj[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (obj.containsKey("*")) {
            graph.sync.request()
            graph.changed(Graph.Change.MUSIC)
            scope.launch { runCatching { graph.library.refreshPlaylists() } }
            return
        }
        // Status lists change with every song a device plays (Listening now): they only
        // mean fetching who's listening again, not reloading screens or syncing.
        val (status, playlists) = ids("playlist").partition(graph.listening::isStatusList)
        if (status.isNotEmpty()) graph.listening.refreshSoon()
        if (status.isNotEmpty() && playlists.isEmpty() && listOf("song", "album", "artist").all { ids(it).isEmpty() }) return
        // A kid's lists changed: a sync pass fetches them again (and downloads to match).
        val kid = graph.kids.current
        if (kid != null && playlists.any { it in kid.playlistIds }) {
            Log.i("Live", "kids' lists changed")
            graph.sync.request()
        }
        // Clean mode's lists (the grown-up's own) changed: play by the new ones.
        val clean = graph.kids.cleanRules.value
        if (clean != null && playlists.any { it in clean.playlistIds }) scope.launch { runCatching { graph.kids.refreshClean() } }
        if (playlists.isNotEmpty()) {
            Log.i("Live", "playlists changed: $playlists")
            // Open screens reload and downloaded ones sync (see Graph); the listing
            // refetch updates the counts Home and Library show.
            playlists.forEach(graph.library::notifyPlaylistEdited)
            scope.launch { runCatching { graph.library.refreshPlaylists() } }
        }
        // Song, album, artist and playlist events cover loves and stars made elsewhere (and play counts, ratings).
        if (playlists.isNotEmpty() || listOf("song", "album", "artist").any { ids(it).isNotEmpty() }) refreshStarredSoon()
    }

    private var starredJob: Job? = null

    /** Likes come in bursts (a whole album); refresh once they settle. */
    private fun refreshStarredSoon() {
        starredJob?.cancel()
        starredJob = scope.launch {
            delay(1_500)
            val before = graph.library.starredSongs.value
            graph.library.refreshStarred()
            // Play counts send song events too, so only sync Loved when loves changed.
            if (graph.library.starredSongs.value != before) graph.lovedChanged()
            graph.changed(Graph.Change.ANNOTATIONS)
        }
    }
}
