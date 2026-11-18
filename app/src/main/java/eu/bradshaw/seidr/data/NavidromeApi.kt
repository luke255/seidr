package eu.bradshaw.seidr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** The server has no `/auth/login`: it isn't Navidrome, so its native API isn't there. */
class NotNavidromeException : IOException("not a Navidrome server")

/** Navidrome answered, but turned the request down (HTTP [code]): retrying won't help. */
class NavidromeRefusedException(val code: Int) : IOException("HTTP $code")

/** A playlist as Navidrome's own API has it, with what Subsonic leaves out: a smart playlist's [rules]. */
data class NdPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val public: Boolean,
    val rules: JsonObject?,
    /** It has a picture of its own (uploaded, or from a URL). */
    val hasPicture: Boolean,
)

/**
 * Navidrome's own API (`/api/…`, what its web UI uses), for the few things
 * Subsonic has no call for. Signed in with a JWT from `/auth/login`, kept
 * until the server turns it down. Always the signed-in login, never a guest's.
 */
class NavidromeApi(private val http: OkHttpClient, private val server: () -> ServerConfig?) {
    private companion object {
        val JSON = "application/json".toMediaType()

        /**
         * A JSON body typed exactly `application/json`: as bytes, so OkHttp adds no
         * `; charset=utf-8`, which Navidrome takes for an M3U import instead.
         */
        fun JsonObject.body() = toString().toByteArray().toRequestBody(JSON)
    }
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var token: Pair<ServerConfig, String>? = null

    /** A session token for [server], signing in if there isn't one. */
    suspend fun token(server: ServerConfig): String =
        token?.takeIf { it.first == server }?.second ?: login(server).also { token = server to it }

    /** Drops the token (it was refused, or the login changed). */
    fun forget() { token = null }

    private suspend fun login(server: ServerConfig): String = runInterruptible(Dispatchers.IO) {
        val body = """{"username":${json.encodeToString(server.username)},"password":${json.encodeToString(server.password)}}"""
        val req = Request.Builder()
            .url(server.url.trimEnd('/') + "/auth/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw NotNavidromeException()
            if (!resp.isSuccessful) throw IOException("login HTTP ${resp.code}")
            json.parseToJsonElement(resp.body.string()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("no token")
        }
    }

    /** Sends a request to `/api/[path]`, signing in again once if the token has expired. */
    private suspend fun <T> call(path: String, build: Request.Builder.() -> Unit, read: (Response) -> T): T {
        val server = server() ?: throw IOException("not signed in")
        repeat(2) { attempt ->
            val tok = token(server)
            val req = Request.Builder().url(server.url.trimEnd('/') + "/api/" + path)
                .header("x-nd-authorization", "Bearer $tok").apply(build).build()
            val result = runInterruptible(Dispatchers.IO) {
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 401 && attempt == 0) null
                    else if (resp.code in 400..499) throw NavidromeRefusedException(resp.code)
                    else if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    // A reply that doesn't read (not JSON, say) is the server's fault, not a crash.
                    else Result.success(try { read(resp) } catch (e: kotlinx.serialization.SerializationException) { throw IOException("unreadable reply", e) })
                }
            }
            if (result != null) return result.getOrThrow()
            forget()
        }
        throw IOException("signed out by the server")
    }

    private fun ndPlaylist(o: JsonObject): NdPlaylist? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return NdPlaylist(
            id = str("id") ?: return null,
            name = str("name").orEmpty(),
            owner = str("ownerName"),
            public = (o["public"] as? JsonPrimitive)?.booleanOrNull ?: false,
            rules = o["rules"] as? JsonObject,
            hasPicture = listOf("uploadedImage", "externalImageUrl").any { !str(it).isNullOrEmpty() },
        )
    }

    /**
     * Every playlist this login can see, and whether the server takes playlist
     * pictures (Navidrome 0.61 added them, and with them `uploadedImage` on every
     * playlist).
     */
    suspend fun playlists(): Pair<List<NdPlaylist>, Boolean> = call("playlist", {}) { resp ->
        val list = json.parseToJsonElement(resp.body.string()) as? JsonArray ?: throw IOException("not a list")
        val objs = list.mapNotNull { it as? JsonObject }
        objs.mapNotNull(::ndPlaylist) to (objs.isEmpty() || objs.any { "uploadedImage" in it })
    }

    /**
     * Every playlist this login can see that has a picture of its own, by id,
     * with its owner's username; or null when the server can't take pictures.
     */
    suspend fun playlistPictures(): Map<String, String>? {
        val (list, supported) = playlists()
        return if (supported) list.filter { it.hasPicture }.associate { it.id to it.owner.orEmpty() } else null
    }

    /**
     * The playlists this login has starred, by id, with when (ISO 8601). Only
     * entries marked `starred` count, so a server that ignores the filter can't
     * pass off every playlist as starred.
     */
    suspend fun starredPlaylists(): List<Pair<String, String?>> = call("playlist?starred=true", {}) { resp ->
        val list = json.parseToJsonElement(resp.body.string()) as? JsonArray ?: throw IOException("not a list")
        list.mapNotNull { o ->
            (o as? JsonObject)?.takeIf { (it["starred"] as? JsonPrimitive)?.booleanOrNull == true }?.let {
                val id = (it["id"] as? JsonPrimitive)?.contentOrNull ?: return@let null
                id to (it["starredAt"] as? JsonPrimitive)?.contentOrNull
            }
        }
    }

    /** One playlist, fresh. */
    suspend fun playlist(id: String): NdPlaylist = call("playlist/$id", {}) { resp ->
        (json.parseToJsonElement(resp.body.string()) as? JsonObject)?.let(::ndPlaylist) ?: throw IOException("not a playlist")
    }

    /** Changes just the given [fields] of playlist [id] (Navidrome keeps the rest). */
    suspend fun updatePlaylist(id: String, fields: JsonObject) {
        call("playlist/$id", { put(fields.body()) }) {}
    }

    /** Deletes playlist [id]. */
    suspend fun deletePlaylist(id: String) {
        call("playlist/$id", { delete() }) {}
    }

    /** Creates a playlist from [fields] (`name`, `rules`, `public`…). Its id. */
    suspend fun createPlaylist(fields: JsonObject): String = call("playlist", { post(fields.body()) }) { resp ->
        (json.parseToJsonElement(resp.body.string()) as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("no id")
    }

    /** Uploads [jpeg] as [playlistId]'s picture. */
    suspend fun setPlaylistPicture(playlistId: String, jpeg: ByteArray) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "cover.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        call("playlist/$playlistId/image", { post(body) }) {}
    }

    /** Removes [playlistId]'s own picture, so the server's mosaic shows again. */
    suspend fun removePlaylistPicture(playlistId: String) {
        call("playlist/$playlistId/image", { delete() }) {}
    }
}
