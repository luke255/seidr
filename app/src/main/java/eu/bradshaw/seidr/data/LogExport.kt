package eu.bradshaw.seidr.data

import android.os.Build
import eu.bradshaw.seidr.BuildConfig
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings → About → Share a log: this app's recent logcat (an app reads only
 * its own), scrubbed so it's safe to post in a public issue. The screen shows
 * the result before it goes anywhere.
 */
object LogExport {
    const val FILE_NAME = "seidr-log.txt"
    private const val LINES = 4000

    /** What the log says about this install, then the scrubbed log itself. */
    suspend fun collect(graph: Graph, isTv: Boolean): String = withContext(Dispatchers.IO) {
        val server = withTimeoutOrNull(5_000) {
            runCatching { graph.client.strict.ping().ok() }.getOrNull()
                ?.let { "${it.type ?: "unknown"} ${it.serverVersion.orEmpty()} (Subsonic API ${it.version}${if (it.openSubsonic) ", OpenSubsonic" else ""})" }
        }
        val login = graph.settings.server.value
        val header = buildString {
            appendLine("Seiðr ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}${if (isTv) ", TV" else ""}")
            appendLine("Server: ${server ?: if (login == null) "not signed in" else "unreachable"}")
            if (login?.isKid == true) appendLine("Kid's login")
            if (graph.guest.value != null) appendLine("Playing for a guest")
        }
        val log = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", LINES.toString())
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        }.getOrElse { if (it is CancellationException) throw it; "Couldn't read the log: ${it.javaClass.simpleName}" }
        redact(header + "\n" + log, known(graph))
    }

    /** The exact values to take out, and what each becomes. */
    private fun known(graph: Graph): Map<String, String> = buildMap {
        fun host(url: String?) = url?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.takeIf { it.isNotBlank() }
        val logins = listOfNotNull(graph.settings.server.value, graph.guest.value?.config)
        for (l in logins) {
            put(l.url.trimEnd('/'), "<server>")
            host(l.url)?.let { put(it, "<server>") }
            put(l.username, "<user>")
            l.kidOf?.let { put(it, "<user>") }
        }
        for (k in graph.settings.kidLogins.value) put(k.username, "<kid>")
        // A device that was never named goes by its model, which the header shows anyway.
        val names = runCatching { listOf(graph.connect.name) + graph.connect.devices.value.map { it.name } }.getOrDefault(emptyList())
        for (n in names) if (!n.equals(Build.MODEL, ignoreCase = true)) put(n, "<device>")
    }

    private val GITHUB = setOf("github.com", "api.github.com", "objects.githubusercontent.com", "raw.githubusercontent.com")
    private val SECRET_PARAM = Regex("""(?i)([?&;](?:u|p|t|s|jwt|token|salt|password|apikey)=)[^&\s"'<>]+""")
    private val AUTH_HEADER = Regex("""(?i)((?:x-nd-)?authorization:\s*)\S+(?:\s+\S+)?""")
    private val JWT = Regex("""eyJ[\w-]{5,}\.[\w-]{5,}\.[\w-]+""")
    private val URL_HOST = Regex("""(?i)\b((?:https?|wss?)://)([^\s/"'<>?#]+)""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    // Four or more colons, or a "::": a log's own timestamps (12:34:56) have two.
    private val IPV6_FULL = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}(?![\w:])""")
    private val IPV6_SHORT = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?(?![\w:])""")

    /**
     * Takes out logins, addresses and names: pure, so it's unit-tested. [known]
     * maps exact strings (server address, usernames, device names) to what
     * replaces them; names are matched whole and ignoring case.
     */
    fun redact(text: String, known: Map<String, String>): String {
        var out = text
        // Longest first, so a full address goes before the host inside it.
        for ((value, label) in known.entries.filter { it.key.isNotBlank() }.sortedByDescending { it.key.length }) {
            out = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?![\\p{L}\\p{N}])").replace(out) { label }
        }
        out = SECRET_PARAM.replace(out) { it.groupValues[1] + "<hidden>" }
        out = AUTH_HEADER.replace(out) { it.groupValues[1] + "<hidden>" }
        out = JWT.replace(out, "<token>")
        out = URL_HOST.replace(out) { m ->
            val host = m.groupValues[2].substringAfterLast('@').substringBefore(':').lowercase()
            if (host in GITHUB || host == "<server>") m.groupValues[1] + m.groupValues[2].substringAfterLast('@') else m.groupValues[1] + "<host>"
        }
        out = EMAIL.replace(out, "<email>")
        out = IPV4.replace(out, "<ip>")
        out = IPV6_FULL.replace(out, "<ip>")
        out = IPV6_SHORT.replace(out) { if (it.value == "::") it.value else "<ip>" }
        return out
    }
}
