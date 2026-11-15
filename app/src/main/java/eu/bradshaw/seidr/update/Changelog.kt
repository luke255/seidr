package eu.bradshaw.seidr.update

/**
 * `CHANGELOG.md` (bundled as an asset): `## <version>` headings, each with a
 * list of `- ` items; continuation lines are indented. Anything else (the title,
 * the intro) is ignored. `**bold**` markers are dropped.
 */
object Changelog {
    data class Release(val version: String, val items: List<String>)

    const val ASSET = "CHANGELOG.md"

    fun parse(text: String): List<Release> {
        val out = mutableListOf<Release>()
        var version: String? = null
        val items = mutableListOf<String>()
        fun flush() { version?.let { out += Release(it, items.toList()) }; items.clear() }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
                version == null -> {}
                line.startsWith("- ") -> items += line.removePrefix("- ").trim()
                line.isNotBlank() && raw.first().isWhitespace() && items.isNotEmpty() ->
                    items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        flush()
        return out.map { r -> r.copy(items = r.items.map { it.replace("**", "") }) }
    }

    /** One version's items as plain text, a line each: what `version.json` carries as `notes`. */
    fun notes(text: String, version: String): String? =
        parse(text).firstOrNull { it.version == version }?.items?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "• $it" }
}
