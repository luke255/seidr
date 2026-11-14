package eu.bradshaw.seidr.vis

import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Test

/**
 * `presets.txt` must list exactly the bundled presets. To regenerate, from
 * app/src/main/assets/milkdrop/presets:
 * `find . -name "*.milk" | sed 's|^\./||' | LC_ALL=C sort > ../presets.txt`
 */
class MilkdropAssetsTest {
    @Test fun `preset index matches the bundled presets`() {
        val root = File("src/main/assets/milkdrop")
        val dir = File(root, "presets")
        val files = dir.walk().filter { it.isFile && it.name.endsWith(".milk") }
            .map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
        val index = File(root, "presets.txt").readLines().filter { it.isNotBlank() }.sorted()
        assertEquals(files, index)
    }
}
