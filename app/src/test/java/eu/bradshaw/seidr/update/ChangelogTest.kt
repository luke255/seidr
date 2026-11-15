package eu.bradshaw.seidr.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

class ChangelogTest {
    @Test fun `parses headings, bullets and continuation lines`() {
        val text = """
            # What's new

            Intro text, ignored.

            ## 1.2.0

            - **Update everything.** Then
              carry on.
            - Second

            ## 1.1.0

            - Only one
        """.trimIndent()
        assertEquals(
            listOf(
                Changelog.Release("1.2.0", listOf("Update everything. Then carry on.", "Second")),
                Changelog.Release("1.1.0", listOf("Only one")),
            ),
            Changelog.parse(text),
        )
        assertEquals("• Only one", Changelog.notes(text, "1.1.0"))
        assertNull(Changelog.notes(text, "9.9.9"))
    }

    private fun version(v: String) = v.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }

    /**
     * The release gate (scripts/release.sh checks the same): the version in
     * gradle.properties has a non-empty section, and nothing above it is older.
     * A newer heading on top is notes being written ahead of the next release.
     */
    @Test fun `CHANGELOG has an entry for this version`() {
        val current = Properties().apply { File("../gradle.properties").inputStream().use(::load) }.getProperty("seidr.version")
        val releases = Changelog.parse(File("../CHANGELOG.md").readText())
        val mine = releases.firstOrNull { it.version == current }
        assertTrue("CHANGELOG.md has no '## $current' section", mine != null)
        assertTrue("CHANGELOG.md's '## $current' has no entries", mine!!.items.isNotEmpty())
        assertTrue("CHANGELOG.md's top heading is older than $current", version(releases.first().version) >= version(current))
        assertEquals("newest first", releases.sortedByDescending { version(it.version) }.map { it.version }, releases.map { it.version })
    }
}
