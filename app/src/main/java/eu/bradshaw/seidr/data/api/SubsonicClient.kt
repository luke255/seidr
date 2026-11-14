package eu.bradshaw.seidr.data.api

import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.ServerStatus
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SubsonicException(val code: Int, message: String) : IOException(message)

/** Thrown when there is no server configured yet. */
class NotLoggedInException : IOException("Not logged in")

/**
 * Builds the two API instances:
 *  - [api] for screens: falls back to the last-seen response when offline.
 *  - [strict] for sync and edits: a network failure is always an exception.
 */
class SubsonicClient(
    /** The configured server, read on every request so logging in/out takes effect at once. */
    private val server: () -> ServerConfig?,
    /** Fixed salt for cover-art URLs (see [coverArtUrl]). */
    private val coverSalt: String,
    baseClient: OkHttpClient,
    private val store: ResponseStore,
    private val status: ServerStatus,
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val random = SecureRandom()

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val server = server() ?: throw NotLoggedInException()
        val url = rewrite(req.url, server)
        chain.proceed(req.newBuilder().url(url).build())
    }

    // Tracks reachability from real traffic so offline mode reacts quickly.
    private val statusInterceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request()).also { status.reportSuccess() }
        } catch (e: IOException) {
            if (e !is NotLoggedInException) status.reportFailure()
            throw e
        }
    }

    private val fallbackInterceptor = Interceptor { chain ->
        val req = chain.request()
        val endpoint = req.url.pathSegments.lastOrNull().orEmpty()
        if (req.method != "GET" || endpoint !in ResponseStore.CACHEABLE) return@Interceptor chain.proceed(req)
        val key = cacheKey(req.url)
        fun cached(body: String) = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("cached")
            .header("X-Seidr-Cached", "1")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        // Known offline: answer from the last-seen copy at once rather than wait out a
        // connect timeout. SeidrApp's probe notices when the server is back.
        if (!status.online.value) store.get(key)?.let { return@Interceptor cached(it) }
        try {
            val resp = chain.proceed(req)
            if (!resp.isSuccessful) return@Interceptor resp
            val body = resp.body.string()
            if (body.contains("\"status\":\"ok\"")) runCatching { store.put(key, body) }
            resp.newBuilder().body(body.toResponseBody("application/json".toMediaType())).build()
        } catch (e: IOException) {
            cached(store.get(key) ?: throw e)
        }
    }

    /** The last-seen response for a cacheable GET, without touching the network. */
    fun cached(endpoint: String, vararg params: Pair<String, String>): Envelope? {
        val key = endpoint + "?" + params.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        return store.get(key)?.let { runCatching { json.decodeFromString<Envelope>(it) }.getOrNull() }
    }

    /** Forgets last-seen responses (sign-out, or a different server). */
    fun clearCache() = store.clear()

    private val strictHttp = baseClient.newBuilder()
        .addInterceptor(statusInterceptor)
        .addInterceptor(authInterceptor)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val lenientHttp = baseClient.newBuilder()
        .addInterceptor(fallbackInterceptor)
        .addInterceptor(statusInterceptor)
        .addInterceptor(authInterceptor)
        .build()

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        // Placeholder; authInterceptor swaps in the configured server.
        .baseUrl("http://seidr.invalid/rest/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SubsonicApi::class.java)

    val api: SubsonicApi = retrofit(lenientHttp)
    val strict: SubsonicApi = retrofit(strictHttp)

    /** Plain authenticated client for streams and downloads. */
    val mediaHttp: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(statusInterceptor)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun rewrite(url: HttpUrl, server: ServerConfig, salt: String? = null): HttpUrl {
        val endpoint = url.pathSegments.last()
        val base = server.url.toHttpUrl()
        val b = base.newBuilder().addPathSegment("rest").addPathSegment(endpoint)
        for (i in 0 until url.querySize) b.addQueryParameter(url.queryParameterName(i), url.queryParameterValue(i))
        return addAuth(b, server, salt).build()
    }

    private fun addAuth(b: HttpUrl.Builder, server: ServerConfig, fixedSalt: String? = null): HttpUrl.Builder {
        val salt = fixedSalt ?: ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return b.setQueryParameter("u", server.username)
            .setQueryParameter("t", md5(server.password + salt))
            .setQueryParameter("s", salt)
            .setQueryParameter("v", "1.16.1")
            .setQueryParameter("c", "seidr")
            .setQueryParameter("f", "json")
    }

    private fun endpointUrl(endpoint: String, server: ServerConfig, salt: String?, vararg params: Pair<String, String>): String {
        val b = server.url.toHttpUrl().newBuilder().addPathSegment("rest").addPathSegment(endpoint)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return addAuth(b, server, salt).build().toString()
    }

    /** Original file, never transcoded. */
    fun streamUrl(id: String): String? = server()?.let {
        endpointUrl("stream.view", it, null, "id" to id, "format" to "raw")
    }

    fun downloadUrl(id: String): String? = server()?.let {
        endpointUrl("download.view", it, null, "id" to id)
    }

    /** Stable for a given id and size, so image caches can key on it. */
    fun coverArtUrl(id: String, size: Int): String? = server()?.let {
        endpointUrl("getCoverArt.view", it, coverSalt, "id" to id, "size" to size.toString())
    }

    private fun cacheKey(url: HttpUrl): String {
        val skip = setOf("u", "t", "s", "p", "v", "c", "f")
        val params = (0 until url.querySize)
            .map { url.queryParameterName(it) to url.queryParameterValue(it) }
            .filter { it.first !in skip }
            .sortedWith(compareBy({ it.first }, { it.second }))
        return url.pathSegments.last() + "?" + params.joinToString("&") { "${it.first}=${it.second}" }
    }

    companion object {
        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/** Unwraps an envelope, turning a Subsonic error into an exception. */
fun Envelope.ok(): SubsonicResponse {
    if (response.status != "ok") {
        throw SubsonicException(response.error?.code ?: 0, response.error?.message ?: "Server error")
    }
    return response
}

/** Subsonic error code 70: the requested data was not found. */
val Throwable.isNotFound: Boolean get() = this is SubsonicException && code == 70
