package eu.bradshaw.seidr.sync

/**
 * What [DownloadWorker] accepts as a track, kept pure so it can be tested.
 * Subsonic servers report errors (track gone, no permission) as HTTP 200 with
 * a JSON or XML envelope, so a 2xx alone never means "this is the file".
 */
object DownloadRules {
    sealed interface Start {
        /** Write the body, appending to the part file when [append]. */
        data class Write(val append: Boolean) : Start
        /** The part file is already full size or past it: delete it and start again. */
        data object Restart : Start
        data class Fail(val message: String) : Start
    }

    /**
     * Decides what to do with a response before reading its body. [have] is the
     * part file's length, sent as a Range request when non-zero.
     */
    fun start(code: Int, contentType: String?, have: Long): Start = when {
        code == 416 && have > 0 -> Start.Restart
        code !in 200..299 -> Start.Fail("HTTP $code")
        isEnvelope(contentType) -> Start.Fail("server sent an error, not the file")
        // 206 continues the part; 200 (no Range, or If-Range said the file changed) starts over.
        else -> Start.Write(append = code == 206 && have > 0)
    }

    /** A Subsonic response envelope rather than media. */
    fun isEnvelope(contentType: String?): Boolean {
        val t = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return t == "application/json" || t == "application/xml" || t == "text/xml" ||
            t.endsWith("+json") || t.endsWith("+xml")
    }

    /** The message from a Subsonic error envelope (JSON or XML), if one can be found. */
    fun envelopeMessage(body: String): String? =
        Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?: Regex("message=\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    /**
     * Checks a finished part file of [length] bytes. [total] is what this
     * response said the whole file would be (-1 if unknown); [expected] is the
     * server's size from the track listing (0 if unknown). Null means keep it.
     */
    fun finish(expected: Long, total: Long, length: Long): String? = when {
        total > 0 && length != total -> "incomplete: $length of $total bytes"
        expected > 0 && length != expected -> "got $length bytes, expected $expected"
        else -> null
    }
}
