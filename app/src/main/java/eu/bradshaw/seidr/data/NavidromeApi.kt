package eu.bradshaw.seidr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/**
 * Navidrome's own API (`/api/…`, what its web UI uses), for the few things
 * Subsonic has no call for. Signed in with a JWT from `/auth/login`, kept
 * until the server turns it down. Always the signed-in login, never a guest's.
 */
class NavidromeApi(private val http: OkHttpClient, private val server: () -> ServerConfig?) {
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
                    else if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    else Result.success(read(resp))
                }
            }
            if (result != null) return result.getOrThrow()
            forget()
        }
        throw IOException("signed out by the server")
    }

    /**
     * Every playlist this login can see that has a picture of its own (uploaded,
     * or from a URL), by id, with its owner's username; or null when the server
     * can't take pictures (before Navidrome 0.61).
     */
    suspend fun playlistPictures(): Map<String, String>? = call("playlist", {}) { resp ->
        val list = json.parseToJsonElement(resp.body.string()) as? JsonArray ?: throw IOException("not a list")
        val objs = list.map { it.jsonObject }
        // Navidrome 0.61 added pictures, and with them this field on every playlist.
        if (objs.isNotEmpty() && objs.none { "uploadedImage" in it }) return@call null
        objs.filter { o ->
            listOf("uploadedImage", "externalImageUrl").any { !o[it]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
        }.mapNotNull { o ->
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to o["ownerName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.toMap()
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
