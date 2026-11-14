package eu.bradshaw.seidr.data

import android.util.Log
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.db.SourceType
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private var token: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    // Keep-alives arrive every ~15 s, so a minute of silence means the stream is dead.
    private val http = graph.baseHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Sign-out: forget the session token too. */
    fun reset() {
        stop()
        token = null
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
            if (e is NotNavidrome) { Log.i("Live", "no event stream on this server"); return }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.i("Live", "stream ended: ${e ?: "closed"}")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }

    private suspend fun listen(server: ServerConfig) {
        val tok = token ?: login(server).also { token = it }
        val url = server.url.trimEnd('/') + "/api/events?jwt=$tok"
        val call = http.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").build())
        val job = kotlin.coroutines.coroutineContext[Job]
        val handle = job?.invokeOnCompletion { call.cancel() }
        try {
            runInterruptible {
                call.execute().use { resp ->
                    if (resp.code == 401) { token = null; throw IllegalStateException("token expired") }
                    if (resp.code == 404) throw NotNavidrome()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
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

    private fun handle(event: String, data: String) {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (event) {
            "refreshResource" -> refresh(obj)
            "scanStatus" -> {
                val now = obj["scanning"]?.jsonPrimitive?.booleanOrNull ?: return
                // A finished scan can change albums and smart playlists, which send no events of their own.
                if (scanning && !now) { Log.i("Live", "scan finished"); graph.sync.request() }
                scanning = now
            }
        }
    }

    private fun refresh(obj: JsonObject) {
        fun ids(key: String) = (obj[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (obj.containsKey("*")) {
            graph.sync.request()
            scope.launch { runCatching { graph.library.refreshPlaylists() } }
            return
        }
        val playlists = ids("playlist")
        if (playlists.isNotEmpty()) {
            Log.i("Live", "playlists changed: $playlists")
            playlists.forEach(graph.library::notifyPlaylistEdited)
            scope.launch { runCatching { graph.library.refreshPlaylists() } }
            val sources = graph.downloads.sources.value.keys
            val force = playlists.map { SourceType.sourceId(SourceType.PLAYLIST, it) }.filter { it in sources }.toSet()
            if (force.isNotEmpty()) graph.sync.request(force)
        }
        // Song and album events cover likes made elsewhere (and play counts, ratings).
        if (ids("song").isNotEmpty() || ids("album").isNotEmpty()) refreshStarredSoon()
    }

    private var starredJob: Job? = null

    /** Likes come in bursts (a whole album); refresh once they settle. */
    private fun refreshStarredSoon() {
        starredJob?.cancel()
        starredJob = scope.launch {
            delay(1_500)
            graph.library.refreshStarred()
        }
    }

    private suspend fun login(server: ServerConfig): String = runInterruptible {
        val body = """{"username":${json.encodeToString(server.username)},"password":${json.encodeToString(server.password)}}"""
        val req = Request.Builder()
            .url(server.url.trimEnd('/') + "/auth/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw NotNavidrome()
            if (!resp.isSuccessful) throw IllegalStateException("login HTTP ${resp.code}")
            json.parseToJsonElement(resp.body.string()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("no token")
        }
    }

    private class NotNavidrome : Exception()
}
