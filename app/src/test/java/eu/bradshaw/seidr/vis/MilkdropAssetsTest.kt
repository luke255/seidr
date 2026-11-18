package eu.bradshaw.seidr.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `manifest.txt` defines the pack PresetPack downloads; `scripts/milkdrop-manifest.py`
 * writes it. This checks its shape: two pinned commits, then a git blob hash and
 * a path per line, presets and textures both there, nothing twice.
 */
class MilkdropAssetsTest {
    private val lines = File("src/main/assets/milkdrop/manifest.txt").readLines(Charsets.UTF_8)

    @Test fun `header pins both repositories to a commit`() {
        val header = lines.takeWhile { it.startsWith("#") }
        assertEquals(2, header.size)
        assertTrue(header[0].endsWith("presets-cream-of-the-crop " + header[0].substringAfterLast(' ')))
        assertTrue(header[1].contains("presets-milkdrop-texture-pack "))
        header.forEach { assertTrue(it, Regex("[0-9a-f]{40}").matches(it.substringAfterLast(' '))) }
    }

    @Test fun `every file has a hash and a unique path`() {
        val files = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        files.forEach { assertTrue(it, Regex("[0-9a-f]{40} (presets/.+\\.milk|textures/.+)").matches(it)) }
        val paths = files.map { it.substringAfter(' ') }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.count { it.startsWith("presets/") } > 9000)
        assertTrue(paths.count { it.startsWith("textures/") } > 50)
    }

    @Test fun `blob hashes are git's`() {
        // "hello\n", as `git hash-object` gives it.
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", PresetPack.blobSha("hello\n".toByteArray()))
    }
}
